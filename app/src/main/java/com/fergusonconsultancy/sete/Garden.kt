package com.fergusonconsultancy.sete

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.roundToInt

const val DAY = 86_400_000L

/** How a kind of plant drinks. Advice is general guidance for UK homes, not a verified source. */
data class Kind(
    val key: String,
    val label: String,
    val latin: String,
    /** Days between drinks in summer. */
    val base: Int,
    /** Extra stretch through winter (Nov to Feb) for plants that rest. */
    val winter: Float,
    /** true: toxic to cats and dogs; false: listed as non-toxic; null: unknown. */
    val toxic: Boolean?,
    val how: String,
    val cue: String,
    val pets: String,
)

object Kinds {
    val all = listOf(
        Kind("strelitzia", "Bird of paradise", "Strelitzia", 7, 1f, true,
            "Water until it runs from the drainage holes, then empty the saucer. Let the top 5 cm dry out first.",
            "Push a finger in to the second knuckle. Dry there means water.",
            "Mildly toxic to cats and dogs; chewing causes stomach upset."),
        Kind("citrus", "Citrus tree", "Citrus", 6, 1f, true,
            "Keep the soil evenly moist, never soggy. Water when the top 2 to 3 cm feel dry. Rainwater is kindest.",
            "Curling leaves or dropped fruit mean it went too dry.",
            "Leaves and peel are toxic to cats and dogs."),
        Kind("schlumbergera", "Christmas cactus", "Schlumbergera", 9, 1f, false,
            "Water when the top 2 cm are dry. Once buds form, keep it in the same spot and keep watering steady.",
            "Limp, wrinkled pads mean thirst. Dropped buds usually mean it was moved or dried out.",
            "Listed as non-toxic to cats and dogs."),
        Kind("chlorophytum", "Spider plant", "Chlorophytum comosum", 6, 1f, false,
            "Water when the top half of the soil is dry. Rain or filtered water helps the brown tips.",
            "Pale, limp leaves mean thirst. Brown tips usually mean tap-water minerals or dry air.",
            "Listed as non-toxic, though cats love to chew it."),
        Kind("aechmea", "Bromeliad", "Aechmea", 7, 1f, false,
            "Keep a little water in the central cup and refresh it every week or two. Moisten the soil only lightly.",
            "An empty cup is the signal. Soggy soil rots the base.",
            "Listed as non-toxic to cats and dogs."),
        Kind("phalaenopsis", "Moth orchid", "Phalaenopsis", 7, 1f, false,
            "Soak the inner pot for 10 minutes, then let it drain fully. Never leave water in the crown or the outer pot.",
            "Silver roots mean water now. Green roots mean wait.",
            "Listed as non-toxic to cats and dogs."),
        Kind("pilea", "Chinese money plant", "Pilea peperomioides", 7, 1f, false,
            "Water when the top 3 cm are dry, then drain well.",
            "Leaves drooping like a closing umbrella means thirst.",
            "Listed as non-toxic to cats and dogs."),
        Kind("kalanchoe", "Kalanchoe", "Kalanchoe (best guess from the photo)", 12, 1.3f, true,
            "Water deeply, then let the soil dry almost completely. Much less in winter.",
            "Thin, wrinkling leaves mean thirst. Yellow, soft leaves mean too much water.",
            "Toxic to cats and dogs."),
        Kind("spathiphyllum", "Peace lily", "Spathiphyllum", 6, 1f, true,
            "Water when the top 2 to 3 cm are dry. Filtered or rainwater helps the brown tips.",
            "It droops dramatically when thirsty and perks up within hours of a drink.",
            "Irritant to cats and dogs (mouth and stomach). Not a true lily."),
        Kind("dendrobium", "Cane orchid", "Dendrobium (best guess from the photo)", 10, 1.6f, false,
            "Water freely while growing. Through winter keep it cool, bright and much drier: a light drink every couple of weeks helps it flower.",
            "Shrivelled canes mean too dry, even for winter.",
            "Listed as non-toxic to cats and dogs."),
        Kind("dieffenbachia", "Dumb cane", "Dieffenbachia", 7, 1f, true,
            "Water when the top 3 cm are dry. Keep it off cold glass.",
            "Drooping means thirst. Yellow lower leaves usually mean too much water or cold.",
            "Toxic to cats and dogs; the sap irritates skin too."),
        Kind("monstera", "Swiss cheese plant", "Monstera deliciosa", 8, 1f, true,
            "Water when the top 5 cm are dry, then drain.",
            "Curling leaves mean thirst. Yellowing with dark spots means too wet.",
            "Toxic to cats and dogs."),
        Kind("dracaena", "Dragon tree", "Dracaena marginata", 12, 1f, true,
            "Water when the top half of the soil is dry. Rainwater or filtered, as it dislikes fluoride.",
            "Brown tips come from minerals or dry air. A soft, yellow base means too wet.",
            "Toxic to cats and dogs."),
        Kind("aloe", "Aloe", "Aloe vera", 18, 1.4f, true,
            "Water deeply, then let it dry right through. Every 3 to 4 weeks in winter.",
            "Thin, curling leaves mean thirst. Leaves flopping flat mean it wants more light.",
            "Toxic to cats and dogs."),
        Kind("pothos", "Pothos", "Epipremnum aureum", 8, 1f, true,
            "Water when the top half of the soil is dry.",
            "Leaves go soft and droop when thirsty; yellow leaves mean too wet.",
            "Toxic to cats and dogs."),
        Kind("other", "Houseplant", "", 7, 1f, null,
            "Water when the top few centimetres are dry, then drain.",
            "Check the soil with a finger before every drink.",
            "Check before letting pets near it."),
    )
    private val byKey = all.associateBy { it.key }
    operator fun get(key: String?) = byKey[key] ?: byKey.getValue("other")
}

