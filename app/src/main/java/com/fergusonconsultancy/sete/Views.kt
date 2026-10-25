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

/** Each state's colour, read from the current palette. */
fun thirstColor(t: Thirst): Int = when (t) {
    Thirst.THIRSTY -> Kit.THIRST; Thirst.SOON -> Kit.CHAMPAGNE; Thirst.CONTENT -> Kit.GOOD; Thirst.NEW -> Kit.MUTED
}
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
            pill.color = thirstColor(thirst)
            c.drawCircle(r.left + pad + dot / 2, r.centerY(), dot / 2, pill)
            c.drawText(word, r.left + pad + dot + context.px(6), r.centerY() + label.textSize * 0.36f, label)
        }
        c.restore()
    }
}

/** One plant on the garden screen. Its Watered button is there only when the plant needs it. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PlantCard(ctx: Context, val plantId: String, onOpen: (String) -> Unit, onWater: (String) -> Unit, onMenu: (String) -> Unit = {}) : LinearLayout(ctx) {
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
        // Hold a card for its quick actions, removing included
        setOnLongClickListener { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onMenu(plantId); true }
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
class Grid(ctx: Context, minColumnDp: Int = 168, gapDp: Int = 12) : ViewGroup(ctx) {
    private val gap = ctx.px(gapDp)
    private val minCol = ctx.px(minColumnDp)
    private fun cols(w: Int) = max(2, (w + gap) / (minCol + gap))

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

/**
 * A tally chip: a coloured dot, a number and a word, with a line underneath that says what is
 * behind the number. Tapping it takes you to that group.
 */
@SuppressLint("ClickableViewAccessibility")
fun Context.tally(n: Int, word: String, color: Int, detail: String, onTap: () -> Unit): View = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(px(14), px(9), px(16), px(10))
    background = rounded(0, px(18).toFloat(), Kit.HAIRLINE, px(1))
    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(View(context).apply { background = rounded(color, 100f) }, LinearLayout.LayoutParams(px(7), px(7)).apply { marginEnd = px(8) })
    head.addView(text("$n", 17f, Kit.INK, 500).apply { fontFeatureSettings = "tnum" })
    head.addView(text(word, 13f, Kit.MUTED, 400), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(6) })
    head.addView(text("›", 15f, Kit.MUTED, 300), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) })
    addView(head)
    addView(text(detail, 12f, color, 400).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; tag = SECONDARY },
        LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(3) })
    isClickable = true
    contentDescription = "$n $word. $detail"
    setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onTap() }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
        }
        false
    }
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
fun Context.pour(label: String, onTap: () -> Unit): TextView = text(label, 16.5f, Kit.ON_WATER, 500).apply {
    gravity = Gravity.CENTER
    setPadding(px(20), px(17), px(20), px(17))
    background = rounded(Kit.WATER_DEEP, px(28).toFloat())
    isClickable = true
    setOnClickListener { onTap() }
}

/** The light meter's scale: four bands on a logarithmic bar, low to direct sun, with the reading's mark. */
class LuxGauge(ctx: Context) : View(ctx) {
    var lux = -1.0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.px(10.5f).toFloat(); typeface = Kit.sans(ctx, 500); color = Kit.MUTED }
    private val r = RectF()
    private fun x(v: Double, w: Float): Float {
        val lo = kotlin.math.log10(50.0); val hi = kotlin.math.log10(30000.0)
        return (((kotlin.math.log10(v.coerceIn(50.0, 30000.0)) - lo) / (hi - lo)) * w).toFloat()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val bar = context.px(10).toFloat(); val top = context.px(6).toFloat()
        val edges = listOf(50.0, 800.0, 2500.0, 10000.0, 30000.0)
        val shades = listOf(0.25f, 0.45f, 0.7f, 1f)
        for (i in 0 until 4) {
            p.color = tint(Kit.CHAMPAGNE, shades[i])
            r.set(x(edges[i], w) + if (i > 0) context.px(2) else 0, top, x(edges[i + 1], w), top + bar)
            c.drawRoundRect(r, bar / 2, bar / 2, p)
            val name = Light.values()[i].label
            // The last band's name sits flush right so it never runs off the end
            val tx = if (i == 3) w - label.measureText(name) else r.left
            c.drawText(name, tx, top + bar + label.textSize + context.px(6), label)
        }
        if (lux >= 0) {
            val mx = x(lux, w)
            p.color = Kit.INK
            c.drawCircle(mx.coerceIn(bar, w - bar), top + bar / 2, bar * 0.85f, p)
            p.color = Kit.GROUND
            c.drawCircle(mx.coerceIn(bar, w - bar), top + bar / 2, bar * 0.35f, p)
        }
    }
}


/**
 * One plant as a row: the compact layout for a big garden. A small photo still holds its water,
 * and the drop button appears only when the plant needs it.
 */
