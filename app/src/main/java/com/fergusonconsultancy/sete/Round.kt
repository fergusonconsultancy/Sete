package com.fergusonconsultancy.sete

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Watering Round: every thirsty plant, one at a time, full screen. Tap Watered and the
 * drop falls into its photo, the pot fills with a rising gurgle, the plant says thank you and
 * the next one slides in. The round ends with a chime and a single splash of drops.
 */
@SuppressLint("ViewConstructor")
class RoundView(
    private val ctx: Context,
    private val queue: List<String>,
    private val insetTop: Int,
    private val insetBottom: Int,
    private val onClose: () -> Unit,
) : FrameLayout(ctx) {
    private var index = 0
    private var busy = false
    private var watered = 0
    private var damp = 0
    private var skipped = 0
    private val started = System.currentTimeMillis()

    private val progress = ctx.text("", 13f, Kit.MUTED, 500).apply { letterSpacing = 0.12f }
    private val dots = Dots(ctx, queue.size)
    private val stage = FrameLayout(ctx)
    private var current: View? = null

    init {
        setBackgroundColor(Kit.GROUND)
        isClickable = true // nothing reaches the garden underneath
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.px(22), insetTop + ctx.px(14), ctx.px(22), insetBottom + ctx.px(18))
        }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        left.addView(progress)
        left.addView(dots, LinearLayout.LayoutParams(-1, ctx.px(10)).apply { topMargin = ctx.px(8) })
        bar.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ctx.text("Close", 14.5f, Kit.MUTED, 500).apply {
            setPadding(ctx.px(14), ctx.px(10), 0, ctx.px(10))
            isClickable = true
            setOnClickListener { close() }
        })
        col.addView(bar)
        col.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(col, LayoutParams(-1, -1))
        if (queue.isEmpty()) post { finish() } else post { show(0) }
    }

    fun close() {
        animate().alpha(0f).translationY(ctx.px(30).toFloat()).setDuration(260).setInterpolator(Kit.ease).withEndAction { onClose() }.start()
    }

    private fun show(i: Int) {
        index = i
        val p = Store.get(queue[i])
        if (p == null) { next(); return }
        progress.text = "PLANT ${i + 1} OF ${queue.size}"
        dots.done = i
        val card = card(p)
        stage.addView(card, FrameLayout.LayoutParams(-1, -1))
        card.translationX = stage.width * 0.35f
        card.alpha = 0f
        card.animate().translationX(0f).alpha(1f).setDuration(380).setInterpolator(Kit.ease).start()
        current = card
        busy = false
    }

    private fun card(p: Plant): View {
        val s = Rhythm.standing(p)
        val ch = Personality[p.kind]
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val avail = ctx.resources.displayMetrics.widthPixels - ctx.px(44)
        val room = ctx.resources.displayMetrics.heightPixels - insetTop - insetBottom - ctx.px(430)
        val tileW = min(min(avail, ctx.px(300)), room.coerceAtLeast(ctx.px(150)) * 4 / 5)
        val tile = Tile(ctx, ctx.px(22).toFloat(), badge = false).apply { bind(p, s, false) }
        col.addView(tile, LinearLayout.LayoutParams(tileW, -2).apply { topMargin = ctx.px(18) })
        col.addView(ctx.text(ch.title.uppercase(), 11.5f, Kit.CHAMPAGNE, 500).apply { letterSpacing = 0.2f; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.px(18) })
        col.addView(ctx.text(p.name, 34f, Kit.INK, displayFace = true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.px(4) })
        val voice = ctx.text(if (s.thirst == Thirst.NEW) "First check. Feel the soil, then tell me." else Personality.thirsty(p), 17f, Kit.INK, 300).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.3f)
        }
        col.addView(voice, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(8) })
        col.addView(ctx.text(Rhythm.dueText(s) + if (p.spot.isNotBlank()) "  ·  ${p.spot}" else "", 13f, Kit.MUTED).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(6) })

        val spacer = View(ctx)
        col.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(ctx.pour("Watered") { act(p, tile, voice, Store.Act.WATERED) }, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ctx.quiet("Still damp") { act(p, tile, voice, Store.Act.DAMP) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ctx.quiet("Bone dry") { act(p, tile, voice, Store.Act.DRY) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ctx.px(8) })
        row.addView(ctx.quiet("Skip") { if (!busy) { busy = true; skipped++; next() } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ctx.px(8) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(10) })
        return col
    }

    private fun act(p: Plant, tile: Tile, voice: TextView, a: Store.Act) {
        if (busy) return
        busy = true
        Store.act(p.id, a)
        val now = Store.get(p.id) ?: p
        val line = when (a) { Store.Act.DAMP -> Personality.damp(p); else -> Personality.thanks(p) }
        voice.animate().alpha(0f).setDuration(140).withEndAction {
            voice.text = line
            voice.setTextColor(if (a == Store.Act.DAMP) Kit.INK else Kit.WATER)
            voice.animate().alpha(1f).setDuration(220).start()
        }.start()
        if (a == Store.Act.DAMP) {
            damp++
            Sound.play(Sound.Cue.TAP)
            tile.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            postDelayed({ next() }, 1300)
        } else {
            watered++
            Sound.watered()
            tile.bind(now, Rhythm.standing(now), animate = true)
            postDelayed({ next() }, 1900)
        }
    }

    private fun next() {
        val old = current
        old?.animate()?.translationX(-stage.width * 0.3f)?.alpha(0f)?.setDuration(300)?.setInterpolator(Kit.ease)
            ?.withEndAction { stage.removeView(old) }?.start()
        if (index + 1 < queue.size) postDelayed({ show(index + 1) }, 120)
        else { dots.done = queue.size; postDelayed({ finish() }, 260) }
    }

    private fun finish() {
        stage.removeAllViews()
        progress.text = "ROUND DONE"
        val now = System.currentTimeMillis()
        val stillThirsty = Store.all().count { Rhythm.standing(it, now).thirst == Thirst.THIRSTY }
        val grew = if (stillThirsty == 0 && (watered + damp) > 0) Store.noteCleared() else false
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val splash = Splash(ctx)
        col.addView(splash, LinearLayout.LayoutParams(-1, ctx.px(170)))
        col.addView(ctx.text(if (queue.isEmpty()) "Nobody's thirsty" else "Round done", 44f, Kit.INK, displayFace = true).apply { gravity = Gravity.CENTER })
        val mins = ((now - started) / 60_000).toInt()
        val parts = mutableListOf<String>()
        if (watered > 0) parts += "$watered watered"
        if (damp > 0) parts += "$damp still damp"
        if (skipped > 0) parts += "$skipped skipped"
        val summary = if (queue.isEmpty()) "Every plant has had a drink. Enjoy the day."
            else parts.joinToString(", ") + if (mins >= 1) ", in $mins minute${if (mins == 1) "" else "s"}." else ", in under a minute."
        col.addView(ctx.text(summary, 16f, Kit.MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(10) })
        val streak = Store.streak
        if (streak > 0) col.addView(ctx.text(
            if (streak == 1) "Your first round with nobody left thirsty." else "$streak rounds in a row with nobody left thirsty.",
            15f, if (grew) Kit.WATER else Kit.INK, 500).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(14) })
        else if (stillThirsty > 0) col.addView(ctx.text("$stillThirsty still thirsty. They'll be in the evening nudge.", 15f, Kit.INK).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.px(14) })
        col.addView(View(ctx), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(ctx.pour("Back to the garden") { close() }, LinearLayout.LayoutParams(-1, -2))
        stage.addView(col, FrameLayout.LayoutParams(-1, -1))
        col.alpha = 0f
        col.animate().alpha(1f).setDuration(300).start()
        if (watered + damp > 0) { Sound.play(Sound.Cue.CHIME, vary = false); splash.burst() }
    }
}