data class LogEntry(val t: Long, val by: String, val k: String)

/** One reading of a photo: how much of the plant looks green rather than yellow. */
data class Read(val t: Long, val green: Double)

/** How much light a spot gets. Thresholds are general guidance for houseplants, not a standard. */
enum class Light(val label: String, val factor: Double, val detail: String) {
    LOW("Low", 1.3, "Away from windows. Soil stays wet much longer."),
    MEDIUM("Medium", 1.0, "Bright room, out of the window's direct line."),
    BRIGHT("Bright", 0.85, "On or by a window. Soil dries faster."),
    SUN("Direct sun", 0.75, "Sun falls on the plant. It dries fastest.");

    companion object {
        fun fromLux(lux: Double) = when { lux < 800 -> LOW; lux < 2500 -> MEDIUM; lux < 10000 -> BRIGHT; else -> SUN }
    }
}

/** What the pot is made of changes how fast the soil dries. */
enum class Pot(val label: String, val factor: Double) {
    PLASTIC("Plastic", 1.0), GLAZED("Glazed", 1.0), TERRACOTTA("Terracotta", 0.85), CONCRETE("Concrete", 0.92), METAL("Metal cover", 1.05)
}

data class Plant(
    val id: String,
    val name: String,
    val kind: String,
    val spot: String = "",
    /** A bundled photo key, or "data:" plus base64 JPEG for a photo taken in the app. */
    val photo: String = "",
    val base: Int = Kinds[kind].base,
    val learn: Double = 1.0,
    val last: Long? = null,
    val snooze: Long? = null,
    val log: List<LogEntry> = emptyList(),
    val seen: String = "",
    val order: Int = 999,
    val updated: Long = 0L,
    /** The spot: light level (null: not known), a light reading if measured, a radiator nearby, the pot. */
    val light: Light? = null,
    val lux: Double? = null,
    val luxAt: Long? = null,
    /** Where the light level came from: "measured", "photo" or "set". */
    val lightFrom: String = "",
    val heat: Boolean = false,
    val pot: Pot? = null,
    val photoAt: Long? = null,
    val reads: List<Read> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("kind", kind); put("spot", spot); put("photo", photo)
        put("base", base); put("learn", learn); put("last", last ?: JSONObject.NULL); put("snooze", snooze ?: JSONObject.NULL)
        put("seen", seen); put("order", order); put("updated", updated)
        put("log", JSONArray().apply { log.forEach { put(JSONObject().put("t", it.t).put("by", it.by).put("k", it.k)) } })
        put("light", light?.name ?: JSONObject.NULL); put("lux", lux ?: JSONObject.NULL); put("luxAt", luxAt ?: JSONObject.NULL)
        put("lightFrom", lightFrom); put("heat", heat); put("pot", pot?.name ?: JSONObject.NULL); put("photoAt", photoAt ?: JSONObject.NULL)
        put("reads", JSONArray().apply { reads.forEach { put(JSONObject().put("t", it.t).put("green", it.green)) } })
    }

    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name, "kind" to kind, "spot" to spot, "photo" to photo, "base" to base, "learn" to learn,
        "last" to last, "snooze" to snooze, "seen" to seen, "order" to order, "updated" to updated,
        "log" to log.map { mapOf("t" to it.t, "by" to it.by, "k" to it.k) },
        "light" to light?.name, "lux" to lux, "luxAt" to luxAt, "lightFrom" to lightFrom, "heat" to heat, "pot" to pot?.name,
        "photoAt" to photoAt, "reads" to reads.map { mapOf("t" to it.t, "green" to it.green) },
    )

    companion object {
        fun fromJson(o: JSONObject) = Plant(
            id = o.getString("id"), name = o.optString("name", "Plant"), kind = o.optString("kind", "other"),
            spot = o.optString("spot"), photo = o.optString("photo"), base = o.optInt("base", Kinds[o.optString("kind")].base),
            learn = o.optDouble("learn", 1.0),
            last = if (o.isNull("last")) null else o.optLong("last"), snooze = if (o.isNull("snooze")) null else o.optLong("snooze"),
            seen = o.optString("seen"), order = o.optInt("order", 999), updated = o.optLong("updated", 0L),
            log = o.optJSONArray("log")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { LogEntry(it.getLong("t"), it.optString("by"), it.optString("k", "w")) } } } ?: emptyList(),
            light = if (o.isNull("light")) null else runCatching { Light.valueOf(o.getString("light")) }.getOrNull(),
            lux = if (o.isNull("lux")) null else o.optDouble("lux"), luxAt = if (o.isNull("luxAt")) null else o.optLong("luxAt"),
            lightFrom = o.optString("lightFrom"), heat = o.optBoolean("heat", false),
            pot = if (o.isNull("pot")) null else runCatching { Pot.valueOf(o.getString("pot")) }.getOrNull(),
            photoAt = if (o.isNull("photoAt")) null else o.optLong("photoAt"),
            reads = o.optJSONArray("reads")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { Read(it.getLong("t"), it.getDouble("green")) } } } ?: emptyList(),
        )

        private fun num(v: Any?): Number? = v as? Number

        @Suppress("UNCHECKED_CAST")
        fun fromMap(id: String, m: Map<String, Any?>) = Plant(
            id = id, name = m["name"] as? String ?: "Plant", kind = m["kind"] as? String ?: "other",
            spot = m["spot"] as? String ?: "", photo = m["photo"] as? String ?: "",
            base = num(m["base"])?.toInt() ?: Kinds[m["kind"] as? String].base, learn = num(m["learn"])?.toDouble() ?: 1.0,
            last = num(m["last"])?.toLong(), snooze = num(m["snooze"])?.toLong(),
            seen = m["seen"] as? String ?: "", order = num(m["order"])?.toInt() ?: 999, updated = num(m["updated"])?.toLong() ?: 0L,
            log = (m["log"] as? List<Map<String, Any?>>)?.map { LogEntry(num(it["t"])?.toLong() ?: 0L, it["by"] as? String ?: "", it["k"] as? String ?: "w") } ?: emptyList(),
            light = (m["light"] as? String)?.let { v -> runCatching { Light.valueOf(v) }.getOrNull() },
            lux = num(m["lux"])?.toDouble(), luxAt = num(m["luxAt"])?.toLong(), lightFrom = m["lightFrom"] as? String ?: "",
            heat = m["heat"] as? Boolean ?: false, pot = (m["pot"] as? String)?.let { v -> runCatching { Pot.valueOf(v) }.getOrNull() },
            photoAt = num(m["photoAt"])?.toLong(),
            reads = (m["reads"] as? List<Map<String, Any?>>)?.map { Read(num(it["t"])?.toLong() ?: 0L, num(it["green"])?.toDouble() ?: 0.0) } ?: emptyList(),
        )
    }
}