@SuppressLint("ViewConstructor")
class PlantRow(ctx: Context, val plantId: String, onOpen: (String) -> Unit, onWater: (String) -> Unit, onMenu: (String) -> Unit) : LinearLayout(ctx) {
    val tile = Tile(ctx, ctx.px(12).toFloat(), badge = false)
    private val name = ctx.text("", 15.5f, Kit.INK, 500).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val sub = ctx.text("", 13f, Kit.MUTED, 400).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val dot = View(ctx)
    private val quick = android.widget.ImageView(ctx).apply {
        setImageDrawable(DropIcon(ctx))
        scaleType = android.widget.ImageView.ScaleType.CENTER
        background = rounded(tint(Kit.WATER, 0.2f), ctx.px(22).toFloat())
        contentDescription = "Watered"
        isClickable = true
        setOnClickListener { onWater(plantId) }
    }
    var thirsty = false
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.px(8), ctx.px(8), ctx.px(10), ctx.px(8))
        background = rounded(Kit.SURFACE, ctx.px(16).toFloat())
        addView(tile, LayoutParams(ctx.px(52), -2))
        val words = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.px(14), 0, ctx.px(8), 0) }
        words.addView(name)
        val line = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(dot, LayoutParams(ctx.px(6), ctx.px(6)).apply { marginEnd = ctx.px(7) })
        line.addView(sub, LayoutParams(0, -2, 1f))
        words.addView(line, LayoutParams(-1, -2).apply { topMargin = ctx.px(4) })
        addView(words, LayoutParams(0, -2, 1f))
        addView(quick, LayoutParams(ctx.px(44), ctx.px(44)))
        isClickable = true
        setOnClickListener { onOpen(plantId) }
        setOnLongClickListener { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onMenu(plantId); true }
    }

    fun bind(p: Plant, s: Standing, animate: Boolean) {
        thirsty = s.thirst == Thirst.THIRSTY
        tile.bind(p, s, animate)
        name.text = p.name
        sub.text = Rhythm.dueText(s) + if (p.spot.isNotBlank()) "  ·  ${p.spot}" else ""
        dot.background = rounded(thirstColor(s.thirst), 100f)
        quick.visibility = if (s.thirst == Thirst.THIRSTY || s.thirst == Thirst.NEW || (s.thirst == Thirst.SOON && (s.remaining ?: 9.0) <= 1.0)) VISIBLE else INVISIBLE
    }
}

/** Settings: three sliders, drawn so it matches the ink. */
class SlidersIcon(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.px(1.6f).toFloat(); strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val left = w * 0.28f; val right = w * 0.72f
        listOf(0.36f to 0.6f, 0.5f to 0.38f, 0.64f to 0.55f).forEach { (y, knob) ->
            p.color = Kit.MUTED; p.style = Paint.Style.STROKE
            c.drawLine(left, h * y, right, h * y, p)
            p.color = Kit.INK; p.style = Paint.Style.FILL
            c.drawCircle(left + (right - left) * knob, h * y, context.px(3).toFloat(), p)
        }
    }
}

/** A magnifier for the search field. */
class SearchIcon(ctx: Context) : android.graphics.drawable.Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.px(1.8f).toFloat(); strokeCap = Paint.Cap.ROUND; color = Kit.MUTED }
    private val s = ctx.px(18)
    override fun getIntrinsicWidth() = s
    override fun getIntrinsicHeight() = s
    override fun draw(c: Canvas) {
        val b = bounds; val r = b.width() * 0.3f
        val cx = b.left + b.width() * 0.42f; val cy = b.top + b.height() * 0.42f
        c.drawCircle(cx, cy, r, p)
        c.drawLine(cx + r * 0.72f, cy + r * 0.72f, b.right - b.width() * 0.12f, b.bottom - b.height() * 0.12f, p)
    }
    override fun setAlpha(a: Int) { p.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { p.colorFilter = f }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * A sheet's scrolling body that can also be pulled down to close. While its content is at the
 * top, a downward drag moves the whole sheet with the finger; let go far enough or fast enough
 * and it closes, otherwise it settles back. Scrolling up through long content works as usual.
 */
@SuppressLint("ClickableViewAccessibility")
class SheetScroll(ctx: Context, private val onDrag: (Float) -> Unit, private val onRelease: (Float, Float) -> Unit) : android.widget.ScrollView(ctx) {
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var dragging = false
    private var lastY = 0f
    private var lastT = 0L
    private var velocity = 0f

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; dragging = false; lastY = e.rawY; lastT = e.eventTime; velocity = 0f }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - startY; val dx = e.rawX - startX
                if (!dragging && scrollY == 0 && dy > slop && dy > kotlin.math.abs(dx) * 1.2f) {
                    dragging = true
                    startY = e.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return dragging || super.onInterceptTouchEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) {
            // Content too short to scroll, or a drag that began on empty space: take it here too
            if (e.actionMasked == MotionEvent.ACTION_MOVE && scrollY == 0) {
                val dy = e.rawY - startY
                if (dy > slop) { dragging = true; startY = e.rawY }
            }
            if (!dragging) return super.onTouchEvent(e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dt = (e.eventTime - lastT).coerceAtLeast(1)
                velocity = velocity * 0.4f + ((e.rawY - lastY) / dt * 1000f) * 0.6f
                lastY = e.rawY; lastT = e.eventTime
                onDrag((e.rawY - startY).coerceAtLeast(0f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onRelease((e.rawY - startY).coerceAtLeast(0f), velocity)
            }
        }
        return true
    }
}
