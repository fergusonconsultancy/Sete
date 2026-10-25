package com.fergusonconsultancy.sete

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The garden on this phone: one JSON file, written whole on every change. When sync is set up,
 * the household's copy in Firestore is the source of truth and this file mirrors it, so the
 * garden still opens, and reminders still fire, with no signal.
 */
object Store {
    private lateinit var app: Context
    private lateinit var file: File
    lateinit var prefs: SharedPreferences
        private set
    private val plants = LinkedHashMap<String, Plant>()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var ready = false

    @Synchronized
    fun init(c: Context) {
        if (ready) return
        app = c.applicationContext
        prefs = app.getSharedPreferences("sete", Context.MODE_PRIVATE)
        file = File(app.filesDir, "garden.json")
        if (file.exists()) {
            runCatching {
                val a = JSONObject(file.readText()).getJSONArray("plants")
                for (i in 0 until a.length()) Plant.fromJson(a.getJSONObject(i)).let { plants[it.id] = it }
            }
        } else {
            Seed.plants(System.currentTimeMillis()).forEach { plants[it.id] = it }
            save()
        }
        ready = true
    }

    // ---- Settings ---------------------------------------------------------------------------
    /** Morning reminder, minutes after midnight. */
    var morning: Int
        get() = prefs.getInt("morning", 8 * 60 + 30)
        set(v) = prefs.edit().putInt("morning", v).apply()
    var evening: Boolean
        get() = prefs.getBoolean("evening", true)
        set(v) = prefs.edit().putBoolean("evening", v).apply()
    /** Who is watering on this phone, as written in the history. */
    var me: String
        get() = prefs.getString("me", "") ?: ""
        set(v) = prefs.edit().putString("me", v.trim()).apply()
    var household: String
        get() = prefs.getString("household", "") ?: ""
        set(v) = prefs.edit().putString("household", v).apply()
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) = prefs.edit().putBoolean("onboarded", v).apply()

    // ---- Reading ----------------------------------------------------------------------------
    @Synchronized
    fun all(): List<Plant> = plants.values.toList()

    @Synchronized
    fun get(id: String): Plant? = plants[id]

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    private fun changed() {
        main.post { listeners.toList().forEach { it() } }
    }

    // ---- Writing ----------------------------------------------------------------------------
    @Synchronized
    private fun save() {
        val a = JSONArray(); plants.values.forEach { a.put(it.toJson()) }
        val tmp = File(file.parentFile, "garden.tmp")
        tmp.writeText(JSONObject().put("v", 1).put("plants", a).toString())
        tmp.renameTo(file)
    }

    /** Writes one plant here and, when sync is on, to the household. */
    fun put(p: Plant, push: Boolean = true) {
        val stamped = p.copy(updated = System.currentTimeMillis())
        synchronized(this) { plants[p.id] = stamped; save() }
        changed()
        if (push) Sync.push(app, stamped)
        Reminders.refresh(app)
    }

    fun remove(id: String) {
        synchronized(this) { plants.remove(id); save() }
        changed()
        Sync.delete(app, id)
        Reminders.cancelPlant(app, id)
    }

    /** The household's copy replaces this phone's, whole. */
    fun replaceAll(list: List<Plant>) {
        synchronized(this) {
            if (list.map { it.toJson().toString() } == plants.values.map { it.toJson().toString() }) return
            plants.clear(); list.sortedBy { it.order }.forEach { plants[it.id] = it }; save()
        }
        changed()
        Reminders.refresh(app)
    }

    // ---- The three things a hand on the soil can tell us ---------------------------------------
    enum class Act { WATERED, DAMP, DRY }

    /** Records an act and returns the plant as it was, for undo. */
    fun act(id: String, a: Act): Plant? {
        val p = get(id) ?: return null
        val now = System.currentTimeMillis()
        val entry = LogEntry(now, me, when (a) { Act.WATERED -> "w"; Act.DAMP -> "damp"; Act.DRY -> "dry" })
        val log = (listOf(entry) + p.log).take(14)
        val next = when (a) {
            Act.WATERED -> p.copy(last = now, snooze = null, log = log)
            // It was bone dry: water now and come round a little sooner next time
            Act.DRY -> p.copy(last = now, snooze = null, log = log, learn = (p.learn * 0.9).coerceAtLeast(0.5))
            // Still damp: wait two days and learn to wait a little longer
            Act.DAMP -> p.copy(snooze = now + 2 * DAY, log = log, learn = (p.learn * 1.1).coerceAtMost(2.0))
        }
        put(next)
        return p
    }

    fun newId(): String = "p" + System.currentTimeMillis().toString(36)
}
