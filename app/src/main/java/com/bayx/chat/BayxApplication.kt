package com.bayx.chat

import android.app.Application
import android.content.Context
import android.util.Log
import com.bayx.chat.data.Bayx
import java.lang.reflect.Modifier

class BayxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initRustPlatform(this)
        Bayx.init(this)
    }

    /**
     * Matrix SDK'nın TLS sertifika doğrulaması Android bağlamına ihtiyaç duyar.
     * Sınıf SDK paketinin içinde geldiği için yansıma ile çağrılır.
     */
    private fun initRustPlatform(context: Context) {
        try {
            val cls = Class.forName("org.matrix.rustsdk.Android")
            val method = cls.getMethod("init", Context::class.java)
            val target = if (Modifier.isStatic(method.modifiers)) null else cls.getField("INSTANCE").get(null)
            method.invoke(target, context)
        } catch (e: Throwable) {
            Log.e("Bayx", "Matrix SDK platform başlatılamadı", e)
        }
    }
}
