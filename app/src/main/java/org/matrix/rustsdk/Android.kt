package org.matrix.rustsdk

import android.content.Context

/**
 * Matrix Rust SDK'ya Android bağlamını verir. SDK, TLS sertifikalarını Android'in sistem
 * güven deposuyla doğrulamak için buna ihtiyaç duyar. Paket ve sınıf adı SDK'nın yerel
 * kütüphanesindeki JNI işleviyle eşleşmek zorundadır; değiştirilmemeli.
 */
object Android {
    init {
        System.loadLibrary("matrix_sdk_ffi")
    }

    @JvmStatic
    external fun init(context: Context)
}
