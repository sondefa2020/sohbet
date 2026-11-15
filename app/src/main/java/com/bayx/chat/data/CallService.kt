package com.bayx.chat.data

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Görüşme sürerken çalışan ön plan servisi. Android, uygulama arka plana geçince mikrofonu ve
 * kamerayı keser; bu servis görüşme süresince ikisinin de açık kalmasını sağlar ve bildirim
 * çubuğunda "Görüşme sürüyor" gösterir.
 */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        val call = Calls.state.value
        val notification = Notifier.ongoingCallNotification(this, call?.peerName.orEmpty(), video)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (video && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                startForeground(ID, notification, type)
            } else {
                startForeground(ID, notification)
            }
        } catch (e: Exception) {
            // İzin yoksa ya da sistem izin vermediyse görüşme yine sürer; yalnızca arka planda kısıtlanır.
            stopSelf()
            return START_NOT_STICKY
        }
        if (call == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val ID = 4130
        private const val EXTRA_VIDEO = "video"

        fun start(context: Context, video: Boolean) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val intent = Intent(context, CallService::class.java).putExtra(EXTRA_VIDEO, video)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallService::class.java)) }
        }
    }
}

/** Bildirimlerdeki "Reddet" ve "Kapat" düğmeleri. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REJECT -> Calls.rejectFromNotification(intent.getStringExtra(EXTRA_FROM), intent.getStringExtra(EXTRA_CALL_ID))
            ACTION_HANGUP -> Calls.hangup()
        }
    }

    companion object {
        const val ACTION_REJECT = "com.bayx.chat.ARAMA_REDDET"
        const val ACTION_HANGUP = "com.bayx.chat.ARAMA_KAPAT"
        const val EXTRA_FROM = "from"
        const val EXTRA_CALL_ID = "call_id"
    }
}
