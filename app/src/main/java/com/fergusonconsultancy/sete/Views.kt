package com.fergusonconsultancy.sete

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

fun tint(color: Int, a: Float): Int = (((color ushr 24) * a).toInt().coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

val THIRST_COLOR = mapOf(
    Thirst.THIRSTY to Kit.THIRST, Thirst.SOON to Kit.CHAMPAGNE, Thirst.CONTENT to Kit.GOOD, Thirst.NEW to Kit.MUTED,
)
val THIRST_LABEL = mapOf(Thirst.THIRSTY to "Thirsty", Thirst.SOON to "Soon", Thirst.CONTENT to "Content", Thirst.NEW to "New")

/**
 * A plant's photo holding its water. The level is what is left of the last drink and it drains
 * day by day; when the plant is watered a drop falls into the photo, a ring spreads, and the
 * level rises past its mark and settles, like water finding its level. Water is soft, so it
 * may overshoot; nothing else in the app does.
 */
@SuppressLint("ViewConstructor")
class Tile(ctx: Context, private val radius: Float, private val badge: Boolean = true) : View(ctx) {
    private var plant: Plant? = null
    private var bitmap: Bitmap? = null
    private var level = 0f
    private var thirst = Thirst.NEW
    private var sat = 1f
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val water = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.px(1.2f).toFloat(); color = tint(Kit.WATER, 0.85f) }
    private val drop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.WATER }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.px(1.5f).toFloat(); color = Kit.WATER }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.px(10.5f).toFloat(); typeface = Kit.sans(ctx, 500); letterSpacing = 0.08f; color = Kit.INK }
    private val mono = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Kit.display(ctx); color = Kit.CHAMPAGNE; textAlign = Paint.Align.CENTER }
    private val clip = Path()
    private val wave = Path()
    private val r = RectF()
    private val src = Rect()

    // The pour
    private var pour = -1f
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, width * 5 / 4)
    }

    fun bind(p: Plant, s: Standing, animate: Boolean) {
        val photoChanged = plant?.photo != p.photo || bitmap == null
        plant = p
        if (photoChanged) bitmap = Photos.get(context, p)
        thirst = s.thirst
        val target = s.level
        val targetSat = if (s.thirst == Thirst.THIRSTY) 0.6f else 1f
        if (animate && target - level > 0.15f) pourTo(target, targetSat)
        else if (animator == null) { level = target; sat = targetSat }
        contentDescription = "${p.name}, ${Rhythm.dueText(s)}"
        invalidate()
    }

    private fun pourTo(target: Float, targetSat: Float) {
        animator?.cancel()
        val from = level; val fromSat = sat
        val rise = OvershootInterpolator(1.6f)
        val fall = PathInterpolator(0.55f, 0f, 0.9f, 0.5f)
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            addUpdateListener {
                val t = it.animatedFraction
                pour = t
                // The drop falls for the first third; the water answers it
                val u = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
                level = from + (target - from) * rise.getInterpolation(u)
                sat = fromSat + (targetSat - fromSat) * u
                phase = fall.getInterpolation(t) * 2f * PI.toFloat()
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { pour = -1f; level = target; sat = targetSat; animator = null; invalidate() }
            })
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        r.set(0f, 0f, w, h)
        clip.reset(); clip.addRoundRect(r, radius, radius, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        c.drawColor(Kit.RAISED)
        val b = bitmap
        if (b != null) {
            // Centre-crop
            val scale = max(w / b.width, h / b.height)
            val sw = (w / scale).toInt(); val sh = (h / scale).toInt()
            val sx = (b.width - sw) / 2; val sy = ((b.height - sh) * 0.45f).toInt()
            src.set(sx, sy, sx + sw, sy + sh)
            if (sat < 0.999f) {
                val m = ColorMatrix().apply { setSaturation(sat) }
                val dim = 0.92f + 0.08f * ((sat - 0.6f) / 0.4f).coerceIn(0f, 1f)
                m.postConcat(ColorMatrix(floatArrayOf(dim, 0f, 0f, 0f, 0f, 0f, dim, 0f, 0f, 0f, 0f, 0f, dim, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
                photoPaint.colorFilter = ColorMatrixColorFilter(m)
            } else photoPaint.colorFilter = null
            c.drawBitmap(b, src, r, photoPaint)
        } else {
            pill.shader = RadialGradient(w / 2, h * 0.35f, w, Kit.RAISED, Kit.SURFACE, Shader.TileMode.CLAMP)
            c.drawRect(r, pill); pill.shader = null
            mono.textSize = w * 0.42f
            c.drawText((plant?.name?.trim()?.firstOrNull() ?: '?').uppercase(), w / 2, h * 0.58f, mono)
        }

        // The water
        val top = h * (1f - level)
        if (level > 0.003f) {
            val amp = context.px(2.6f).toFloat() * (if (pour >= 0f) 1.6f else 1f)
            val period = context.px(46).toFloat()
            wave.reset(); wave.moveTo(0f, h)
            var x = 0f
            wave.lineTo(0f, top)
            while (x <= w + 2) {
                wave.lineTo(x, top + amp * sin((x / period) * 2 * PI.toFloat() + phase))
                x += 3f
            }
            wave.lineTo(w, h); wave.close()
            water.shader = LinearGradient(0f, h, 0f, top - amp, tint(Kit.WATER_DEEP, 0.66f), tint(Kit.WATER, 0.24f), Shader.TileMode.CLAMP)
            c.drawPath(wave, water)
            wave.reset(); x = 0f
            wave.moveTo(0f, top + amp * sin(phase))
            while (x <= w + 2) { wave.lineTo(x, top + amp * sin((x / period) * 2 * PI.toFloat() + phase)); x += 3f }
            c.drawPath(wave, line)
        }

        // The drop and its ring
        if (pour in 0f..1f) {
            val landY = (h * (1f - level)).coerceAtLeast(h * 0.2f)
            if (pour < 0.32f) {
                val t = pour / 0.32f
                val y = -context.px(10) + (landY + context.px(10)) * t * t
                val dr = context.px(5).toFloat()
                wave.reset()
                wave.moveTo(w / 2, y - dr * 2.1f)
                wave.cubicTo(w / 2 + dr * 0.2f, y - dr * 1.2f, w / 2 + dr, y - dr * 0.4f, w / 2 + dr, y + dr * 0.2f)
                wave.cubicTo(w / 2 + dr, y + dr * 1.3f, w / 2 - dr, y + dr * 1.3f, w / 2 - dr, y + dr * 0.2f)
                wave.cubicTo(w / 2 - dr, y - dr * 0.4f, w / 2 - dr * 0.2f, y - dr * 1.2f, w / 2, y - dr * 2.1f)
                c.drawPath(wave, drop)
            } else if (pour < 0.95f) {
                val t = (pour - 0.32f) / 0.63f
                ring.alpha = ((1f - t) * 230).toInt()
                val rx = context.px(6) + w * 0.42f * t
                r.set(w / 2 - rx, landY - rx * 0.28f, w / 2 + rx, landY + rx * 0.28f)
                c.drawOval(r, ring)
            }
        }

        // The badge: one word of state
        if (badge) {
            val word = THIRST_LABEL.getValue(thirst).uppercase()
            val pad = context.px(9).toFloat(); val dot = context.px(6).toFloat()
            val tw = label.measureText(word)
            val bh = context.px(22).toFloat()
            r.set(context.px(9).toFloat(), context.px(9).toFloat(), context.px(9) + pad + dot + context.px(6) + tw + pad, context.px(9) + bh)
            pill.color = tint(Kit.GROUND, 0.74f)
            c.drawRoundRect(r, bh / 2, bh / 2, pill)
            pill.color = THIRST_COLOR.getValue(thirst)
            c.drawCircle(r.left + pad + dot / 2, r.centerY(), dot / 2, pill)
            c.drawText(word, r.left + pad + dot + context.px(6), r.centerY() + label.textSize * 0.36f, label)
        }
        c.restore()
    }
}

/** One plant on the garden screen. Its Watered button is there only when the plant needs it. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PlantCard(ctx: Context, val plantId: String, onOpen: (String) -> Unit, onWater: (String) -> Unit) : LinearLayout(ctx) {
    val tile = Tile(ctx, ctx.px(18).toFloat())
    private val name = ctx.text("", 15f, Kit.INK, 500)
    private val sub = ctx.text("", 12.5f, Kit.MUTED, 400).apply { tag = SECONDARY }
    private val quick = ctx.text("Watered", 14f, Kit.INK, 500).apply {
        gravity = Gravity.CENTER
        setPadding(ctx.px(10), ctx.px(10), ctx.px(10), ctx.px(10))
        background = rounded(tint(Kit.WATER, 0.2f), ctx.px(12).toFloat())
        setCompoundDrawablesRelativeWithIntrinsicBounds(DropIcon(ctx), null, null, null)
        compoundDrawablePadding = ctx.px(8)
        isClickable = true
        setOnClickListener { onWater(plantId) }
    }

    init {
        orientation = VERTICAL
        background = rounded(Kit.SURFACE, ctx.px(18).toFloat())
        clipToOutline = true
        addView(tile, LayoutParams(-1, -2))
        val words = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.px(12), ctx.px(10), ctx.px(12), ctx.px(12)) }
        words.addView(name)
        words.addView(sub, LayoutParams(-1, -2).apply { topMargin = ctx.px(3) })
        addView(words, LayoutParams(-1, -2))
        addView(quick, LayoutParams(-1, -2).apply { setMargins(ctx.px(10), 0, ctx.px(10), ctx.px(10)) })
        isClickable = true
        setOnClickListener { onOpen(plantId) }
        // A card gives under the finger and springs back
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.975f).scaleY(0.975f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.2f)).start()
            }
            false
        }
    }

    var thirsty = false
        private set

    /** A thirsty plant wilts a little and recovers: once, as the garden opens. */
    fun droop() {
        if (!isAttachedToWindow || width == 0) return
        pivotX = width / 2f; pivotY = height.toFloat()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            addUpdateListener {
                val t = it.animatedFraction
                rotation = -3.2f * sin(t * PI.toFloat() * 2.2f) * (1f - t)
            }
            start()
        }
    }

    fun bind(p: Plant, s: Standing, animate: Boolean) {
        thirsty = s.thirst == Thirst.THIRSTY
        tile.bind(p, s, animate)
        name.text = p.name
        sub.text = Rhythm.dueText(s)
        quick.visibility = if (s.thirst == Thirst.THIRSTY || s.thirst == Thirst.NEW || (s.thirst == Thirst.SOON && (s.remaining ?: 9.0) <= 1.0)) VISIBLE else GONE
    }
}

