package com.bayx.chat.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bayx.chat.MainActivity
import com.bayx.chat.R

/**
 * Bildirimler. Mesaj içerikleri hiçbir zaman bildirime yazılmaz; yalnızca sohbetin adı
 * (cihazda biliniyorsa) ve yeni mesaj olduğu gösterilir.
 */
object Notifier {
    private const val CH_MESSAGES = "mesajlar_v2"
    private const val CH_CALLS = "aramalar"
    private const val CALL_NOTIFICATION_ID = 1001
    const val EXTRA_ROOM_ID = "tayfa_room_id"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Kanal ayarları oluşturulduktan sonra değişmediği için yeni ses yeni kanal ister.
        manager.deleteNotificationChannel("mesajlar")
        manager.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Mesajlar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Yeni mesaj bildirimleri"
                setSound(
                    android.net.Uri.parse("android.resource://${context.packageName}/${R.raw.tayfa_gelen}"),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 35, 60, 35)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_CALLS, "Aramalar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Gelen sesli ve görüntülü aramalar"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            },
        )
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openIntent(context: Context, roomId: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (roomId != null) putExtra(EXTRA_ROOM_ID, roomId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun showMessage(context: Context, roomId: String, roomName: String?, unread: Int) {
        if (!canNotify(context)) return
        val id = roomId.hashCode()
        val text = if (unread > 1) "$unread yeni mesaj" else "Yeni mesajın var"
        val notification = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setContentTitle(roomName ?: "Tayfa")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CH_MESSAGES)
                    .setSmallIcon(R.drawable.ic_stat_tayfa)
                    .setContentTitle("Tayfa")
                    .setContentText("Yeni mesaj")
                    .build(),
            )
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, roomId, id))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun showIncomingCall(context: Context, callerName: String, video: Boolean) {
        if (!canNotify(context)) return
        val open = openIntent(context, null, CALL_NOTIFICATION_ID)
        val notification = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setContentTitle(callerName.ifBlank { "Tayfa" })
            .setContentText(if (video) "Görüntülü arama geliyor" else "Sesli arama geliyor")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(45_000)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(CALL_NOTIFICATION_ID, notification) }
    }

    fun cancelCall(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID) }
    }

    fun cancelRoom(context: Context, roomId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(roomId.hashCode()) }
    }

    fun cancelAll(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
    }
}
