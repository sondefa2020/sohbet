package com.bayx.chat

import android.app.Application
import android.util.Log
import com.bayx.chat.data.Bayx

class BayxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Matrix SDK'nın TLS sertifika doğrulaması Android bağlamına ihtiyaç duyar.
        try {
            org.matrix.rustsdk.Android.init(this)
        } catch (e: Throwable) {
            Log.e("Bayx", "Matrix SDK platform başlatılamadı", e)
            Bayx.platformError = e.toString()
        }
        Bayx.init(this)
        com.bayx.chat.data.Gate.init(this)
        Wallpaper.init(this)
        com.bayx.chat.data.CallLog.init(this)
        com.bayx.chat.data.ChatPrefs.init(this)
    }
}
