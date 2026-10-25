package com.fergusonconsultancy.sete

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import java.io.ByteArrayOutputStream
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var screen: Screen
    private lateinit var verdict: TextView
    private lateinit var tallies: Grid
    private lateinit var voice: TextView
    private lateinit var roundButton: TextView
    private var round: RoundView? = null
    private lateinit var fab: TextView
    private lateinit var remindersPill: Pill
    private val sections = LinkedHashMap<Thirst, Pair<LinearLayout, Grid>>()
    private val cards = HashMap<String, PlantCard>()
    private val main = Handler(Looper.getMainLooper())
    private var holdUntil = 0L
    private var crashed = false
    private var barsTop = 0
    private var barsBottom = 0

    private var sheet: FrameLayout? = null
    private var sheetPanel: ScrollView? = null
    private var sheetRefresh: (() -> Unit)? = null
    private var toastView: LinearLayout? = null
    private val hideToast = Runnable { toastView?.animate()?.alpha(0f)?.translationY(px(20).toFloat())?.setDuration(220)?.withEndAction { toastView?.visibility = View.GONE }?.start() }

    private var pendingShot: Shot? = null
    private var photoPicked: ((Shot) -> Unit)? = null
    /** The plant a new photo is for, kept across the trip to the camera. */
    private var shotFor: String? = null
    private var onSheetClosed: (() -> Unit)? = null

    private val onChange: () -> Unit = { render(); sheetRefresh?.invoke() }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (Crash.show(this)) { crashed = true; return }
        // The home's palette by day, its evening palette by night, following the phone
        Kit.dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        Store.init(this)
        Sound.init(this)
        Reminders.channel(this)
        Reminders.schedule(this)


        screen = Screen(this, "Sete", dayLabel())
        verdict = text("", 19f, Kit.INK, 300).apply { setLineSpacing(0f, 1.3f) }
        screen.add(verdict, px(14))
        // The thirstiest plant speaks for the garden
        voice = text("", 15.5f, Kit.CHAMPAGNE, 400).apply { setLineSpacing(0f, 1.3f) }
        screen.add(voice, px(10))
        roundButton = pour("") { startRound() }
        screen.add(roundButton, px(18))
        // Equal tiles, two to a row on a phone, so every chip has room for its line of detail
        tallies = Grid(this, minColumnDp = 150, gapDp = 8)
        screen.add(tallies, px(18))

        val titles = mapOf(
            Thirst.THIRSTY to ("Thirsty" to "Due now. One tap logs it."),
            Thirst.NEW to ("Start here" to "Not logged yet. Feel the soil, then tap Watered or Still damp."),
            Thirst.SOON to ("Next few days" to "Due within two days."),
            Thirst.CONTENT to ("Content" to "The water line is what's left of the last drink."),
        )
        titles.forEach { (t, words) ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(text(words.first, 27f, Kit.INK, displayFace = true))
            box.addView(text(words.second, 13f, Kit.MUTED).apply { tag = SECONDARY }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
            box.addView(View(this).apply { setBackgroundColor(Kit.HAIRLINE) }, LinearLayout.LayoutParams(-1, px(1)).apply { topMargin = px(10); bottomMargin = px(14) })
            val grid = Grid(this)
            box.addView(grid, LinearLayout.LayoutParams(-1, -2))
            sections[t] = box to grid
            screen.add(box, px(34))
        }
        screen.add(text("Watering rhythms are general guidance for UK homes, stretched automatically through autumn and winter. " +
            "Sete learns from Still damp and Bone dry. Your finger in the soil always beats the calendar.", 12.5f, Kit.MUTED).apply { tag = SECONDARY }, px(40))

        root = FrameLayout(this).apply { setBackgroundColor(Kit.GROUND) }
        root.addView(screen.root, FrameLayout.LayoutParams(-1, -1))

        // Reminders state, bottom left: shows what will happen and opens the settings
        remindersPill = Pill(this).apply {
            isClickable = true
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); openSettings() }
        }
        root.addView(remindersPill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        fab = text("+  Plant", 15.5f, Kit.INK, 500).apply {
            setPadding(px(20), px(13), px(22), px(13))
            background = rounded(Kit.RAISED, px(26).toFloat(), Kit.HAIRLINE, px(1))
            elevation = px(8).toFloat()
            isClickable = true
            // A new plant starts with its photo: tap for the camera, hold to add without one
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); camera(11) }
            setOnLongClickListener { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); openEdit(null); true }
            contentDescription = "Add a plant with a photo"
        }
        root.addView(fab, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))

        root.setOnApplyWindowInsetsListener { _, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            barsTop = bars.top; barsBottom = bars.bottom
            val side = px(20)
            screen.column.setPadding(side + bars.left, px(26) + bars.top, side + bars.right, px(120) + bars.bottom)
            (fab.layoutParams as FrameLayout.LayoutParams).setMargins(0, 0, px(18) + bars.right, px(18) + bars.bottom)
            (remindersPill.layoutParams as FrameLayout.LayoutParams).setMargins(px(18) + bars.left, 0, 0, px(22) + bars.bottom)
            fab.requestLayout(); remindersPill.requestLayout()
            sheetPanel?.let { p ->
                (p.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + px(40)
                p.getChildAt(0)?.let { c -> c.setPadding(c.paddingLeft, c.paddingTop, c.paddingRight, max(bars.bottom, ime.bottom) + px(28)) }
                p.requestLayout()
            }
            ins
        }
        setContentView(root)
        // The window's decor exists only once content is set
        window.setDecorFitsSystemWindows(false)
        val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (Kit.dark) 0 else bars, bars)

        render()
        if (!Store.onboarded) main.postDelayed({ if (!Store.onboarded) openWelcome() }, 350)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        if (crashed) return
        Store.listen(onChange)
        Sync.start(this)
        Store.checkNeglect()
        render()
        // Thirsty plants wilt a little as you arrive
        main.postDelayed({ cards.values.filter { it.thirsty }.forEachIndexed { i, c -> c.postDelayed({ c.droop() }, i * 90L) } }, 600)
    }

    override fun onStop() {
        if (crashed) { super.onStop(); return }
        Store.unlisten(onChange)
        Sync.stop()
        super.onStop()
    }

    /** Turning or unfolding the phone: the garden's column follows the new width. */
    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        if (crashed || !::screen.isInitialized) return
        screen.column.layoutParams = (screen.column.layoutParams as FrameLayout.LayoutParams).apply {
            width = minOf(resources.displayMetrics.widthPixels, px(620))
        }
        root.requestApplyInsets()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handle(i)
    }

    private fun handle(i: Intent?) {
        if (i?.getBooleanExtra("round", false) == true) {
            i.removeExtra("round")
            main.postDelayed({ startRound() }, 250)
            return
        }
        val id = i?.getStringExtra("plant") ?: return
        i.removeExtra("plant")
        main.postDelayed({ if (Store.get(id) != null) openPlant(id) }, 200)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            round != null -> round?.close()
            sheet != null -> closeSheet()
            else -> super.onBackPressed()
        }
    }

    private fun dayLabel(): String {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val day = cal.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, java.util.Locale.UK) ?: ""
        return "$day " + when { h < 12 -> "morning"; h < 18 -> "afternoon"; else -> "evening" }
    }

    // ---- The garden ---------------------------------------------------------------------------

    private fun render() {
        if (!::screen.isInitialized) return
        val now = System.currentTimeMillis()
        val plants = Store.all()
        val standing = plants.associate { it.id to Rhythm.standing(it, now) }

        // Just after a tap, let the water rise in place before the card moves to its new group
        if (now < holdUntil) {
            plants.forEach { p -> cards[p.id]?.bind(p, standing.getValue(p.id), animate = true) }
            main.removeCallbacks(renderLater); main.postDelayed(renderLater, holdUntil - now + 20)
            return
        }

        val groups = plants.groupBy { standing.getValue(it.id).thirst }
        sections.forEach { (t, pair) ->
            val (box, grid) = pair
            val list = (groups[t] ?: emptyList()).let { l ->
                if (t == Thirst.NEW) l.sortedBy { it.order } else l.sortedBy { standing.getValue(it.id).remaining ?: 0.0 }
            }
            box.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            val want = list.map { it.id }
            val have = (0 until grid.childCount).map { (grid.getChildAt(it) as PlantCard).plantId }
            list.forEach { p ->
                val existed = cards.containsKey(p.id)
                val card = cards.getOrPut(p.id) { PlantCard(this, p.id, ::openPlant) { id -> act(id, Store.Act.WATERED) } }
                card.bind(p, standing.getValue(p.id), animate = existed)
            }
            if (want != have) {
                grid.removeAllViews()
                want.forEach { id -> cards[id]?.let { c -> (c.parent as? android.view.ViewGroup)?.removeView(c); grid.addView(c) } }
            }
        }
        val gone = cards.keys - plants.map { it.id }.toSet()
        gone.forEach { id -> cards.remove(id)?.let { c -> (c.parent as? android.view.ViewGroup)?.removeView(c) } }

        val n = { t: Thirst -> groups[t]?.size ?: 0 }
        val thirsty = n(Thirst.THIRSTY); val soon = n(Thirst.SOON); val fresh = n(Thirst.NEW)
        // The header pill names the most urgent state, so it never contradicts the line beneath it
        val (pill, pillColour) = when {
            thirsty > 0 -> "$thirsty thirsty" to Kit.THIRST
            soon > 0 -> "$soon due soon" to Kit.CHAMPAGNE
            fresh > 0 -> "$fresh to check" to Kit.MUTED
            else -> "All content" to Kit.GOOD
        }
        screen.status.set(pill, pillColour)
        val hello = greeting()
        verdict.text = hello + when {
            plants.isEmpty() -> "No plants yet. Add the first one."
            thirsty > 0 -> "${if (thirsty == 1) "One plant is" else "$thirsty plants are"} thirsty. " +
                (if (soon > 0) "$soon more in the next couple of days." else "Everything else is fine.")
            fresh > 0 -> "$fresh plants are waiting for their first check."
            soon > 0 -> "Nothing due today. $soon coming up soon."
            else -> "Everyone has had a drink. Nothing to do today."
        }
        val loudest = (groups[Thirst.THIRSTY] ?: emptyList()).minByOrNull { standing.getValue(it.id).remaining ?: 0.0 }
        voice.text = loudest?.let { "${it.name}: ${Personality.thirsty(it)}" } ?: ""
        voice.visibility = if (loudest == null) View.GONE else View.VISIBLE
        val due = thirsty + fresh
        roundButton.text = when { thirsty > 0 -> "Start the watering round  ·  $due"; fresh > 0 -> "Start the first round  ·  $fresh"; else -> "" }
        roundButton.visibility = if (due > 0) View.VISIBLE else View.GONE
        tallies.removeAllViews()
        // Each chip says what is behind its number, and takes you there
        fun firstOf(t: Thirst) = (groups[t] ?: emptyList()).minByOrNull { standing.getValue(it.id).remaining ?: 0.0 }
        fun whenText(p: Plant) = Rhythm.dueText(standing.getValue(p.id)).removePrefix("Due ").let { if (it == "today") "today" else it }
        val detail = mapOf(
            Thirst.THIRSTY to (firstOf(Thirst.THIRSTY)?.let { "most overdue: ${it.name}" } ?: "nobody right now"),
            Thirst.SOON to (firstOf(Thirst.SOON)?.let { "next: ${it.name}, ${whenText(it)}" } ?: "nothing in the next two days"),
            Thirst.CONTENT to (firstOf(Thirst.CONTENT)?.let { p ->
                standing.getValue(p.id).due?.let { "next due " + "%tA".format(Calendar.getInstance().apply { timeInMillis = it }) } ?: "all watered"
            } ?: "none yet"),
            Thirst.NEW to "a first feel of the soil",
        )
        listOf(Thirst.THIRSTY to "Thirsty", Thirst.SOON to "Soon", Thirst.CONTENT to "Content", Thirst.NEW to "Not logged").forEach { (t, w) ->
            if (t != Thirst.NEW || n(t) > 0) tallies.addView(tally(n(t), w, thirstColor(t), detail.getValue(t)) { goTo(t, detail.getValue(t)) })
        }
        if (Store.streak > 0) tallies.addView(tally(Store.streak, if (Store.streak == 1) "round" else "rounds in a row", Kit.WATER, "nobody left thirsty") {
            toast("A round counts when nobody is left thirsty. Two days overdue resets it.")
        })
        paintRemindersPill()
    }

    private val renderLater = Runnable { holdUntil = 0L; render() }

    /** Scrolls to a group and lifts its title, or says why there is nothing there. */
    private fun goTo(t: Thirst, @Suppress("UNUSED_PARAMETER") detail: String) {
        val box = sections[t]?.first ?: return
        if (box.visibility != View.VISIBLE) {
            toast(if (t == Thirst.THIRSTY) "Nobody's thirsty right now." else "Nothing in this group right now.")
            return
        }
        screen.root.smoothScrollTo(0, (box.top - px(16)).coerceAtLeast(0))
        val title = box.getChildAt(0)
        title.animate().cancel()
        title.alpha = 0.25f
        title.animate().alpha(1f).setStartDelay(260).setDuration(520).start()
        box.getChildAt(1)?.let { it.alpha = 1f }
    }

    private fun notificationsOn() = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun clock(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    private fun paintRemindersPill() {
        if (notificationsOn()) remindersPill.set("Reminders  ${clock(Store.morning)}", Kit.WATER)
        else remindersPill.set("Reminders off", Kit.THIRST)
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val part = when { h < 5 -> "Up late"; h < 12 -> "Morning"; h < 18 -> "Afternoon"; else -> "Evening" }
        val first = Store.me.trim().split(" ").firstOrNull().orEmpty()
        return if (first.isNotBlank()) "$part, $first. " else ""
    }

    private fun thirstyCount() = System.currentTimeMillis().let { now -> Store.all().count { Rhythm.standing(it, now).thirst == Thirst.THIRSTY } }

    private fun act(id: String, a: Store.Act) {
        val plant = Store.get(id) ?: return
        val before = thirstyCount()
        holdUntil = System.currentTimeMillis() + 1500
        val prev = Store.act(id, a) ?: return
        if (a == Store.Act.DAMP) Sound.play(Sound.Cue.TAP) else Sound.watered()
        val line = if (a == Store.Act.DAMP) Personality.damp(plant) else Personality.thanks(plant)
        toast("${plant.name}: $line") { Store.put(prev) }
        // The last thirsty plant done: that's a round
        if (before > 0 && thirstyCount() == 0 && Store.noteCleared()) {
            main.postDelayed({
                Sound.play(Sound.Cue.CHIME, vary = false)
                toast(if (Store.streak == 1) "Nobody's thirsty. First round done." else "Nobody's thirsty. ${Store.streak} rounds in a row.")
            }, 1700)
        }
    }

    private fun startRound() {
        if (round != null) return
        if (sheet != null) closeSheet()
        val now = System.currentTimeMillis()
        val all = Store.all().map { it to Rhythm.standing(it, now) }
        val queue = all.filter { it.second.thirst == Thirst.THIRSTY }.sortedBy { it.second.remaining ?: 0.0 }.map { it.first.id } +
            all.filter { it.second.thirst == Thirst.NEW }.sortedBy { it.first.order }.map { it.first.id }
        val v = RoundView(this, queue, barsTop, barsBottom) {
            round?.let { root.removeView(it) }
            round = null
            render()
        }
        round = v
        // Above the floating buttons, which carry their own elevation
        v.elevation = px(24).toFloat()
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        v.alpha = 0f; v.translationY = px(30).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(Kit.ease).start()
    }

    // ---- Toast -------------------------------------------------------------------------------

    private fun toast(msg: String, undo: (() -> Unit)? = null) {
        toastView?.let { root.removeView(it) }
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18), px(10), px(8), px(10))
            background = rounded(Kit.RAISED, px(26).toFloat(), Kit.HAIRLINE, px(1))
            elevation = px(30).toFloat()
            addView(text(msg, 14f, Kit.INK, 400), LinearLayout.LayoutParams(0, -2, 1f))
            if (undo != null) addView(text("Undo", 14f, Kit.CHAMPAGNE, 500).apply {
                setPadding(px(14), px(8), px(14), px(8))
                isClickable = true
                setOnClickListener { undo(); main.removeCallbacks(hideToast); hideToast.run() }
            })
        }
        root.addView(t, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(px(16), 0, px(16), barsBottom + px(80)) })
        t.alpha = 0f; t.translationY = px(20).toFloat()
        t.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(Kit.ease).start()
        toastView = t
        main.removeCallbacks(hideToast); main.postDelayed(hideToast, 6000)
    }

    // ---- Sheets ------------------------------------------------------------------------------

    private fun openSheet(refresh: Boolean = false, build: (LinearLayout) -> Unit) {
        val existing = sheetPanel
        if (existing != null) {
            val body = existing.getChildAt(0) as LinearLayout
            val keep = body.paddingBottom
            body.removeAllViews(); body.addView(grip()); build(body)
            body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, keep)
            if (!refresh) existing.scrollTo(0, 0)
            return
        }
        val host = FrameLayout(this)
        val scrim = View(this).apply {
            setBackgroundColor(Kit.SCRIM); alpha = 0f
            isClickable = true; setOnClickListener { closeSheet() }
        }
        host.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), 0, px(22), barsBottom + px(28))
        }
        body.addView(grip()); build(body)
        val panel = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = GradientDrawable().apply {
                setColor(Kit.SURFACE)
                val r = px(28).toFloat(); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            clipToOutline = true
            addView(body, FrameLayout.LayoutParams(-1, -2))
        }
        val maxW = minOf(resources.displayMetrics.widthPixels, px(640))
        host.addView(panel, FrameLayout.LayoutParams(maxW, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { topMargin = barsTop + px(40) })
        host.elevation = px(20).toFloat()
        root.addView(host, FrameLayout.LayoutParams(-1, -1))
        sheet = host; sheetPanel = panel
        panel.translationY = resources.displayMetrics.heightPixels.toFloat()
        scrim.animate().alpha(1f).setDuration(280).start()
        panel.animate().translationY(0f).setDuration(420).setInterpolator(Kit.ease).start()
        root.requestApplyInsets()
    }

    private fun grip() = FrameLayout(this).apply {
        setPadding(0, px(10), 0, px(14))
        addView(View(context).apply { background = rounded(Kit.HAIRLINE or 0x30000000, px(2).toFloat()) },
            FrameLayout.LayoutParams(px(40), px(4), Gravity.CENTER_HORIZONTAL))
    }

    private fun closeSheet() {
        val host = sheet ?: return
        val panel = sheetPanel
        sheet = null; sheetPanel = null; sheetRefresh = null; photoPicked = null
        onSheetClosed?.invoke(); onSheetClosed = null
        currentFocus?.let { f -> (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken, 0) }
        host.getChildAt(0).animate().alpha(0f).setDuration(240).start()
        panel?.animate()?.translationY(panel.height.toFloat() + px(40))?.setDuration(300)?.setInterpolator(Kit.ease)
            ?.withEndAction { root.removeView(host) }?.start() ?: root.removeView(host)
    }

    private fun LinearLayout.block(title: String, body: CharSequence, color: Int = Kit.INK) {
        addView(text(title.uppercase(), 11.5f, Kit.CHAMPAGNE, 500).apply { letterSpacing = 0.18f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20) })
        addView(text(body, 15f, color, 400), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
    }

    // ---- One plant ---------------------------------------------------------------------------

    private fun openPlant(id: String) {
        sheetRefresh = { if (Store.get(id) == null) closeSheet() else openSheet(refresh = true) { fillPlant(it, id) } }
        onSheetClosed = null
        openSheet { fillPlant(it, id) }
    }

    private fun who(by: String) = when { by.isBlank() -> ""; by == Store.me -> "you"; else -> by }

    private fun heading(b: LinearLayout, title: String, top: Int = 20) =
        b.addView(text(title.uppercase(), 11.5f, Kit.CHAMPAGNE, 500).apply { letterSpacing = 0.18f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(top) })

    private fun shortDate(t: Long): String = "%te %<tb".format(Calendar.getInstance().apply { timeInMillis = t })

    private fun lightSource(p: Plant): String = when (p.lightFrom) {
        "measured" -> "Measured at %,d lux on %s.".format(p.lux?.roundToInt() ?: 0, shortDate(p.luxAt ?: p.updated))
        "photo" -> if (p.lux != null) "Estimated from its photo's exposure (about %,d lux). Measure it for accuracy.".format(p.lux.roundToInt())
            else "Judged from its photo. Measure it for accuracy."
        "set" -> "Set by you."
        else -> "Not known yet. Measure it, or pick one."
    }

    private fun fillPlant(b: LinearLayout, id: String) {
        val p = Store.get(id) ?: return
        val k = Kinds[p.kind]
        val s = Rhythm.standing(p)

        val hero = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val tile = Tile(this, px(16).toFloat(), badge = false).apply { bind(p, s, false) }
        hero.addView(tile, LinearLayout.LayoutParams(px(118), -2))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, 0, 0) }
        words.addView(text(p.name, if (p.name.length > 14) 25f else 30f, Kit.INK, displayFace = true).apply { setLineSpacing(0f, 1.05f) })
        if (k.latin.isNotBlank()) words.addView(text(k.latin, 13.5f, Kit.MUTED).apply { setTypeface(typeface, android.graphics.Typeface.ITALIC) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
        words.addView(text(Personality[p.kind].title.uppercase(), 11f, Kit.CHAMPAGNE, 500).apply { letterSpacing = 0.18f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        val lastBy = p.log.firstOrNull { it.k != "damp" }?.by ?: ""
        val state = if (p.last != null) "${Rhythm.dueText(s)}. Watered ${Rhythm.ago(p.last)}${if (who(lastBy).isNotBlank()) " by ${who(lastBy)}" else ""}."
            else "Not logged yet. Feel the soil first."
        words.addView(text(state, 15f, if (s.thirst == Thirst.CONTENT || s.thirst == Thirst.NEW) Kit.INK else thirstColor(s.thirst), 500),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        hero.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        b.addView(hero)

        b.addView(pour("Watered it") { closeSheet(); act(id, Store.Act.WATERED) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22) })
        val two = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        two.addView(quiet("Soil still damp", "Wait 2 days, water less often") { closeSheet(); act(id, Store.Act.DAMP) }, LinearLayout.LayoutParams(0, -2, 1f))
        two.addView(quiet("It was bone dry", "Watered, sooner next time") { closeSheet(); act(id, Store.Act.DRY) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(10) })
        b.addView(two, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })

        if (s.thirst == Thirst.THIRSTY) b.block("${p.name} says", Personality.thirsty(p), Kit.CHAMPAGNE)

        val m = Rhythm.month(System.currentTimeMillis())
        // The rhythm in plain words: how often, what's usual, and what is stretching or shortening it
        val slower = mutableListOf<String>(); val faster = mutableListOf<String>()
        if (Rhythm.season(m) * Rhythm.winterExtra(p.kind, m) > 1.0) slower += if (Rhythm.winterExtra(p.kind, m) > 1.0) "its winter rest" else "shorter days"
        Rhythm.spotParts(p, m).forEach { (what, f) -> if (f > 1.0) slower.add(what) else faster.add(what) }
        if (p.learn > 1.0) slower += "damp soil at past checks" else if (p.learn < 1.0) faster += "dry soil at past checks"
        fun list(l: List<String>) = if (l.size < 2) l.joinToString("") else l.dropLast(1).joinToString(", ") + " and " + l.last()
        val why = listOfNotNull(
            slower.takeIf { it.isNotEmpty() }?.let { "slower for ${list(it)}" },
            faster.takeIf { it.isNotEmpty() }?.let { "faster for ${list(it)}" },
        ).joinToString("; ")
        b.block("Watering", "Every ${s.days.roundToInt()} days for now." +
            if (why.isNotEmpty()) " Usually ${p.base} in summer; $why." else "")

        // Its spot: light, heat and pot each change how fast the soil dries
        heading(b, "Its spot", 26)
        b.addView(text(if (p.spot.isNotBlank()) p.spot else "Somewhere in the house", 15f, Kit.INK), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
        b.addView(label("Light"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
        val lights = Light.values()
        b.addView(Chips(this, lights.map { it.label }, p.light?.let { lights.indexOf(it) } ?: -1) { i ->
            Store.get(id)?.let { Store.put(it.copy(light = lights[i], lightFrom = "set")) }
        })
        b.addView(text(lightSource(p) + (p.light?.let { " ${it.detail}" } ?: ""), 13f, Kit.MUTED).apply { setLineSpacing(0f, 1.25f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        b.addView(quiet("Measure the light here", "Hold the phone where the plant sits") { openMeter(id) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        b.addView(Row(this, "Next to a radiator", "Dries faster while the heating's on, Oct to Apr", Toggle(this, p.heat) { on ->
            Store.get(id)?.let { Store.put(it.copy(heat = on)) }
        }).apply { setPadding(0, px(12), 0, px(4)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
        b.addView(label("Pot"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        val pots = Pot.values()
        b.addView(Chips(this, pots.map { it.label }, p.pot?.let { pots.indexOf(it) } ?: -1) { i ->
            Store.get(id)?.let { Store.put(it.copy(pot = pots[i])) }
        })

        // How it looks: a fresh photo now and then shows how it's doing
        heading(b, "How it looks", 26)
        val look = mutableListOf<String>()
        look += p.photoAt?.let { "Photo taken ${Rhythm.ago(it)}." } ?: "Photo from 25 Oct."
        p.reads.firstOrNull()?.let { look += "About ${(it.green * 100).roundToInt()}% of the leaf colour reads green." }
        Look.trend(p.reads)?.let { look += it }
        if (p.reads.size < 2) look += "Take a new photo every few weeks and Sete compares the leaf colour."
        b.addView(text(look.joinToString(" "), 15f, Kit.INK).apply { setLineSpacing(0f, 1.3f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
        if (p.seen.isNotBlank()) b.addView(text(p.seen, 13.5f, Kit.MUTED).apply { setLineSpacing(0f, 1.25f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        val shots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shots.addView(quiet("New photo") { shotFor = id; camera(12) }, LinearLayout.LayoutParams(0, -2, 1f))
        shots.addView(quiet("From gallery") { shotFor = id; gallery(14) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(10) })
        b.addView(shots, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })

        b.block("How to water", k.how)
        b.block("Thirst signal", k.cue)
        b.block("Pets", k.pets, when (k.toxic) { true -> Kit.THIRST; false -> Kit.GOOD; null -> Kit.MUTED })
        if (p.log.isNotEmpty()) {
            heading(b, "History")
            p.log.take(8).forEach { e ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(8), 0, px(8)) }
                val what = when (e.k) { "damp" -> "Felt damp, waited"; "dry" -> "Watered, was bone dry"; else -> "Watered" } +
                    (if (who(e.by).isNotBlank()) " · ${who(e.by)}" else "")
                row.addView(text(what, 14.5f, Kit.INK), LinearLayout.LayoutParams(0, -2, 1f))
                val cal = Calendar.getInstance().apply { timeInMillis = e.t }
                row.addView(text("%ta %<te %<tb".format(cal), 13.5f, Kit.MUTED).apply { fontFeatureSettings = "tnum" })
                b.addView(row)
                b.addView(View(this).apply { setBackgroundColor(Kit.HAIRLINE) }, LinearLayout.LayoutParams(-1, px(1)))
            }
        }
        b.addView(quiet("Edit plant") { openEdit(id) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(26) })
    }

    // ---- Light meter ---------------------------------------------------------------------------

    /** The phone's own light sensor, read live: hold it where the plant sits and save the reading. */
    private fun openMeter(id: String) {
        val p = Store.get(id) ?: return
        sheetRefresh = null
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)
        var lux = -1.0
        val number = text("…", 44f, Kit.INK, 300).apply { fontFeatureSettings = "tnum" }
        val band = text("", 17f, Kit.CHAMPAGNE, 500)
        val detail = text("", 14f, Kit.MUTED).apply { setLineSpacing(0f, 1.25f) }
        val gauge = LuxGauge(this)
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val v = e.values[0].toDouble()
                lux = if (lux < 0) v else lux * 0.8 + v * 0.2
                val l = Light.fromLux(lux)
                number.text = "%,d lux".format(lux.roundToInt())
                band.text = l.label
                detail.text = l.detail + if (lux < 60 && Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it < 8 || it >= 17 })
                    " It looks dark out: daylight gives a truer reading." else ""
                gauge.lux = lux
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        onSheetClosed = { sm?.unregisterListener(listener) }
        openSheet { b ->
            b.addView(text("Light at ${p.name}", 28f, Kit.INK, displayFace = true))
            b.addView(text("Hold the phone where the plant sits, screen up and facing the window, in daylight. The reading settles in a few seconds.",
                14.5f, Kit.MUTED).apply { setLineSpacing(0f, 1.25f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
            if (sensor == null) {
                b.addView(text("This phone has no light sensor Sete can read. Pick the light level on the plant instead.", 15f, Kit.INK),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20) })
                return@openSheet
            }
            b.addView(number, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22) })
            b.addView(band, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2) })
            b.addView(gauge, LinearLayout.LayoutParams(-1, px(46)).apply { topMargin = px(14) })
            b.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
            b.addView(pour("Save this reading") {
                if (lux < 0) return@pour
                val l = Light.fromLux(lux)
                Store.get(id)?.let { Store.put(it.copy(light = l, lux = lux, luxAt = System.currentTimeMillis(), lightFrom = "measured")) }
                val days = Store.get(id)?.let { Rhythm.days(it).roundToInt() }
                Sound.play(Sound.Cue.PLIP)
                toast("${l.label} light. ${p.name} now drinks every $days days.")
                openPlant(id)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(22) })
            b.addView(text("Thresholds are general guidance: under 800 lux low, under 2,500 medium, under 10,000 bright, above that direct sun.",
                12.5f, Kit.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
        }
        sensor?.let { sm.registerListener(listener, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
    }

    // ---- Add or edit -------------------------------------------------------------------------

    /** A photo read on the phone: the picture, its leaf colour and the light it was taken in. */
    data class Shot(val photo: String, val green: Double?, val lux: Double?)

    private fun openEdit(id: String?, shot: Shot? = null) {
        sheetRefresh = null
        onSheetClosed = null
        pendingShot = shot
        openSheet { fillEdit(it, id?.let { Store.get(it) }) }
    }

    private fun fillEdit(b: LinearLayout, p: Plant?) {
        var kind = p?.kind ?: "other"
        var base = p?.base ?: Kinds[kind].base
        var baseTouched = p != null
        var light: Light? = p?.light
        var lightFrom = p?.lightFrom ?: ""
        var heat = p?.heat ?: false
        var pot: Pot? = p?.pot

        b.addView(text(if (p == null) "A new plant" else "Edit ${p.name}", 30f, Kit.INK, displayFace = true))
        if (p == null) b.addView(text("Name it and pick its type; the rhythm fills in from its type and spot.", 14f, Kit.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })

        // Photo
        val preview = Tile(this, px(14).toFloat(), badge = false)
        fun showPreview() {
            val d = Plant(id = p?.id ?: "new", name = nameOf(b, p), kind = kind, photo = pendingShot?.photo ?: p?.photo ?: "")
            preview.bind(d, Standing(Thirst.CONTENT, 7.0, 7.0, 0f, null), false)
        }
        val photoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        photoRow.addView(preview, LinearLayout.LayoutParams(px(88), -2))
        val photoButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        photoButtons.addView(quiet("Take a photo") { camera(10) }, LinearLayout.LayoutParams(-1, -2))
        photoButtons.addView(quiet("From gallery") { gallery(9) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        photoRow.addView(photoButtons, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(14) })
        b.addView(photoRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18) })
        val lightNote = text("", 13f, Kit.MUTED).apply { setLineSpacing(0f, 1.25f) }
        var lightChips: Chips? = null

        b.addView(label("Name"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18) })
        val name = field("Peace Lily", p?.name ?: "").apply { tag = "name" }
        b.addView(name)

        b.addView(label("Type"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
        val readout = text("", 17f, Kit.INK, 500).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        fun paintBase() { readout.text = "$base days" }
        val kinds = Kinds.all
        b.addView(Chips(this, kinds.map { it.label }, kinds.indexOfFirst { it.key == kind }.coerceAtLeast(0)) { i ->
            kind = kinds[i].key
            if (!baseTouched) { base = kinds[i].base; paintBase() }
        })

        b.addView(label("Where it lives"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
        val spot = field("Bay window", p?.spot ?: "")
        b.addView(spot)

        if (p == null) {
            // A new plant's spot; an existing plant's is on its own page
            b.addView(label("Light"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
            val lights = Light.values()
            lightChips = Chips(this, lights.map { it.label }, light?.let { lights.indexOf(it) } ?: -1) { i -> light = lights[i]; lightFrom = "set"; lightNote.text = lights[i].detail }
            b.addView(lightChips)
            b.addView(lightNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
            b.addView(Row(this, "Next to a radiator", "Dries faster while the heating's on", Toggle(this, heat) { heat = it }).apply { setPadding(0, px(10), 0, px(2)) })
            b.addView(label("Pot"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(6) })
            val pots = Pot.values()
            b.addView(Chips(this, pots.map { it.label }, pot?.let { pots.indexOf(it) } ?: -1) { i -> pot = pots[i] })
        }

        // A photo arriving fills in what it can: the light it was taken in, unless she has chosen
        fun useShot(s: Shot) {
            pendingShot = s
            showPreview()
            val l = s.lux?.let { Light.fromLux(it) }
            if (p == null && l != null && lightFrom != "set") {
                light = l; lightFrom = "photo"
                lightChips?.select(Light.values().indexOf(l))
                lightNote.text = "Looks ${l.label.lowercase()} from the photo's exposure (about %,d lux). Measure it later for accuracy.".format((s.lux ?: 0.0).roundToInt())
            }
        }
        photoPicked = { s -> useShot(s) }
        pendingShot?.let { useShot(it) }

        b.addView(label("Summer rhythm (stretched automatically for the season and spot)"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
        val stepper = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun stepBtn(s: String, d: Int) = text(s, 20f, Kit.INK, 400).apply {
            gravity = Gravity.CENTER
            background = rounded(Kit.RAISED, px(14).toFloat(), Kit.HAIRLINE, px(1))
            isClickable = true
            setOnClickListener { base = (base + d).coerceIn(2, 45); baseTouched = true; paintBase(); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
        }
        stepper.addView(stepBtn("−", -1), LinearLayout.LayoutParams(px(52), px(48)))
        stepper.addView(readout, LinearLayout.LayoutParams(0, -2, 1f))
        stepper.addView(stepBtn("+", 1), LinearLayout.LayoutParams(px(52), px(48)))
        paintBase()
        b.addView(stepper)

        b.addView(primary(if (p == null) "Add to the garden" else "Save changes") {
            val n = name.text.toString().trim().ifBlank { "Unnamed plant" }
            val now = System.currentTimeMillis()
            val shot = pendingShot
            if (p == null) {
                Store.put(Plant(id = Store.newId(), name = n, kind = kind, spot = spot.text.toString().trim(), photo = shot?.photo ?: "", base = base, order = 999,
                    light = light, lightFrom = if (light == null) "" else lightFrom, lux = if (lightFrom == "photo") shot?.lux else null,
                    luxAt = if (lightFrom == "photo" && shot?.lux != null) now else null, heat = heat, pot = pot,
                    photoAt = if (shot != null) now else null, reads = shot?.green?.let { listOf(Read(now, it)) } ?: emptyList()))
                toast("Added $n")
            } else {
                var next = p.copy(name = n, kind = kind, spot = spot.text.toString().trim(), base = base)
                if (shot != null) next = next.copy(photo = shot.photo, photoAt = now, reads = shot.green?.let { (listOf(Read(now, it)) + p.reads).take(12) } ?: p.reads)
                Store.put(next)
                toast("Saved")
            }
            closeSheet()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(26) })

        if (p != null) {
            var sure = false
            lateinit var del: View
            del = quiet("Remove plant", color = Kit.THIRST) {
                if (!sure) { sure = true; ((del as LinearLayout).getChildAt(0) as TextView).text = "Tap again to remove ${p.name}" }
                else { Store.remove(p.id); closeSheet(); toast("Removed ${p.name}") { Store.put(p) } }
            }
            b.addView(del, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        }
        showPreview()
    }

    private fun nameOf(b: LinearLayout, p: Plant?): String = (b.findViewWithTag<EditText>("name")?.text?.toString()?.ifBlank { null }) ?: p?.name ?: "?"

    // ---- Photos --------------------------------------------------------------------------------
    // 9 gallery for the open sheet, 10 camera for the open sheet, 11 camera for a new plant,
    // 12 camera for a plant's new photo, 14 gallery for a plant's new photo.

    private fun captureFile() = java.io.File(java.io.File(cacheDir, "camera").apply { mkdirs() }, "capture.jpg")

    /** The camera app, straight away: no permission needed, as Sete never touches the camera itself. */
    private fun camera(code: Int) {
        val f = captureFile().apply { delete() }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(i, code) }.onFailure {
            toast("No camera app found. Choose a photo instead.")
            gallery(if (code == 12) 14 else 9)
        }
    }

    private fun gallery(code: Int) {
        val pick = Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
        val i = if (pick.resolveActivity(packageManager) != null) pick else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(i, code) }.onFailure { toast("No photo picker found on this phone.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in listOf(9, 10, 11, 12, 14)) return
        if (resultCode != RESULT_OK) {
            // Camera closed without a photo while adding: carry on without one
            if (requestCode == 11) openEdit(null)
            return
        }
        val fromCamera = requestCode in listOf(10, 11, 12)
        val uri = if (fromCamera) Uri.fromFile(captureFile()) else (data?.data ?: return)
        val target = shotFor
        Thread {
            val shot = runCatching { read(uri, fromCamera) }.getOrNull()
            main.post {
                if (shot == null) { toast("Couldn't read that photo. Try another."); return@post }
                when (requestCode) {
                    11 -> openEdit(null, shot)
                    12, 14 -> target?.let { id ->
                        val p = Store.newPhoto(id, shot.photo, shot.green, shot.lux) ?: return@let
                        Sound.play(Sound.Cue.PLIP)
                        toast(Look.trend(p.reads)?.substringBefore(".")?.plus(".") ?: "New photo saved for ${p.name}.")
                    }
                    else -> photoPicked?.invoke(shot)
                }
            }
        }.start()
    }

    /** Crops to the garden's 4:5 frame at 480 × 600, reads its leaf colour and its exposure. */
    private fun read(uri: Uri, fromCamera: Boolean): Shot {
        val source = if (fromCamera) ImageDecoder.createSource(captureFile()) else ImageDecoder.createSource(contentResolver, uri)
        val src = ImageDecoder.decodeBitmap(source) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = info.size
            val scale = max(1, minOf(s.width / 600, s.height / 750))
            d.setTargetSize(s.width / scale, s.height / scale)
        }
        val out = Bitmap.createBitmap(480, 600, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val k = max(480f / src.width, 600f / src.height)
        val w = (480 / k).toInt(); val h = (600 / k).toInt()
        val x = (src.width - w) / 2; val y = ((src.height - h) * 0.45f).toInt()
        c.drawBitmap(src, Rect(x, y, x + w, y + h), RectF(0f, 0f, 480f, 600f), Paint(Paint.FILTER_BITMAP_FLAG))
        val bytes = ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 74, it) }.toByteArray()
        val lux = runCatching {
            (if (fromCamera) java.io.FileInputStream(captureFile()) else contentResolver.openInputStream(uri))?.use { Look.luxFromExif(it) }
        }.getOrNull()
        return Shot("data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP), Look.green(out), lux)
    }

    // ---- First run -----------------------------------------------------------------------------

    private fun openWelcome() {
        sheetRefresh = null
        openSheet { b ->
            b.addView(text("Welcome to Sete", 32f, Kit.INK, displayFace = true))
            b.addView(text("Twenty plants from around the house are already here, each with its own photo. " +
                "The water in each photo drains as its next drink comes due.", 15f, Kit.INK), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
            b.addView(text("Every morning Sete tells you who's thirsty. Tap Watered or Still damp right on the notification, " +
                "even from the cover screen.", 15f, Kit.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
            b.addView(label("Your name, for the watering history"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(20) })
            val me = field("Your name", Store.me)
            b.addView(me)
            b.addView(primary("Turn on reminders") {
                Store.me = me.text.toString()
                Store.onboarded = true
                askNotifications()
                closeSheet()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(24) })
            b.addView(quiet("Not now") { Store.me = me.text.toString(); Store.onboarded = true; closeSheet() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsOn()) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        else if (!notificationsOn()) openNotificationSettings()
        Reminders.schedule(this)
    }

    private fun openNotificationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        Reminders.schedule(this)
        paintRemindersPill()
        sheetRefresh?.invoke()
        if (requestCode == 7 && notificationsOn()) toast("Reminders on. The morning round is at ${clock(Store.morning)}.")
    }

    override fun onResume() {
        super.onResume()
        if (crashed) return
        paintRemindersPill()
        sheetRefresh?.invoke()
    }

    // ---- Settings ------------------------------------------------------------------------------

    private fun openSettings() {
        sheetRefresh = { openSheet(refresh = true) { fillSettings(it) } }
        openSheet { fillSettings(it) }
    }

    private fun section(b: LinearLayout, title: String) =
        b.addView(text(title.uppercase(), 11.5f, Kit.CHAMPAGNE, 500).apply { letterSpacing = 0.2f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(26); bottomMargin = px(10) })

    private fun fillSettings(b: LinearLayout) {
        b.addView(text("Settings", 32f, Kit.INK, displayFace = true))

        section(b, "Reminders")
        val card = Card(this)
        val on = notificationsOn()
        card.row(Row(this, "Notifications", if (on) "Thirsty plants arrive with Watered and Still damp buttons" else "Switched off, so reminders can't reach you",
            endValue(if (on) "On" else "Allow", if (on) Kit.MUTED else Kit.CHAMPAGNE)) { if (on) openNotificationSettings() else askNotifications() })

        val time = endValue(clock(Store.morning), Kit.INK)
        val stepper = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun step(s: String, d: Int) = text(s, 18f, Kit.INK).apply {
            gravity = Gravity.CENTER
            background = rounded(Kit.RAISED, px(12).toFloat())
            isClickable = true
            setOnClickListener {
                Store.morning = ((Store.morning + d) % 1440 + 1440) % 1440
                time.text = clock(Store.morning); Reminders.schedule(this@MainActivity); paintRemindersPill()
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        stepper.addView(step("−", -15), LinearLayout.LayoutParams(px(40), px(40)))
        stepper.addView(time.apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(px(70), -2))
        stepper.addView(step("+", 15), LinearLayout.LayoutParams(px(40), px(40)))
        card.row(Row(this, "Morning round", "Every thirsty plant, one bundle", stepper))
        card.row(Row(this, "Evening nudge at 7pm", "Only for plants still thirsty", Toggle(this, Store.evening) { Store.evening = it; Reminders.schedule(this) }))

        val exact = Reminders.canExact(this)
        card.row(Row(this, "Exact timing", if (exact) "Reminders arrive on the minute" else "Without it, Android may deliver them a little late",
            endValue(if (exact) "On" else "Allow", if (exact) Kit.MUTED else Kit.CHAMPAGNE)) {
            if (!exact) runCatching { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
        })
        val pm = getSystemService(PowerManager::class.java)
        val awake = pm.isIgnoringBatteryOptimizations(packageName)
        card.row(Row(this, "Keep reminders alive", if (awake) "Samsung won't put Sete to sleep" else "Stops One UI putting Sete to sleep and dropping reminders",
            endValue(if (awake) "Done" else "Allow", if (awake) Kit.MUTED else Kit.CHAMPAGNE)) {
            if (!awake) {
                @Suppress("BatteryLife")
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            }
        })
        card.row(Row(this, "Sounds", "Water and chimes. Quiet when the phone is on silent", Toggle(this, Store.sound) {
            Store.sound = it
            if (it) Sound.watered()
        }))
        card.row(Row(this, "Send a test reminder", "Shows today's round now", endValue("Send", Kit.CHAMPAGNE)) {
            if (!notificationsOn()) askNotifications() else { Reminders.round(this, "morning", test = true); toast("Sent. Check the notification shade.") }
        })
        b.addView(card)

        section(b, "You")
        val me = field("Your name", Store.me)
        me.setOnFocusChangeListener { _, focused -> if (!focused) Store.me = me.text.toString() }
        me.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { Store.me = s?.toString() ?: "" }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        b.addView(label("Shown in each plant's watering history"))
        b.addView(me)

        section(b, "Sharing the garden")
        when {
            !Sync.configured -> b.addView(text("Sharing between phones isn't switched on in this build. It needs a free Firebase project; " +
                "the steps are in the project's README. Everything else works on this phone alone.", 14.5f, Kit.MUTED))
            Store.household.isBlank() -> {
                b.addView(text("Share one garden between two phones, so a plant watered on one stays quiet on the other.", 14.5f, Kit.MUTED))
                b.addView(primary("Start a shared garden") {
                    toast("Putting the garden online…")
                    Sync.create(this) { code, err -> if (code != null) toast("Shared. Enter $code on the other phone.") else toast(err ?: "Couldn't share the garden.") ; sheetRefresh?.invoke() }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
                b.addView(label("Or join one with its code"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(18) })
                val code = field("10-letter code", "", caps = false).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
                b.addView(code)
                b.addView(quiet("Join with this code", "This phone's garden is replaced by the shared one") {
                    toast("Joining…")
                    Sync.join(this, code.text.toString()) { ok, err -> toast(if (ok) "Joined. You're sharing one garden." else err ?: "Couldn't join."); sheetRefresh?.invoke() }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
            }
            else -> {
                b.addView(text("This garden is shared. Enter this code on the other phone to join it.", 14.5f, Kit.MUTED))
                b.addView(text(Store.household.chunked(5).joinToString(" "), 34f, Kit.CHAMPAGNE, displayFace = true).apply {
                    letterSpacing = 0.12f; setTextIsSelectable(true)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
                val two = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                two.addView(quiet("Copy code") {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Sete garden code", Store.household))
                    toast("Code copied")
                }, LinearLayout.LayoutParams(0, -2, 1f))
                var sure = false
                lateinit var leave: View
                leave = quiet("Stop sharing", color = Kit.THIRST) {
                    if (!sure) { sure = true; ((leave as LinearLayout).getChildAt(0) as TextView).text = "Tap again to stop" }
                    else { Sync.leave(); toast("This phone keeps its own copy now."); sheetRefresh?.invoke() }
                }
                two.addView(leave, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(10) })
                b.addView(two, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(14) })
            }
        }

        b.addView(doctrineCard(listOf(
            "Water is the only material" to "Each plant's photo holds what is left of its last drink, and drains as the next one comes due. Thirsty photos lose a little colour.",
            "One climax" to "Watering drops a single bead into the photo; a ring spreads and the level springs up past its mark and settles. Water is soft, so it may overshoot. Nothing rigid does.",
            "Concentrato" to "A fast fling sheds the due dates and section notes, leaving names and water.",
            "The Giulietta principle, tuned for a chore" to "The Watered button appears only on plants that need it. Disclosure follows need, not touch.",
            "Time belongs to the user" to "Every act logs on the tap, with Undo for six seconds. Reminders carry their own buttons, so a round can be done from the cover screen.",
            "The soil decides" to "The calendar is a guess. Still damp waits two days and learns to wait longer; Bone dry waters and comes round sooner.",
            "The spot decides too" to "Light, a radiator and the pot each change how fast soil dries, and each is named in the plant's rhythm. Light is measured with the phone's own sensor, or estimated from a photo's exposure; a new photo is compared with the last for leaf colour. All of it stays on the phone.",
            "Materials of the home" to "The palette is the house itself. By day: greige walls, matte iron, oak, exposed brick for thirst, sage zellige for content and copper for what is chosen. By night: the kitchen's matte black, brick and Edison amber. Water stays water.",
        )), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(30) })
        b.addView(text("Care advice and pet notes are general guidance, not a vet's or a botanist's. Plant types marked as a best guess came from the photos.",
            12.5f, Kit.MUTED), LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })
    }
}