/** A small water drop, for buttons. */
class DropIcon(ctx: Context) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Kit.WATER }
    private val path = Path()
    private val s = ctx.px(14)
    override fun getIntrinsicWidth() = s * 3 / 4
    override fun getIntrinsicHeight() = s
    override fun draw(c: Canvas) {
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
        path.reset()
        path.moveTo(b.left + w / 2, b.top.toFloat())
        path.cubicTo(b.left + w / 2, b.top.toFloat(), b.left.toFloat(), b.top + h * 0.5f, b.left.toFloat(), b.top + h * 0.66f)
        path.cubicTo(b.left.toFloat(), b.top + h * 1.02f, b.right.toFloat(), b.top + h * 1.02f, b.right.toFloat(), b.top + h * 0.66f)
        path.cubicTo(b.right.toFloat(), b.top + h * 0.5f, b.left + w / 2, b.top.toFloat(), b.left + w / 2, b.top.toFloat())
        c.drawPath(path, p)
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/** Children in equal columns with a gap; column count follows the width. */
class Grid(ctx: Context) : ViewGroup(ctx) {
    private val gap = ctx.px(12)
    private fun cols(w: Int) = max(2, (w + gap) / (context.px(168) + gap))

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val n = cols(w)
        val cw = (w - gap * (n - 1)) / n
        var h = 0
        var start = 0
        while (start < childCount) {
            var rowH = 0
            for (i in start until minOf(start + n, childCount)) {
                val ch = getChildAt(i)
                ch.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                rowH = max(rowH, ch.measuredHeight)
            }
            h += rowH
            start += n
            if (start < childCount) h += gap
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val n = cols(w)
        val cw = (w - gap * (n - 1)) / n
        var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            val col = i % n
            val x = col * (cw + gap)
            ch.layout(x, y, x + cw, y + ch.measuredHeight)
            rowH = max(rowH, ch.measuredHeight)
            if (col == n - 1) { y += rowH + gap; rowH = 0 }
        }
    }
}

