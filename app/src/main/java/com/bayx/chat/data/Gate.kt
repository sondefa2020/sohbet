package com.bayx.chat.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.biometric.BiometricManager
import kotlinx.coroutines.flow.MutableStateFlow

/** Uygulama kilidi: Tayfa'ya bir süre sonra dönüşte parmak izi ya da ekran kilidi ister. */
object Gate {
    val locked = MutableStateFlow(false)

    /** İlk kurulumda kullanıcı sözleşmesi onaylandı mı. */
    val onboardingAccepted = MutableStateFlow(true)

    private const val LOCK_AFTER_MS = 30_000L
    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private var backgroundAt = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("tayfa_giris", Context.MODE_PRIVATE)
        locked.value = lockEnabled && lockAvailable()
        onboardingAccepted.value = prefs.getBoolean("onay", false)
    }

    fun acceptOnboarding() {
        prefs.edit().putBoolean("onay", true).apply()
        onboardingAccepted.value = true
    }

    var lockEnabled: Boolean
        get() = prefs.getBoolean("kilit", false)
        set(value) {
            prefs.edit().putBoolean("kilit", value).apply()
            if (!value) locked.value = false
        }

    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

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
}