enum class Thirst { THIRSTY, NEW, SOON, CONTENT }

/** Where a plant stands right now: the level is what is left of its last drink, 0 to 1. */
data class Standing(val thirst: Thirst, val days: Double, val remaining: Double?, val level: Float, val due: Long?)

object Rhythm {
    /** UK light and heating: plants drink less as the days shorten. */
    fun season(month: Int): Double = doubleArrayOf(1.5, 1.5, 1.25, 1.1, 1.0, 1.0, 1.0, 1.0, 1.1, 1.25, 1.5, 1.5)[month]

    fun winterExtra(kind: String, month: Int): Double = if (month >= 10 || month <= 1) Kinds[kind].winter.toDouble() else 1.0

    fun month(now: Long): Int = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.MONTH)

    /** Radiators are on from October to April. */
    fun heating(month: Int) = month >= 9 || month <= 3

    /** What the spot does to the rhythm, each part named so the app can explain it. */
    fun spotParts(p: Plant, month: Int): List<Pair<String, Double>> {
        val out = mutableListOf<Pair<String, Double>>()
        p.light?.let { if (it.factor != 1.0) out += (if (it == Light.SUN) "direct sun" else "${it.label.lowercase()} light") to it.factor }
        if (p.heat && heating(month)) out += "the radiator" to 0.85
        p.pot?.let { if (it.factor != 1.0) out += (if (it == Pot.METAL) "its metal cover" else "its ${it.label.lowercase()} pot") to it.factor }
        return out
    }

    fun days(p: Plant, now: Long = System.currentTimeMillis()): Double {
        val m = month(now)
        val spot = spotParts(p, m).fold(1.0) { a, b -> a * b.second }
        return (p.base * season(m) * winterExtra(p.kind, m) * spot * p.learn).coerceAtLeast(1.5)
    }

    fun standing(p: Plant, now: Long = System.currentTimeMillis()): Standing {
        val eff = days(p, now)
        val last = p.last
        if (last == null) {
            val s = p.snooze
            if (s != null && s > now) {
                val r = (s - now).toDouble() / DAY
                return Standing(if (r <= 2) Thirst.SOON else Thirst.CONTENT, eff, r, (r / eff).coerceIn(0.0, 1.0).toFloat(), s)
            }
            return Standing(Thirst.NEW, eff, null, 0f, null)
        }
        var due = last + (eff * DAY).toLong()
        p.snooze?.let { if (it > due) due = it }
        val rem = (due - now).toDouble() / DAY
        val t = when { rem <= 0.5 -> Thirst.THIRSTY; rem <= 2.0 -> Thirst.SOON; else -> Thirst.CONTENT }
        return Standing(t, eff, rem, (rem / eff).coerceIn(0.0, 1.0).toFloat(), due)
    }

    fun dueText(s: Standing): String {
        val r = s.remaining ?: return "Not logged yet"
        val d = r.roundToInt()
        return when {
            r <= -0.5 -> "${-d} day${if (-d == 1) "" else "s"} overdue"
            r <= 0.5 -> "Due today"
            d == 1 -> "Due tomorrow"
            else -> "Due in $d days"
        }
    }

    fun ago(t: Long, now: Long = System.currentTimeMillis()): String {
        val d = ((now - t) / DAY).toInt()
        return when { d <= 0 -> "today"; d == 1 -> "yesterday"; else -> "$d days ago" }
    }
}

