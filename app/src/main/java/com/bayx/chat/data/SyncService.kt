package com.bayx.chat.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Uygulama kapatılsa (arka plandan atılsa) bile süreci ayakta tutan kalıcı ön plan servisi.
 * Böylece Matrix senkronu ve arama yoklaması çalışmaya devam eder; mesaj ve aramalar
 * anında gelir. Sessiz, en düşük öncelikli bir bildirim gösterir.
 */
class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifier.backgroundNotification(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(BG_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(BG_ID, notification)
        }
        // Oturum kapalıysa servise gerek yok.
        if (Bayx.auth.value is AuthState.LoggedOut) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Süreç ayaktayken aramaları da almak için yoklamayı canlı tut.
        runCatching { Calls.ensurePolling() }
        // Sistem süreci öldürürse yeniden başlat.
        return START_STICKY
    }

    companion object {
        private const val BG_ID = 7300

        /** Giriş yapılmışsa servisi başlatır. */
        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, SyncService::class.java)) }
        }
    }
}
