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
            // Gardens from before spots existed: start each photographed plant from its photo's spot
            var moved = false
            plants.values.toList().forEach { p ->
                val seed = Seed.spots[p.id]
                if (seed != null && p.light == null && p.pot == null && p.lightFrom.isEmpty()) {
                    plants[p.id] = p.copy(light = seed.first, heat = seed.second, pot = seed.third, lightFrom = "photo"); moved = true
                }
            }
            if (moved) save()
        } else {
            Seed.plants(System.currentTimeMillis()).forEach { plants[it.id] = it }
            save()
        }
        ready = true
        // Each photo's leaf colour, measured once, so later photos have something to compare with
        Thread { baselines() }.start()
    }

    private fun baselines() {
        all().filter { it.reads.isEmpty() && it.photo.isNotBlank() }.forEach { p ->
            val bmp = Photos.get(app, p) ?: return@forEach
            val g = Look.green(bmp) ?: return@forEach
            val now = get(p.id) ?: return@forEach
            if (now.reads.isEmpty()) synchronized(this) { plants[p.id] = now.copy(reads = listOf(Read(now.photoAt ?: now.updated, g))); save() }
        }
    }

    /** A new photo of a plant: kept with its leaf colour and, if the photo could tell, the light. */
    fun newPhoto(id: String, photo: String, green: Double?, lux: Double?): Plant? {
        val p = get(id) ?: return null
        val now = System.currentTimeMillis()
        var next = p.copy(photo = photo, photoAt = now, reads = if (green != null) (listOf(Read(now, green)) + p.reads).take(12) else p.reads)
        // A photo's light estimate never overrides a measured reading
        if (lux != null && p.lightFrom != "measured" && p.lightFrom != "set") next = next.copy(light = Light.fromLux(lux), lux = lux, luxAt = now, lightFrom = "photo")
        put(next)
        return next
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
    var sound: Boolean
        get() = prefs.getBoolean("sound", true)
        set(v) = prefs.edit().putBoolean("sound", v).apply()
    /** Rounds finished, one per day at most, since a plant was last left thirsty for two days. */
    var streak: Int
        get() = prefs.getInt("streak", 0)
        set(v) = prefs.edit().putInt("streak", v).apply()
    private var streakDay: Long
        get() = prefs.getLong("streakDay", -1L)
        set(v) = prefs.edit().putLong("streakDay", v).apply()

    private fun today(): Long {
        val c = java.util.Calendar.getInstance()
        return (System.currentTimeMillis() + c.get(java.util.Calendar.ZONE_OFFSET) + c.get(java.util.Calendar.DST_OFFSET)) / DAY
    }

    /** Called whenever the garden is checked: a plant two days overdue ends the streak. */
    fun checkNeglect() {
        val now = System.currentTimeMillis()
        if (all().any { (Rhythm.standing(it, now).remaining ?: 0.0) <= -2.0 }) streak = 0
    }

    /** Called when nothing is left thirsty after she acts. Returns true if the streak grew today. */
    fun noteCleared(): Boolean {
        val d = today()
        if (streakDay == d) return false
        streakDay = d; streak += 1
        return true
    }

    /** How the garden is laid out: "cards" or "list". */
    var view: String
        get() = prefs.getString("view", "cards") ?: "cards"
        set(v) = prefs.edit().putString("view", v).apply()
    /** Order within each group: "due", "name" or "place". */
    var sort: String
        get() = prefs.getString("sort", "due") ?: "due"
        set(v) = prefs.edit().putString("sort", v).apply()
    /** "phone" follows the phone's light or dark setting; "day" or "night" fixes it. */
    var theme: String
        get() = prefs.getString("theme", "phone") ?: "phone"
        set(v) = prefs.edit().putString("theme", v).apply()
    /** Groups folded away. Content starts folded: those plants need nothing today. */
    var folded: Set<String>
        get() = prefs.getStringSet("folded", setOf(Thirst.CONTENT.name)) ?: emptySet()
        set(v) = prefs.edit().putStringSet("folded", v.toSet()).apply()

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

    /** Removes a plant, keeping it among the recently removed so it can come back. */
    fun remove(id: String) {
        val gone = get(id) ?: return
        val kept = (listOf(gone) + removed().filter { it.id != id }).take(10)
        prefs.edit().putString("removed", JSONArray().apply { kept.forEach { put(it.toJson()) } }.toString()).apply()
        synchronized(this) { plants.remove(id); save() }
        changed()
        Sync.delete(app, id)
        Reminders.cancelPlant(app, id)
    }

    /** The last ten plants removed, newest first. */
    fun removed(): List<Plant> = runCatching {
        val a = JSONArray(prefs.getString("removed", "[]"))
        (0 until a.length()).map { Plant.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** Brings a removed plant back into the garden, as it was. */
    fun restore(id: String): Plant? {
        val p = removed().firstOrNull { it.id == id } ?: return null
        prefs.edit().putString("removed", JSONArray().apply { removed().filter { it.id != id }.forEach { put(it.toJson()) } }.toString()).apply()
        put(p)
        return p
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
