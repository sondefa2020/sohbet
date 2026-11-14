package com.bayx.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Uygulama dili. Türkçe temel; İngilizce ve Arapça çevirileri sözlükten gelir. */
enum class AppLang(val code: String, val native: String) {
    TR("tr", "Türkçe"),
    EN("en", "English"),
    AR("ar", "العربية"),
}

/**
 * Basit yerelleştirme. Çağrı yeri Türkçe metni verir: t("Sohbetler").
 * Seçili dil Türkçe ise ya da çeviri yoksa Türkçe metin aynen döner (güvenli yedek).
 */
object L {
    private const val PREF = "tayfa_dil"
    private const val KEY = "dil"
    private var app: Context? = null

    var lang by mutableStateOf(AppLang.TR)
        private set

    val rtl get() = lang == AppLang.AR

    fun init(context: Context) {
        app = context.applicationContext
        val code = prefs()?.getString(KEY, "tr") ?: "tr"
        lang = AppLang.entries.firstOrNull { it.code == code } ?: AppLang.TR
    }

    fun set(value: AppLang) {
        lang = value
        prefs()?.edit()?.putString(KEY, value.code)?.apply()
    }

    private fun prefs() = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun prefs0() = prefs()

    /** Türkçe metni seçili dile çevirir; çeviri yoksa Türkçe döner. */
    fun t(tr: String): String {
        if (lang == AppLang.TR) return tr
        val row = Dict.map[tr] ?: return tr
        return when (lang) {
            AppLang.EN -> row.first.ifEmpty { tr }
            AppLang.AR -> row.second.ifEmpty { tr }
            AppLang.TR -> tr
        }
    }
}

/** Kısa yardımcı: t("..."). */
fun t(tr: String): String = L.t(tr)

/** Değişkenli metin: tf("%s artık kişilerinde.", ad). Kalıp çevrilir, sonra değerler yerleştirilir. */
fun tf(tr: String, vararg args: Any?): String = runCatching { String.format(L.t(tr), *args) }.getOrElse { tr }
