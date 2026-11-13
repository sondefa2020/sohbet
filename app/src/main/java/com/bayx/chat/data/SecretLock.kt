package com.bayx.chat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Gizli sohbetler bölümünün PIN kilidi. PIN'in kendisi saklanmaz; yalnızca tuzlanmış
 * özeti cihazda tutulur. Uygulama arka plana geçince bölüm yeniden kilitlenir;
 * çıkış yapılınca PIN de silinir.
 */
object SecretLock {
    private const val PREFS = "tayfa_gizli"
    private lateinit var app: Context

    val unlocked = MutableStateFlow(false)

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPin(): Boolean = prefs().contains("hash")

    private fun digest(salt: String, pin: String): String {
        var bytes = (salt + pin).toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        repeat(20_000) { bytes = md.digest(bytes) }
        return bytes.joinToString("") { "%02x".format(java.util.Locale.US, it) }
    }

    fun setPin(pin: String) {
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = saltBytes.joinToString("") { "%02x".format(java.util.Locale.US, it) }
        prefs().edit().putString("salt", salt).putString("hash", digest(salt, pin)).putInt("fails", 0).apply()
        unlocked.value = true
    }

    /** Doğruysa bölümü açar. Üst üste 5 yanlış denemeden sonra 30 saniye bekletir. */
    fun tryUnlock(pin: String): Result {
        val p = prefs()
        val lockedUntil = p.getLong("until", 0L)
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return Result.Wait(((lockedUntil - now) / 1000).toInt() + 1)
        val salt = p.getString("salt", null) ?: return Result.Wrong
        return if (digest(salt, pin) == p.getString("hash", null)) {
            p.edit().putInt("fails", 0).apply()
            unlocked.value = true
            Result.Ok
        } else {
            val fails = p.getInt("fails", 0) + 1
            val edit = p.edit().putInt("fails", fails)
            if (fails >= 5) edit.putLong("until", now + 30_000).putInt("fails", 0)
            edit.apply()
            Result.Wrong
        }
    }

    fun lock() {
        unlocked.value = false
    }

    fun clear() {
        if (::app.isInitialized) prefs().edit().clear().apply()
        unlocked.value = false
    }

    sealed interface Result {
        data object Ok : Result
        data object Wrong : Result
        data class Wait(val seconds: Int) : Result
    }
}
