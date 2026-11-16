package com.bayx.chat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar

/**
 * Kolaylık ayarları (cihazda tutulur): sessiz saatler, büyük yazı, hazır cevaplar.
 */
object Comfort {
    private const val PREFS = "tayfa_kolaylik"
    private lateinit var app: Context

    /** Sessiz saatler açık mı; başlangıç ve bitiş gün içindeki dakika olarak (ör. 23:00 = 1380). */
    val quietEnabled = MutableStateFlow(false)
    val quietFrom = MutableStateFlow(23 * 60)
    val quietTo = MutableStateFlow(7 * 60)

    /** Yazı boyutu çarpanı: 1.0 normal, 1.15 büyük, 1.3 çok büyük. */
    val textScale = MutableStateFlow(1f)

    val quickReplies = MutableStateFlow(DEFAULT_REPLIES)

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val p = prefs()
        quietEnabled.value = p.getBoolean("sessiz", false)
        quietFrom.value = p.getInt("sessiz_bas", 23 * 60)
        quietTo.value = p.getInt("sessiz_bit", 7 * 60)
        textScale.value = p.getFloat("yazi", 1f)
        quickReplies.value = p.getString("hazir", null)?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(5)
            ?.ifEmpty { null } ?: DEFAULT_REPLIES
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setQuiet(enabled: Boolean, from: Int, to: Int) {
        quietEnabled.value = enabled
        quietFrom.value = from
        quietTo.value = to
        prefs().edit().putBoolean("sessiz", enabled).putInt("sessiz_bas", from).putInt("sessiz_bit", to).apply()
    }

    fun setTextScale(scale: Float) {
        textScale.value = scale
        prefs().edit().putFloat("yazi", scale).apply()
    }

    fun setQuickReplies(list: List<String>) {
        val clean = list.map { it.trim() }.filter { it.isNotEmpty() }.take(5)
        quickReplies.value = clean.ifEmpty { DEFAULT_REPLIES }
        prefs().edit().putString("hazir", clean.joinToString("\n")).apply()
    }

    /** Şu an sessiz saatlerin içinde miyiz? (gece yarısını aşan aralıklar desteklenir) */
    fun inQuietHours(now: Calendar = Calendar.getInstance()): Boolean {
        if (!::app.isInitialized || !quietEnabled.value) return false
        val m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val from = quietFrom.value
        val to = quietTo.value
        return if (from <= to) m in from until to else m >= from || m < to
    }

    fun label(minutes: Int): String = "%02d:%02d".format(java.util.Locale.US, minutes / 60, minutes % 60)

    val DEFAULT_REPLIES = listOf("Tamam 👍", "Müsait değilim, sonra yazarım", "Geliyorum", "Arıyorum", "Teşekkürler 🙏")
}
