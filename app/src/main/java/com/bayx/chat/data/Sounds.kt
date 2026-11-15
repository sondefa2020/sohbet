package com.bayx.chat.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import com.bayx.chat.R

/** Uygulama açıkken çalınan kısa sesler ve titreşimler. */
object Sounds {
    enum class Kind { INCOMING, OUTGOING, NUDGE }

    private var pool: SoundPool? = null
    private val ids = HashMap<Kind, Int>()
    private lateinit var app: Context

    fun init(context: Context) {
        if (pool != null) return
        app = context.applicationContext
        val soundPool = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        ids[Kind.INCOMING] = soundPool.load(app, R.raw.tayfa_gelen, 1)
        ids[Kind.OUTGOING] = soundPool.load(app, R.raw.tayfa_giden, 1)
        ids[Kind.NUDGE] = soundPool.load(app, R.raw.tayfa_titresim, 1)
        pool = soundPool
    }

    /** Telefon sessiz ya da titreşim modundaysa ses çalmaz. */
    fun play(kind: Kind) {
        val soundPool = pool ?: return
        val id = ids[kind] ?: return
        val am = app.getSystemService(AudioManager::class.java) ?: return
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION && kind != Kind.NUDGE) return
        val volume = when (kind) {
            Kind.OUTGOING -> 0.6f
            else -> 1f
        }
        soundPool.play(id, volume, volume, 1, 0, 1f)
    }

    /** Titreşim: telefon sessizde olsa bile titrer. */
    fun vibrate(pattern: LongArray) {
        runCatching { app.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    /** MSN tarzı "titreşim" efekti: sert, kesik kesik titreşim ve vızıltı sesi. */
    fun nudge() {
        play(Kind.NUDGE)
        vibrate(longArrayOf(0, 90, 50, 90, 50, 90, 50, 90, 50, 260))
    }

    /** Gelen mesaj: kısa ses ve hafif titreşim. */
    fun incoming() {
        play(Kind.INCOMING)
        vibrate(longArrayOf(0, 35, 60, 35))
    }
}

/** Arama sesleri: arayanda geri çalma tonu, arananda zil sesi. */
object CallTones {
    private var tone: android.media.ToneGenerator? = null
    private var ringtone: android.media.Ringtone? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var beeper: Runnable? = null
    private var currentRinging: Boolean? = null

    /**
     * Arayan taraf: karşı telefon çalıyorsa klasik "çalıyor" tonu; henüz ulaşılamadıysa
     * (karşı tarafın interneti yok) iki saniyede bir kısa "aranıyor" bipi.
     */
    fun ringback(ringing: Boolean) {
        if (currentRinging == ringing && (tone != null)) return
        stop()
        currentRinging = ringing
        runCatching {
            val gen = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
            tone = gen
            if (ringing) {
                gen.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE)
            } else {
                val r = object : Runnable {
                    override fun run() {
                        runCatching { tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 180) }
                        handler.postDelayed(this, 2_000)
                    }
                }
                beeper = r
                handler.post(r)
            }
        }
    }

    /** Aranan taraf (uygulama açıkken): telefonun zil sesi. Sessiz/titreşim modunda çalmaz. */
    fun incomingRing(context: Context) {
        stop()
        val am = context.getSystemService(AudioManager::class.java)
        if (am?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        runCatching {
            val uri = android.media.RingtoneManager.getActualDefaultRingtoneUri(context, android.media.RingtoneManager.TYPE_RINGTONE)
                ?: return
            ringtone = android.media.RingtoneManager.getRingtone(context, uri)?.also { r ->
                if (android.os.Build.VERSION.SDK_INT >= 28) r.isLooping = true
                r.play()
            }
        }
    }

    fun stop() {
        beeper?.let { handler.removeCallbacks(it) }
        beeper = null
        currentRinging = null
        runCatching { tone?.stopTone(); tone?.release() }
        tone = null
        runCatching { ringtone?.stop() }
        ringtone = null
    }
}
