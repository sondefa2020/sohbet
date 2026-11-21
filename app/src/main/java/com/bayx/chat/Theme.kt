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

    fun init(context: Context) {
        app = context.applicationContext
        dark = prefs()?.getBoolean(KEY, false) ?: false
    }

    fun set(value: Boolean) {
        dark = value
        prefs()?.edit()?.putBoolean(KEY, value)?.apply()
    }

    fun toggle() = set(!dark)

    private fun prefs() = app?.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

// Yüzey renkleri (kart, alan). Açıkta beyaz; koyuda koyu gri tonları.
val CardBg: Color get() = if (ThemeState.dark) Color(0xFF161618) else Color.White
val FieldBg: Color get() = if (ThemeState.dark) Color(0xFF1E1E21) else Color(0xFFF6F2ED)
