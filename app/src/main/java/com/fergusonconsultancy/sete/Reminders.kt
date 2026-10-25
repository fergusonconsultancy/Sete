package com.fergusonconsultancy.sete

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/** Photos: bundled ones by key, ones taken in the app as base64 JPEG. Decoded once and kept. */
object Photos {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(c: Context, p: Plant, maxSide: Int = 700): Bitmap? {
        if (p.photo.isBlank()) return null
        val key = "${p.photo.hashCode()}:$maxSide"
        cache.get(key)?.let { return it }
        val opts = BitmapFactory.Options().apply { inSampleSize = if (maxSide < 300) 4 else 1 }
        val bmp = runCatching {
            if (p.photo.startsWith("data:")) {
                val bytes = Base64.decode(p.photo.substringAfter(","), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } else c.assets.open("plants/${p.photo}.jpg").use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }
}

/**
 * Reminders: a morning round at the time she picks, and an evening nudge at seven for anything
 * still thirsty. Each thirsty plant gets its own notification with Watered and Still damp on it,
 * bundled under one summary, so a whole round can be done from the lock screen or the cover screen.
 */
object Reminders {
    const val CHANNEL = "thirst"
    private const val GROUP = "garden"
    private const val SUMMARY_ID = 1000
    private const val TAG = "plant"
    const val ACTION_ALARM = "com.fergusonconsultancy.sete.ALARM"
    const val ACTION_WATERED = "com.fergusonconsultancy.sete.WATERED"
    const val ACTION_DAMP = "com.fergusonconsultancy.sete.DAMP"
    const val EVENING_MINUTES = 19 * 60

    fun channel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Thirsty plants", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The morning round and the evening nudge"
            })
        }
    }

    private fun nextAt(minutes: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now + 30_000) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun alarmIntent(c: Context, slot: String, code: Int) = PendingIntent.getBroadcast(
        c, code, Intent(c, ReminderReceiver::class.java).setAction(ACTION_ALARM).putExtra("slot", slot),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canExact(c: Context): Boolean = c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Sets the next morning and evening alarms. Safe to call as often as you like. */
    fun schedule(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        fun set(at: Long, pi: PendingIntent) {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        set(nextAt(Store.morning, now), alarmIntent(c, "morning", 1))
        val evening = alarmIntent(c, "evening", 2)
        if (Store.evening) set(nextAt(EVENING_MINUTES, now), evening) else am.cancel(evening)
    }

    /** After any change: keep the alarms set and take down notifications for plants no longer thirsty. */
    fun refresh(c: Context) {
        runCatching { schedule(c) }
        val nm = c.getSystemService(NotificationManager::class.java)
        val shown = runCatching { nm.activeNotifications.filter { it.tag == TAG } }.getOrDefault(emptyList())
        if (shown.isEmpty()) return
        var left = 0
        shown.forEach { sbn ->
            val id = sbn.notification.extras.getString("plantId")
            val p = id?.let { Store.get(it) }
            if (p == null || Rhythm.standing(p).thirst != Thirst.THIRSTY) nm.cancel(TAG, sbn.id) else left++
        }
        if (left == 0) nm.cancel(SUMMARY_ID)
    }

    fun cancelPlant(c: Context, id: String) {
        c.getSystemService(NotificationManager::class.java).cancel(TAG, id.hashCode())
        refresh(c)
    }

    private fun actionIntent(c: Context, action: String, id: String, n: Int) = PendingIntent.getBroadcast(
        c, id.hashCode() * 31 + n, Intent(c, ReminderReceiver::class.java).setAction(action).putExtra("plant", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openIntent(c: Context, id: String?) = PendingIntent.getActivity(
        c, (id ?: "garden").hashCode(),
        Intent(c, MainActivity::class.java).putExtra("plant", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun names(list: List<Plant>): String = when (list.size) {
        1 -> list[0].name
        2 -> "${list[0].name} and ${list[1].name}"
        else -> list.dropLast(1).joinToString(", ") { it.name } + " and " + list.last().name
    }

    /** The round itself. [test] shows the current state even when nothing is due. */
    fun round(c: Context, slot: String, test: Boolean = false) {
        channel(c)
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return
        val now = System.currentTimeMillis()
        val all = Store.all().map { it to Rhythm.standing(it, now) }
        val thirsty = all.filter { it.second.thirst == Thirst.THIRSTY }.sortedBy { it.second.remaining ?: 0.0 }
        val waiting = all.count { it.second.thirst == Thirst.NEW }
        val nm = NotificationManagerCompat.from(c)

        if (thirsty.isEmpty()) {
            if ((slot == "morning" && waiting > 0) || test) {
                val title = if (waiting > 0) "$waiting plants are waiting for their first check" else "Nothing is thirsty"
                val text = if (waiting > 0) "Feel each pot's soil, then tap Watered or Still damp." else "Every plant has had a drink. Reminders are working."
                runCatching {
                    nm.notify(SUMMARY_ID, NotificationCompat.Builder(c, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_drop).setColor(Kit.WATER)
                        .setContentTitle(title).setContentText(text)
                        .setContentIntent(openIntent(c, null)).setAutoCancel(true).build())
                }
            }
            return
        }

        val shown = thirsty.take(8)
        shown.forEach { (p, s) ->
            val k = Kinds[p.kind]
            val line = Rhythm.dueText(s) + if (p.spot.isNotBlank()) " · ${p.spot}" else ""
            val extras = android.os.Bundle().apply { putString("plantId", p.id) }
            val b = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_drop).setColor(Kit.WATER)
                .setContentTitle(p.name).setContentText(line)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$line\n${k.cue}"))
                .setGroup(GROUP).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setOnlyAlertOnce(true).setAutoCancel(true).addExtras(extras)
                .setContentIntent(openIntent(c, p.id))
                .addAction(0, "Watered", actionIntent(c, ACTION_WATERED, p.id, 1))
                .addAction(0, "Still damp", actionIntent(c, ACTION_DAMP, p.id, 2))
            Photos.get(c, p, 256)?.let { b.setLargeIcon(it) }
            runCatching { nm.notify(TAG, p.id.hashCode(), b.build()) }
        }
        val list = thirsty.map { it.first }
        val title = if (list.size == 1) "${list[0].name} is thirsty" else "${list.size} plants are thirsty"
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        thirsty.take(6).forEach { (p, s) -> inbox.addLine("${p.name}  ·  ${Rhythm.dueText(s)}") }
        if (thirsty.size > 6) inbox.setSummaryText("and ${thirsty.size - 6} more")
        runCatching {
            nm.notify(SUMMARY_ID, NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_drop).setColor(Kit.WATER)
                .setContentTitle(title).setContentText(names(list.take(4)))
                .setStyle(inbox).setGroup(GROUP).setGroupSummary(true)
                .setContentIntent(openIntent(c, null)).setAutoCancel(true).build())
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.init(c)
        when (i.action) {
            Reminders.ACTION_ALARM -> {
                val pending = goAsync()
                Thread {
                    try {
                        // Pick up anything watered on the other phone first
                        Sync.refreshBlocking(c)
                        Reminders.round(c, i.getStringExtra("slot") ?: "morning")
                        Reminders.schedule(c)
                    } finally { pending.finish() }
                }.start()
            }
            Reminders.ACTION_WATERED, Reminders.ACTION_DAMP -> {
                val id = i.getStringExtra("plant") ?: return
                Store.act(id, if (i.action == Reminders.ACTION_WATERED) Store.Act.WATERED else Store.Act.DAMP)
                Reminders.cancelPlant(c, id)
            }
            else -> Reminders.schedule(c)
        }
    }
}
