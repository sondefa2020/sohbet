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

    /** Arayan taraf: karşı taraf çevrimiçiyse "çalıyor" tonu, değilse "aranıyor" tonu. */
    fun ringback(online: Boolean) {
        stop()
        runCatching {
            tone = android.media.ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).also {
                it.startTone(
                    if (online) android.media.ToneGenerator.TONE_SUP_RINGTONE
                    else android.media.ToneGenerator.TONE_SUP_DIAL,
                )
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
        runCatching { tone?.stopTone(); tone?.release() }
        tone = null
        runCatching { ringtone?.stop() }
        ringtone = null
    }
}
