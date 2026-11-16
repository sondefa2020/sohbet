package com.bayx.chat.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Yeni telefona taşıma: mesaj yedeğinin anahtarı eski telefondan yeni telefona QR ile,
 * uçtan uca şifreli aktarılır. Sunucu yalnızca şifreli veriyi birkaç dakika tutar.
 *
 * Yeni telefon bir P-256 anahtar çifti üretir; açık anahtarı QR'da gösterir. Eski telefon
 * kendi geçici anahtarıyla ortak sır (ECDH) çıkarır, yedek anahtarını AES-GCM ile şifreler.
 */
object Transfer {
    const val QR_PREFIX = "tayfa-aktar:"

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    private fun newPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun publicFrom(text: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(text)))

    private fun sharedKey(pair: KeyPair, other: PublicKey, context: String): SecretKeySpec {
        val agreement = KeyAgreement.getInstance("ECDH").apply { init(pair.private); doPhase(other, true) }
        val secret = agreement.generateSecret()
        val key = MessageDigest.getInstance("SHA-256").digest(secret + context.toByteArray())
        return SecretKeySpec(key, "AES")
    }

    // ---------------------------------------------------------------- yeni telefon
    class Receiver internal constructor(val id: String, private val pair: KeyPair) {
        val qr: String get() = QR_PREFIX + id

        /** Eski telefon gönderene kadar bekler; yedek anahtarını döndürür (süre dolarsa null). */
        suspend fun await(timeoutMs: Long = 5 * 60_000L): String? = withContext(Dispatchers.IO) {
            val token = Bayx.accessToken ?: return@withContext null
            val until = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < until) {
                val (status, payload) = runCatching { Api.transferPoll(token, id) }.getOrDefault("pending" to null)
                if (status == "gone") return@withContext null
                if (status == "ready" && payload != null) {
                    return@withContext runCatching {
                        val key = sharedKey(pair, publicFrom(payload.pub), "tayfa-aktar-v1:$id")
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, unb64(payload.iv)))
                        String(cipher.doFinal(unb64(payload.data)))
                    }.getOrNull()
                }
                delay(2000)
            }
            null
        }
    }

    suspend fun startReceiving(): Receiver? = withContext(Dispatchers.IO) {
        val token = Bayx.accessToken ?: return@withContext null
        val pair = newPair()
        val id = runCatching { Api.transferStart(token, b64(pair.public.encoded)) }.getOrNull() ?: return@withContext null
        Receiver(id, pair)
    }

    // ---------------------------------------------------------------- eski telefon
    /** QR'dan okunan kodla yedek anahtarını yeni telefona gönderir. Hata varsa mesaj döner. */
    suspend fun send(qrText: String, recoveryKey: String): String? = withContext(Dispatchers.IO) {
        val token = Bayx.accessToken ?: return@withContext "Oturum yok."
        val id = qrText.removePrefix(QR_PREFIX).trim()
        val theirs = try {
            Api.transferInfo(token, id)
        } catch (e: ApiException) {
            return@withContext e.message ?: "Kod okunamadı."
        }
        runCatching {
            val pair = newPair()
            val key = sharedKey(pair, publicFrom(theirs), "tayfa-aktar-v1:$id")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val data = cipher.doFinal(recoveryKey.toByteArray())
            Api.transferSend(token, id, TransferPayload(b64(pair.public.encoded), b64(iv), b64(data)))
        }.fold({ null }, { (it as? ApiException)?.message ?: "Gönderilemedi. İnternet bağlantını kontrol et." })
    }
}
