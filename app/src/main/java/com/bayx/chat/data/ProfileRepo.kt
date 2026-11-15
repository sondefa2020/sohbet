package com.bayx.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.MediaSource

/** Profil: görünen ad, profil fotoğrafı ve web oturumu onayı. */
object ProfileRepo {
    val myAvatarUrl = MutableStateFlow<String?>(null)

    // ------------------------------------------------------------- profil

    suspend fun setDisplayName(name: String): Boolean = withContext(Dispatchers.IO) {
        val c = Bayx.client ?: return@withContext false
        val ok = runCatching { c.setDisplayName(name) }.isSuccess
        if (ok) {
            val current = Bayx.auth.value as? AuthState.LoggedIn
            if (current != null) Bayx.auth.value = current.copy(displayName = name)
            Bayx.store.load()?.let { saved -> runCatching { Bayx.store.save(JSONObject(saved).put("displayName", name).toString()) } }
        }
        ok
    }

    suspend fun setAvatar(jpeg: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val c = Bayx.client ?: return@withContext false
        val ok = runCatching { c.uploadAvatar("image/jpeg", jpeg) }.isSuccess
        if (ok) myAvatarUrl.value = runCatching { c.avatarUrl() }.getOrNull()
        ok
    }

    suspend fun refreshMyAvatar() {
        val c = Bayx.client ?: return
        myAvatarUrl.value = runCatching { c.avatarUrl() }.getOrNull()
    }

    private val avatarCache = LruCache<String, Bitmap>(60)

    /** Profil fotoğrafını (mxc adresi) indirir; yalnızca bellekte önbelleğe alır. */
    suspend fun loadAvatar(mxc: String): Bitmap? = withContext(Dispatchers.IO) {
        avatarCache.get(mxc)?.let { return@withContext it }
        val c = Bayx.client ?: return@withContext null
        val bytes = runCatching {
            MediaSource.fromUrl(mxc).use { source -> c.getMediaThumbnail(source, 256uL, 256uL) }
        }.getOrNull() ?: return@withContext null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        avatarCache.put(mxc, bitmap)
        bitmap
    }

    /** Web oturumunu onaylar (QR pairing_id ya da 6 haneli kod). Hata varsa mesaj döner. */
    suspend fun approveWeb(pairingId: String?, code: String?): String? {
        val token = Bayx.accessToken ?: return "Oturum yok."
        return try {
            Api.webApprove(token, pairingId, code)
            null
        } catch (e: ApiException) {
            e.message
        } catch (e: Exception) {
            "Sunucuya ulaşılamadı."
        }
    }

    /** Oturum kapanınca. */
    internal fun reset() {
        myAvatarUrl.value = null
        avatarCache.evictAll()
    }
}
