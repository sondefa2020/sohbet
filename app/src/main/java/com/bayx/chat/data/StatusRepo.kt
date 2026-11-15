package com.bayx.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.MediaSource

/** Durum/hikâye: arkadaşların 24 saatlik paylaşımları. Fotoğraflar uçtan uca şifreli yüklenir. */
object StatusRepo {
    // ---------------------------------------------------------- durum / hikâye
    val statuses = MutableStateFlow<Api.StatusList?>(null)
    /** Durum listesi alınamadıysa kullanıcıya gösterilecek açıklama. */
    val statusError = MutableStateFlow<String?>(null)

    private fun statusMessage(e: Throwable): String = when {
        e is ApiException && e.code == "BULUNAMADI" ->
            "Sunucu Durum özelliğini henüz tanımıyor. Sunucuda kurulum komutunu (kur.sh) yeniden çalıştır."
        e is ApiException -> e.message ?: "İşlem tamamlanamadı."
        else -> "Sunucuya ulaşılamadı. İnternet bağlantını kontrol et."
    }

    suspend fun refreshStatuses() {
        val token = Bayx.accessToken ?: return
        runCatching { Api.statusList(token) }
            .onSuccess { statuses.value = it; statusError.value = null }
            .onFailure { statusError.value = statusMessage(it) }
    }

    /** Metin durumu paylaş. Hata varsa açıklama döner. */
    suspend fun addTextStatus(text: String, bg: String): String? {
        val token = Bayx.accessToken ?: return "Oturum yok."
        return runCatching { Api.statusAdd(token, text, bg) }
            .fold({ refreshStatuses(); null }, { statusMessage(it) })
    }

    /** Fotoğraf durumu: görseli sunucuya yükler, adresini durum olarak paylaşır. */
    suspend fun addImageStatus(jpeg: ByteArray, caption: String): Boolean = withContext(Dispatchers.IO) {
        val token = Bayx.accessToken ?: return@withContext false
        val c = Bayx.client ?: return@withContext false
        // Görsel yüklenmeden önce rastgele bir anahtarla şifrelenir (AES-GCM); sunucudaki dosya
        // tek başına okunamaz, anahtar yalnızca arkadaşlara durum listesiyle verilir.
        val keyBytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val sealed = runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, iv),
            )
            iv + cipher.doFinal(jpeg)
        }.getOrNull() ?: return@withContext false
        val mxc = runCatching { c.uploadMedia("application/octet-stream", sealed, null) }.getOrNull() ?: return@withContext false
        val key = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
        val result = runCatching { Api.statusAddImage(token, mxc, caption, key) }
        result.exceptionOrNull()?.let { statusError.value = statusMessage(it) }
        if (result.isSuccess) refreshStatuses()
        result.isSuccess
    }

    /** Durum görselini indirir (bellek önbelleği loadMedia içinde). */
    suspend fun loadStatusImage(mxc: String, key: String): ByteArray? = withContext(Dispatchers.IO) {
        val raw = runCatching { MediaSource.fromUrl(mxc).use { Bayx.loadMedia(it) } }.getOrNull() ?: return@withContext null
        if (key.isBlank()) return@withContext raw
        runCatching {
            val keyBytes = android.util.Base64.decode(key, android.util.Base64.NO_WRAP)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, raw, 0, 12),
            )
            cipher.doFinal(raw, 12, raw.size - 12)
        }.getOrNull()
    }

    suspend fun deleteStatus(id: String) {
        val token = Bayx.accessToken ?: return
        runCatching { Api.statusDelete(token, id) }
        refreshStatuses()
    }

    fun markStatusSeen(owner: String, id: String) {
        val token = Bayx.accessToken ?: return
        Bayx.scope.launch { runCatching { Api.statusSeen(token, owner, id) } }
    }

    /** Oturum kapanınca. */
    internal fun reset() {
        statuses.value = null
        statusError.value = null
    }
}