/** Progress as a row of drops: filled for each plant done. */
class Dots(ctx: Context, private val n: Int) : View(ctx) {
    var done = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        if (n == 0) return
        val gap = context.px(5).toFloat()
        val w = ((width - gap * (n - 1)) / n).coerceAtMost(context.px(26).toFloat())
        val h = height.toFloat()
        for (i in 0 until n) {
            p.color = if (i < done) Kit.WATER else Kit.RAISED
            val x = i * (w + gap)
            c.drawRoundRect(x, h * 0.3f, x + w, h * 0.7f, h, h, p)
        }
    }
}

/** One splash of drops thrown up from the centre and pulled back down: the round's climax. */
class Splash(ctx: Context) : View(ctx) {
    private class Drop(val angle: Double, val speed: Float, val size: Float)
    private var drops = emptyList<Drop>()
    private var t = -1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.WATER }
    private val path = Path()

    fun burst() {
        drops = List(18) {
            Drop(Math.toRadians(Random.nextDouble(-160.0, -20.0)), Random.nextDouble(0.55, 1.0).toFloat(), Random.nextDouble(0.6, 1.2).toFloat())
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { t = -1f; invalidate() }
            })
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        if (t < 0f) return
        val cx = width / 2f; val cy = height * 0.82f
        val v0 = height * 2.4f; val g = height * 5.2f
        val base = context.px(4).toFloat()
        drops.forEach { d ->
            val time = t * 1.1f
            val vx = (cos(d.angle) * v0 * d.speed).toFloat() * 0.75f
            val vy = (sin(d.angle) * v0 * d.speed).toFloat()
            val x = cx + vx * time
            val y = cy + vy * time + 0.5f * g * time * time
            p.alpha = ((1f - t) * 255).toInt().coerceIn(0, 255)
            val r = base * d.size
            // A drop leans the way it travels
            val dirX = vx; val dirY = vy + g * time
            val len = kotlin.math.sqrt(dirX * dirX + dirY * dirY).coerceAtLeast(1f)
            val ux = dirX / len; val uy = dirY / len
            path.reset()
            path.moveTo(x - ux * r * 2.4f, y - uy * r * 2.4f)
            path.lineTo(x - uy * r, y + ux * r)
            path.lineTo(x + uy * r, y - ux * r)
            path.close()
            c.drawPath(path, p)
            c.drawCircle(x, y, r, p)
        }
    }
}