/** The garden as photographed on 25 Oct 2026. Notes describe what the photo showed. */
object Seed {
    fun plants(now: Long): List<Plant> = listOf(
        Triple("strelitzia", "Bird of Paradise", "strelitzia") to ("Floor, by the copper radiator" to "Sits right against the radiator. Dry heat browns the leaf edges; a hand's width of space helps."),
        Triple("citrus", "Citrus Tree", "citrus") to ("Dark counter by the window" to "Fruiting, with a few paler leaves. Keep moisture steady while the fruit ripens."),
        Triple("xmas_cactus", "Christmas Cactus", "schlumbergera") to ("Wooden bench by the blinds" to "Healthy and full. Autumn is bud season: long dark nights, and no moving it once buds show."),
        Triple("spider_sill", "Little Spider Plant", "chlorophytum") to ("Corner sill, hedgehog pot" to "Young and clean, no brown tips yet."),
        Triple("bromeliad", "Bromeliad", "aechmea") to ("Garden window sill" to "Single rosette, no bloom yet. Water goes in the central cup more than the soil."),
        Triple("orchid_bloom", "Orchid in Bloom", "phalaenopsis") to ("Garden window sill" to "In full flower in a clear inner pot, so the root-colour check is easy."),
        Triple("pilea", "Pilea", "pilea") to ("Corner sill" to "Several babies at the base. They can be potted on once about 5 cm tall."),
        Triple("kalanchoe_terracotta", "Kalanchoe, Terracotta", "kalanchoe") to ("Frosted window sill" to "Long stems lying across the pot with aerial roots, reaching for light. Give it the brightest sill."),
        Triple("peace_lily", "Peace Lily", "spathiphyllum") to ("Grey shelf by the brick wall" to "Brown tips and one dead leaf. Snip them off; usually dry air or uneven watering."),
        Triple("cane_orchid", "Cane Orchid", "dendrobium") to ("Window sill" to "Winter rest is coming: cooler and drier helps it flower in spring."),
        Triple("orchid_sloth", "Orchid with the Sloth", "phalaenopsis") to ("Sill by the copper stand" to "Resting after flowering, with dry old spikes. Leaves look firm."),
        Triple("kalanchoe_cuttings", "Kalanchoe Cuttings", "kalanchoe") to ("Bench by the window, grey pot" to "Several dried leaves. Cuttings rot easily: water lightly and keep them bright."),
        Triple("dieffenbachia", "Dieffenbachia", "dieffenbachia") to ("Window sill, by the geode" to "Two leaves browning from the tip. Likely too wet or touching cold glass. Feel the soil before every drink."),
        Triple("monstera", "Monstera", "monstera") to ("Bay window" to "One lower leaf fully yellow, one spotted. Remove them and let the soil dry between drinks."),
        Triple("orchid_dotted", "Orchid, Dotted Pot", "phalaenopsis") to ("Window sill" to "Plenty of healthy aerial roots. That's normal; don't cut them."),
        Triple("orchid_roots", "Orchid, Wild Roots", "phalaenopsis") to ("Bay window" to "Lively aerial roots with green growing tips. A happy plant."),
        Triple("kalanchoe_fluted", "Kalanchoe, Fluted Pot", "kalanchoe") to ("Bay window" to "Bright, leggy growth reaching for light."),
        Triple("dracaena", "Dragon Tree", "dracaena") to ("Floor, by the copper radiator" to "Next to the radiator too, in the copper bucket. Make sure the inner pot drains."),
        Triple("aloe", "Aloe", "aloe") to ("Bay window" to "Leaves sprawling flat: it wants more light. Very little water through winter."),
        Triple("spider_floor", "Big Spider Plant", "chlorophytum") to ("Floor, by the wall" to "Big and healthy with brown tips. Try rain or filtered water."),
    ).mapIndexed { i, (a, b) ->
        val (light, heat, pot) = spots.getValue(a.first)
        Plant(id = a.first, name = a.second, kind = a.third, spot = b.first, photo = a.first, seen = b.second, order = i + 1, updated = now,
            light = light, lightFrom = "photo", heat = heat, pot = pot)
    }