/** Children left to right, wrapping onto new lines. */
class Flow(ctx: Context) : ViewGroup(ctx) {
    private val gap = ctx.px(8)
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            ch.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + ch.measuredWidth > w) { x = 0; y += rowH + gap; rowH = 0 }
            x += ch.measuredWidth + gap; rowH = max(rowH, ch.measuredHeight)
        }
        setMeasuredDimension(w, y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val ch = getChildAt(i)
            if (x > 0 && x + ch.measuredWidth > w) { x = 0; y += rowH + gap; rowH = 0 }
            ch.layout(x, y, x + ch.measuredWidth, y + ch.measuredHeight)
            x += ch.measuredWidth + gap; rowH = max(rowH, ch.measuredHeight)
        }
    }
}

/** A tally chip: a coloured dot, a number and a word. */
fun Context.tally(n: Int, word: String, color: Int): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(px(12), px(7), px(14), px(7))
    background = rounded(0, px(20).toFloat(), Kit.HAIRLINE, px(1))
    addView(View(context).apply { background = rounded(color, 100f) }, LinearLayout.LayoutParams(px(7), px(7)).apply { marginEnd = px(8) })
    addView(text("$n", 17f, Kit.INK, 500).apply { fontFeatureSettings = "tnum" })
    addView(text(word, 13f, Kit.MUTED, 400), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
}

