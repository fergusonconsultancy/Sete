package com.fergusonconsultancy.sete

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import java.io.InputStream
import kotlin.math.ln
import kotlin.math.pow

/**
 * What a photo can honestly say about a plant, worked out on the phone with nothing sent
 * anywhere: roughly how bright the spot is (from the camera's own exposure), and how green the
 * leaves look compared with the plant's last photo. Both are estimates; the light meter and a
 * finger in the soil beat them.
 */
object Look {
    data class Reading(val lux: Double?, val green: Double?)

    /**
     * The camera picked its exposure to suit the scene, so its settings say how bright the scene
     * was: EV100 = log2(N² / t) − log2(ISO / 100), and illuminance ≈ 2.5 × 2^EV100 lux
     * (the incident-light meter constant of 250 over 100). Indoors, a rough guide only.
     */
    @Suppress("DEPRECATION")
    fun luxFromExif(stream: InputStream): Double? = runCatching {
        val e = ExifInterface(stream)
        val t = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        val n = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
        val iso = e.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).toDouble()
        if (t <= 0 || n <= 0 || iso <= 0) return@runCatching null
        val ev100 = ln(n * n / t) / ln(2.0) - ln(iso / 100.0) / ln(2.0)
        2.5 * 2.0.pow(ev100)
    }.getOrNull()

    /**
     * Of the leaf-coloured pixels in the middle of the frame, the share that is green rather
     * than yellow. Walls, floors and pots are mostly left out by needing real colour; brown
     * isn't counted at all, because brick, oak and terracotta would swamp it.
     */
    fun green(b: Bitmap): Double? {
        val x0 = b.width / 6; val x1 = b.width - x0
        val y0 = b.height / 8; val y1 = b.height - b.height / 4
        val hsv = FloatArray(3)
        var green = 0; var yellow = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                Color.colorToHSV(b.getPixel(x, y), hsv)
                val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
                if (h in 68f..170f && s > 0.18f && v > 0.12f) green++
                else if (h in 42f..68f && s > 0.35f && v > 0.4f) yellow++
                x += 4
            }
            y += 4
        }
        val leaf = green + yellow
        // Too little leaf in frame to judge
        if (leaf < 120) return null
        return green.toDouble() / leaf
    }

    /** A plain-words comparison with the plant's previous photo. */
    fun trend(reads: List<Read>): String? {
        if (reads.size < 2) return null
        val now = reads[0]; val before = reads[1]
        val d = (now.green - before.green) * 100
        val since = Rhythm.ago(before.t)
        return when {
            d <= -8 -> "Yellower than its photo from $since. Feel the soil before watering: too much water is the usual cause."
            d >= 8 -> "Greener than its photo from $since. Whatever you're doing, it likes it."
            else -> "Much the same colour as its photo from $since."
        }
    }
}
