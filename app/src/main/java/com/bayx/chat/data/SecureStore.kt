package com.bayx.chat.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Oturum bilgisini (erişim anahtarı, veritabanı parolası) Android Keystore'daki bir AES-GCM
 * anahtarıyla şifreleyip uygulamanın özel alanında saklar. Çıkışta hem dosya hem anahtar silinir.
 */
class SecureStore(context: Context) {
    private val file = File(context.filesDir, "oturum.bin")

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun save(text: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(text.toByteArray())
        file.writeBytes(byteArrayOf(iv.size.toByte()) + iv + encrypted)
    }

    fun load(): String? {
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val ivSize = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivSize)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes, 1 + ivSize, bytes.size - 1 - ivSize))
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        file.delete()
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "bayx_oturum_anahtari"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
