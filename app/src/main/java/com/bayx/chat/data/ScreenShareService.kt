package com.bayx.chat.data

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.bayx.chat.R

/**
 * Ekran paylaşımı sırasında çalışan kısa ömürlü ön plan servisi. Android 10+ ekran yakalamayı
 * yalnızca "mediaProjection" türünde bir ön plan servisi çalışırken başlatmaya izin verir.
 */
class ScreenShareService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = Notifier.screenShareNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(SCREEN_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(SCREEN_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val SCREEN_ID = 4120

        fun start(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ScreenShareService::class.java)) }
        }
    }
}
