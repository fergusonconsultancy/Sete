package com.fergusonconsultancy.sete

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/**
 * Sete's four sounds, all synthesised (tools/sounds.py): a drop landing, water filling a pot
 * (its pitch climbs as the pot fills), a finger in damp soil, and a two-note chime for a round
 * finished. They follow the phone: silent or vibrate mode means no sound.
 */
object Sound {
    enum class Cue { PLIP, GURGLE, TAP, CHIME }

    private var pool: SoundPool? = null
    private val ids = HashMap<Cue, Int>()
    private val loaded = HashSet<Int>()
    private lateinit var audio: AudioManager
    private val main = Handler(Looper.getMainLooper())

    fun init(c: Context) {
        if (pool != null) return
        audio = c.getSystemService(AudioManager::class.java)
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        p.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
        ids[Cue.PLIP] = p.load(c, R.raw.plip, 1)
        ids[Cue.GURGLE] = p.load(c, R.raw.gurgle, 1)
        ids[Cue.TAP] = p.load(c, R.raw.tap, 1)
        ids[Cue.CHIME] = p.load(c, R.raw.chime, 1)
        pool = p
    }

    private fun allowed(): Boolean =
        Store.sound && ::audio.isInitialized && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL

    /** [vary] nudges the pitch so a whole round of drops never sounds like a loop. */
    fun play(cue: Cue, delayMs: Long = 0, volume: Float = 1f, vary: Boolean = true) {
        if (!allowed()) return
        val p = pool ?: return
        val id = ids[cue] ?: return
        if (id !in loaded) return
        val rate = if (vary) Random.nextDouble(0.92, 1.1).toFloat() else 1f
        val go = Runnable { p.play(id, volume, volume, 1, 0, rate) }
        if (delayMs > 0) main.postDelayed(go, delayMs) else go.run()
    }

    /** Watering: the drop lands, then the pot fills. */
    fun watered() { play(Cue.PLIP); play(Cue.GURGLE, delayMs = 380, volume = 0.8f, vary = false) }
}
