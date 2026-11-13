package com.bayx.chat.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.biometric.BiometricManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Şifresiz giriş ve uygulama kilidi.
 *
 * Kullanıcı hiçbir zaman şifre görmez. Sunucu, kayıt/kurtarma/giriş kodu doğrulandığında
 * 2 dakikalık tek seferlik bir şifre verir; uygulama bununla Matrix oturumunu açar ve hemen
 * ardından sunucudan şifreyi yeniden bilinmeyen bir değere çevirmesini ister.
 *
 * Oturum anahtarı Android Keystore ile şifreli saklanır (SecureStore); uygulama kilidi açıksa
 * uygulamaya dönüşte parmak izi ya da ekran kilidi istenir.
 */
object Gate {
    sealed class Link {
        data class Invite(val code: String) : Link()
        data class Login(val code: String) : Link()
    }

    /** Bildirimden ya da tarayıcıdan gelen davet / giriş bağlantısı. */
    val link = MutableStateFlow<Link?>(null)

    /** Bir kez gösterilecek kurtarma kodu (hiçbir yere kaydedilmez). */
    val recoveryToShow = MutableStateFlow<String?>(null)

    /** Onay bekleyen kayıt isteği (yalnızca rastgele bilet; kişisel bilgi değil). */
    val pendingUser = MutableStateFlow<String?>(null)

    val locked = MutableStateFlow(false)

    private const val LOCK_AFTER_MS = 30_000L
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private var backgroundAt = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("tayfa_giris", Context.MODE_PRIVATE)
        pendingUser.value = prefs.getString("bekleyen_kullanici", null)
        // Soğuk açılışta kayıtlı oturum varsa kilitli başla.
        locked.value = lockEnabled && lockAvailable()
    }

    // ------------------------------------------------------------ uygulama kilidi

    var lockEnabled: Boolean
        get() = prefs.getBoolean("kilit", true)
        set(value) {
            prefs.edit().putBoolean("kilit", value).apply()
            if (!value) locked.value = false
        }

    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Telefonda ekran kilidi ya da parmak izi tanımlı mı? */
    fun lockAvailable(): Boolean =
        BiometricManager.from(appContext).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun onBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        if (backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt > LOCK_AFTER_MS &&
            lockEnabled && lockAvailable() && Bayx.auth.value is AuthState.LoggedIn
        ) {
            locked.value = true
        }
        backgroundAt = 0
    }

    // -------------------------------------------------------------- kayıt bileti

    val pendingTicket: String?
        get() = prefs.getString("bilet", null)

    fun savePending(ticket: String, username: String) {
        prefs.edit().putString("bilet", ticket).putString("bekleyen_kullanici", username).apply()
        pendingUser.value = username
    }

    fun clearPending() {
        prefs.edit().remove("bilet").remove("bekleyen_kullanici").apply()
        pendingUser.value = null
    }

    // ------------------------------------------------------------------- giriş

    /** Sunucunun verdiği tek seferlik şifreyle oturum açar. Hata varsa mesajı döndürür. */
    suspend fun enter(reply: Api.AuthReply): String? {
        if (reply.status != "ok" || reply.otp.isEmpty()) return "Giriş yapılamadı."
        val error = Bayx.login(reply.username, reply.otp)
        if (error == null) {
            locked.value = false
            clearPending()
            // Tek seferlik şifreyi hemen geçersiz kıl (sunucu zaten 2 dakikada kendisi de yapar).
            Bayx.accessToken?.let { token -> runCatching { Api.seal(token) } }
            reply.recoveryCode?.let { recoveryToShow.value = it }
        }
        return error
    }
}