/** A text field in the kit. */
fun Context.field(hint: String, value: String, caps: Boolean = true): EditText = EditText(this).apply {
    setText(value)
    this.hint = hint
    textSize = 16f
    setTextColor(Kit.INK)
    setHintTextColor(Kit.MUTED)
    typeface = Kit.sans(context, 400)
    setSingleLine(true)
    inputType = InputType.TYPE_CLASS_TEXT or (if (caps) InputType.TYPE_TEXT_FLAG_CAP_WORDS else 0)
    background = rounded(Kit.RAISED, px(14).toFloat(), Kit.HAIRLINE, px(1))
    setPadding(px(14), px(13), px(14), px(13))
}

/** A small label above a field. */
fun Context.label(s: String): TextView = text(s, 12.5f, Kit.MUTED, 400).apply { setPadding(px(2), 0, 0, px(6)) }

/** A button that is not the main action: outlined, with an optional second line. */
fun Context.quiet(title: String, sub: String? = null, color: Int = Kit.INK, onTap: () -> Unit): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    gravity = Gravity.CENTER
    setPadding(px(12), px(13), px(12), px(13))
    background = rounded(0, px(16).toFloat(), Kit.HAIRLINE, px(1))
    addView(text(title, 14.5f, color, 500).apply { gravity = Gravity.CENTER })
    if (sub != null) addView(text(sub, 12f, Kit.MUTED, 400).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(3) })
    isClickable = true
    setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onTap() }
}

/** The main action in water: a full-width pill. */
fun Context.pour(label: String, onTap: () -> Unit): TextView = text(label, 16.5f, Kit.INK, 500).apply {
    gravity = Gravity.CENTER
    setPadding(px(20), px(17), px(20), px(17))
    background = rounded(Kit.WATER_DEEP, px(28).toFloat())
    isClickable = true
    setOnClickListener { onTap() }
}
