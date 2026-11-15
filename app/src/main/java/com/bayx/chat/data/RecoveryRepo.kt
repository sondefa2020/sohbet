package com.bayx.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.RecoveryStateListener
import org.matrix.rustcomponents.sdk.TaskHandle

/** Mesaj yedeği (anahtar kurtarma): şifreli mesaj anahtarlarının sunucudaki yedeği. */
object RecoveryRepo {
    // ------------------------------------------- mesaj yedeği (anahtar kurtarma)
    // Şifreli mesajların anahtarları sunucuda, kullanıcının yedek anahtarıyla şifreli saklanır.
    // Uygulama silinip yeniden kurulursa ya da telefon değişirse bu anahtarla eski mesajlar geri gelir.
    // Yedek anahtarı hiçbir yere kaydedilmez; yalnızca kullanıcıya bir kez gösterilir.

    val recoveryState = MutableStateFlow(RecoveryState.UNKNOWN)
    /** Yeni oluşturulan, kullanıcıya bir kez gösterilecek yedek anahtarı. */
    val newRecoveryKey = MutableStateFlow<String?>(null)
    /** Ayarlardan "anahtarı gir" penceresini açmak için. */
    val recoveryPrompt = MutableStateFlow(false)
    private var recoveryHandle: TaskHandle? = null

    private val noProgress = object : EnableRecoveryProgressListener {
        override fun onUpdate(status: EnableRecoveryProgress) = Unit
    }

    internal fun watchRecovery(c: Client) {
        runCatching {
            val enc = c.encryption()
            recoveryState.value = enc.recoveryState()
            recoveryHandle = enc.recoveryStateListener(object : RecoveryStateListener {
                override fun onUpdate(status: RecoveryState) {
                    recoveryState.value = status
                }
            })
        }
        // İlk kez: sunucuda yedek yoksa otomatik aç ve anahtarı kullanıcıya göster.
        Bayx.scope.launch {
            repeat(30) {
                if (recoveryState.value != RecoveryState.UNKNOWN) return@repeat
                delay(1000)
            }
            if (recoveryState.value != RecoveryState.DISABLED) return@launch
            val hasBackup = runCatching { c.encryption().backupExistsOnServer() }.getOrDefault(true)
            if (hasBackup) return@launch
            runCatching { c.encryption().enableRecovery(false, null, noProgress) }
                .onSuccess { newRecoveryKey.value = it }
        }
    }

    /** Yedek anahtarıyla eski mesajların anahtarlarını geri yükler. Hata varsa mesaj döner. */
    suspend fun recoverWithKey(key: String): String? = withContext(Dispatchers.IO) {
        val c = Bayx.client ?: return@withContext "Oturum yok."
        runCatching { c.encryption().recover(key.trim()) }
            .fold({ null }, { "Anahtar doğru değil. Boşlukları dahil, kaydettiğin gibi yaz." })
    }

    /** Yedeği açar ya da yeni anahtar üretir (eski anahtar geçersiz olur). */
    suspend fun createRecoveryKey(): String? = withContext(Dispatchers.IO) {
        val c = Bayx.client ?: return@withContext "Oturum yok."
        runCatching {
            if (recoveryState.value == RecoveryState.ENABLED || recoveryState.value == RecoveryState.INCOMPLETE) {
                c.encryption().disableRecovery()
            }
            c.encryption().enableRecovery(false, null, noProgress)
        }.fold({ newRecoveryKey.value = it; null }, { "Yedek anahtarı oluşturulamadı. İnternet bağlantını kontrol et." })
    }

    /** Oturum kapanınca. */
    internal fun reset() {
        runCatching { recoveryHandle?.cancel() }
        recoveryHandle = null
        recoveryState.value = RecoveryState.UNKNOWN
        newRecoveryKey.value = null
    }
}
