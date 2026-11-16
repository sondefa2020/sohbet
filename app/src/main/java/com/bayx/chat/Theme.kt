package com.bayx.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Açık/koyu tema durumu. Açık modda renkler eskisiyle birebir aynıdır. */
object ThemeState {
    private const val PREF = "tayfa_tema"
    private const val KEY = "koyu"
    private var app: Context? = null

    var dark by mutableStateOf(false)
        private set

    /** Yedek tema "Cam Yeşil": yeşil vurgu, beyaz yüzeyler buzlu cam gibi yarı saydam. */
    var glass by mutableStateOf(false)
        private set

    fun init(context: Context) {
        app = context.applicationContext
        dark = prefs()?.getBoolean(KEY, false) ?: false
        glass = prefs()?.getBoolean("cam", false) ?: false
    }

    fun setGlass(value: Boolean) {
        glass = value
        prefs()?.edit()?.putBoolean("cam", value)?.apply()
    }

    fun set(value: Boolean) {
        dark = value
        prefs()?.edit()?.putBoolean(KEY, value)?.apply()
    }

    fun toggle() = set(!dark)

    private fun prefs() = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

// Yüzey renkleri (kart, alan). Açıkta beyaz; koyuda koyu gri tonları.
val CardBg: Color get() = when {
    ThemeState.glass && ThemeState.dark -> Color(0xFF16231B).copy(alpha = 0.72f)
    ThemeState.glass -> Color.White.copy(alpha = 0.62f)
    ThemeState.dark -> Color(0xFF221E1A)
    else -> Color.White
}
val FieldBg: Color get() = when {
    ThemeState.glass && ThemeState.dark -> Color(0xFF16231B).copy(alpha = 0.6f)
    ThemeState.glass -> Color.White.copy(alpha = 0.55f)
    ThemeState.dark -> Color(0xFF1B1815)
    else -> Color(0xFFFCFAF7)
}

/** Seçili sekme / çip zemini. */
val SelectedBg: Color get() = when {
    ThemeState.glass && ThemeState.dark -> Color(0xFF1E4A30)
    ThemeState.glass -> Color(0xFFD6F5DF)
    ThemeState.dark -> Color(0xFF3A2E24)
    else -> Color(0xFFF5EADD)
}