    /** Light, radiator and pot as they looked in the 25 Oct photos: a starting point to measure and correct. */
    val spots: Map<String, Triple<Light, Boolean, Pot>> = mapOf(
        "strelitzia" to Triple(Light.BRIGHT, true, Pot.PLASTIC),
        "citrus" to Triple(Light.BRIGHT, false, Pot.TERRACOTTA),
        "xmas_cactus" to Triple(Light.BRIGHT, false, Pot.TERRACOTTA),
        "spider_sill" to Triple(Light.BRIGHT, false, Pot.GLAZED),
        "bromeliad" to Triple(Light.BRIGHT, false, Pot.PLASTIC),
        "orchid_bloom" to Triple(Light.BRIGHT, false, Pot.PLASTIC),
        "pilea" to Triple(Light.BRIGHT, false, Pot.GLAZED),
        "kalanchoe_terracotta" to Triple(Light.MEDIUM, false, Pot.TERRACOTTA),
        "peace_lily" to Triple(Light.LOW, false, Pot.GLAZED),
        "cane_orchid" to Triple(Light.BRIGHT, false, Pot.GLAZED),
        "orchid_sloth" to Triple(Light.BRIGHT, false, Pot.TERRACOTTA),
        "kalanchoe_cuttings" to Triple(Light.MEDIUM, false, Pot.CONCRETE),
        "dieffenbachia" to Triple(Light.BRIGHT, false, Pot.PLASTIC),
        "monstera" to Triple(Light.BRIGHT, false, Pot.GLAZED),
        "orchid_dotted" to Triple(Light.BRIGHT, false, Pot.GLAZED),
        "orchid_roots" to Triple(Light.BRIGHT, false, Pot.GLAZED),
        "kalanchoe_fluted" to Triple(Light.BRIGHT, false, Pot.GLAZED),
        "dracaena" to Triple(Light.MEDIUM, true, Pot.METAL),
        "aloe" to Triple(Light.BRIGHT, false, Pot.TERRACOTTA),
        "spider_floor" to Triple(Light.MEDIUM, false, Pot.GLAZED),
    )
}
