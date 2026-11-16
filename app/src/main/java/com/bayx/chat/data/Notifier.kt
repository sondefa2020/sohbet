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
    // Kanal ayarları (ses, önem) oluşturulduktan sonra değiştirilemediği için sürüm eklenir:
    // eski kanalda ses kapanmış/yanlış kalmışsa yeni kanal sesli başlar.
    private const val CH_MESSAGES = "mesajlar_v3"
    private const val CH_CALLS = "aramalar_v2"
    private const val CH_ONGOING = "gorusme"
    private const val CH_SCREEN = "ekran_paylasimi"
    private const val CH_BACKGROUND = "arka_plan"
    private const val CALL_NOTIFICATION_ID = 1001
    const val EXTRA_ROOM_ID = "tayfa_room_id"
    const val EXTRA_ACCEPT_CALL = "tayfa_accept_call"

    /** Aynı sohbet için kısa sürede iki kez ses çalmasın (senkron + Firebase aynı mesajı bildirebilir). */
    private val lastAlert = HashMap<String, Long>()

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Kanal ayarları oluşturulduktan sonra değişmediği için yeni ses yeni kanal ister.
        for (old in listOf("mesajlar", "mesajlar_v2", "aramalar")) manager.deleteNotificationChannel(old)
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
                description = com.bayx.chat.t("Gelen sesli ve görüntülü aramalar")
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 500, 700, 500)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_ONGOING, com.bayx.chat.t("Süren görüşme"), NotificationManager.IMPORTANCE_LOW).apply {
                description = com.bayx.chat.t("Görüşme sürerken gösterilir")
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_SCREEN, com.bayx.chat.t("Ekran paylaşımı"), NotificationManager.IMPORTANCE_LOW).apply {
                description = com.bayx.chat.t("Ekran paylaşımı sürerken gösterilir")
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_BACKGROUND, com.bayx.chat.t("Arka planda çalışma"), NotificationManager.IMPORTANCE_MIN).apply {
                description = com.bayx.chat.t("Mesaj ve aramaların anında gelmesi için Tayfa arka planda açık kalır")
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /** Uygulama arka planda çalışırken gösterilen sessiz, kalıcı bildirim. */
    fun backgroundNotification(context: Context): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createChannels(context)
        val open = android.content.Intent(context, Class.forName("com.bayx.chat.MainActivity")).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = android.app.PendingIntent.getActivity(
            context, 7, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CH_BACKGROUND)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        }
        return builder
            .setContentTitle(com.bayx.chat.t("Tayfa çalışıyor"))
            .setContentText(com.bayx.chat.t("Mesaj ve aramaların anında gelsin diye arka planda açık"))
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /** Ekran paylaşımı ön plan servisi için sessiz, düşük öncelikli bildirim. */
    private const val CONTACT_ID = 5200

    /** Arkadaşlık isteği bildirimi. */
    fun showContactRequest(context: Context, name: String) {
        if (!canNotify(context)) return
        val open = android.content.Intent(context, Class.forName("com.bayx.chat.MainActivity")).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("tayfa_open", "requests")
        }
        val pi = android.app.PendingIntent.getActivity(
            context, 3, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CH_MESSAGES)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        }
        val n = builder
            .setContentTitle(com.bayx.chat.t("Yeni arkadaş isteği"))
            .setContentText("$name seni eklemek istiyor")
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(CONTACT_ID, n)
    }

    fun screenShareNotification(context: Context): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createChannels(context)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, CH_SCREEN)
        } else {
            @Suppress("DEPRECATION") android.app.Notification.Builder(context)
        }
        return builder
            .setContentTitle(com.bayx.chat.t("Tayfa ekran paylaşımı"))
            .setContentText(com.bayx.chat.t("Ekranın karşı tarafla paylaşılıyor"))
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setOngoing(true)
            .build()
    }

    fun canNotify(context: Context): Boolean {
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
        if (ChatPrefs.isMuted(roomId)) return
        if (Bayx.isRequest(roomId)) return // tanımadığın kişiden gelen mesaj isteği: sessizce bekler
        val id = roomId.hashCode()
        val locked = ChatPrefs.isLocked(roomId)
        // Sessiz saatlerde bildirim sessiz gelir; sabitlediğin (favori) sohbetler yine sesli bildirir.
        val hushed = Comfort.inQuietHours() && !ChatPrefs.isPinned(roomId)
        val text = when {
            locked -> com.bayx.chat.t("Kilitli sohbette yeni mesaj")
            unread > 1 -> com.bayx.chat.tf("%s yeni mesaj", unread)
            else -> com.bayx.chat.t("Yeni mesajın var")
        }
        val now = System.currentTimeMillis()
        val quiet = synchronized(lastAlert) {
            val recent = now - (lastAlert[roomId] ?: 0L) < 4_000
            if (!recent) lastAlert[roomId] = now
            recent
        }
        val builder = NotificationCompat.Builder(context, CH_MESSAGES)
            .setOnlyAlertOnce(quiet)
            .setSilent(hushed)
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setContentTitle(if (locked) "Tayfa" else roomName ?: "Tayfa")
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CH_MESSAGES)
                    .setSmallIcon(R.drawable.ic_stat_tayfa)
                    .setContentTitle("Tayfa")
                    .setContentText(com.bayx.chat.t("Yeni mesaj"))
                    .build(),
            )
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, roomId, id))
        // Kilitli sohbete bildirimden yanıt verilemez (önce kilit açılmalı).
        if (!locked) builder.addAction(replyAction(context, roomId, id))
        val notification = builder.addAction(readAction(context, roomId, id)).build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /** "Mesajı hatırlat": zamanı gelince mesajı gösterir; dokununca sohbet açılır. */
    fun showReminder(context: Context, roomId: String, roomName: String, text: String, jobId: Long) {
        if (!canNotify(context)) return
        val id = (jobId % Int.MAX_VALUE).toInt()
        val locked = ChatPrefs.isLocked(roomId)
        val body = if (locked) com.bayx.chat.t("Kilitli sohbetteki bir mesaj") else text.ifBlank { com.bayx.chat.t("Mesaj") }
        val notification = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setContentTitle(com.bayx.chat.tf("Hatırlatma · %s", if (locked) "Tayfa" else roomName.ifBlank { "Tayfa" }))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, roomId, id))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun showScheduledFailed(context: Context, roomId: String, roomName: String) {
        if (!canNotify(context)) return
        val id = roomId.hashCode() + 7
        val notification = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setContentTitle("Tayfa")
            .setContentText(com.bayx.chat.tf("%s için zamanlanmış mesaj gönderilemedi. Dokun, tekrar dene.", roomName.ifBlank { "Sohbet" }))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, roomId, id))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /** Bildirimden uygulamayı açmadan yanıt. */
    private fun replyAction(context: Context, roomId: String, id: Int): NotificationCompat.Action {
        // Hazır cevaplar: bildirimin altında tek dokunuşla gönderilir (Ayarlar > Kolaylıklar'dan değiştirilebilir).
        val input = androidx.core.app.RemoteInput.Builder(ReplyReceiver.KEY_TEXT)
            .setLabel(com.bayx.chat.t("Yanıtla"))
            .setChoices(Comfort.quickReplies.value.toTypedArray<CharSequence>())
            .build()
        val intent = android.content.Intent(context, ReplyReceiver::class.java)
            .setAction(ReplyReceiver.ACTION_REPLY)
            .putExtra(ReplyReceiver.EXTRA_ROOM, roomId)
        // Yanıt metni sisteme bu intent üzerinden eklendiği için değiştirilebilir olmalı.
        val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
        val pi = android.app.PendingIntent.getBroadcast(context, id * 31 + 1, intent, flags)
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_tayfa, com.bayx.chat.t("Yanıtla"), pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun readAction(context: Context, roomId: String, id: Int): NotificationCompat.Action {
        val intent = android.content.Intent(context, ReplyReceiver::class.java)
            .setAction(ReplyReceiver.ACTION_READ)
            .putExtra(ReplyReceiver.EXTRA_ROOM, roomId)
        val pi = android.app.PendingIntent.getBroadcast(
            context, id * 31 + 2, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(R.drawable.ic_stat_tayfa, com.bayx.chat.t("Okundu"), pi)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()
    }

    /**
     * Gelen arama bildirimi: telefonun zil sesiyle, cevaplanana ya da reddedilene kadar çalar
     * (ısrarcı); kilit ekranında tam ekran açılır. Gösterilemediyse false döner, o zaman
     * zil sesini uygulama çalar.
     */
    fun showIncomingCall(context: Context, callerName: String, video: Boolean, from: String? = null, callId: String? = null): Boolean {
        if (!canNotify(context)) return false
        createChannels(context)
        val channel = NotificationManagerCompat.from(context).getNotificationChannel(CH_CALLS)
        if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        val name = callerName.ifBlank { "Tayfa" }
        val open = openIntent(context, null, CALL_NOTIFICATION_ID)
        val answer = PendingIntent.getActivity(
            context, CALL_NOTIFICATION_ID + 1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_ACCEPT_CALL, callId ?: "*")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            context, CALL_NOTIFICATION_ID + 2,
            Intent(context, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_REJECT)
                .putExtra(CallActionReceiver.EXTRA_FROM, from)
                .putExtra(CallActionReceiver.EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (video) com.bayx.chat.t("Görüntülü arama geliyor") else com.bayx.chat.t("Sesli arama geliyor")
        fun base() = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setContentTitle(name)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(45_000)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
        val notification = runCatching {
            val person = androidx.core.app.Person.Builder().setName(name).setImportant(true).build()
            base().setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer).setIsVideo(video)).build()
        }.getOrElse {
            base()
                .addAction(R.drawable.ic_stat_tayfa, com.bayx.chat.t("Reddet"), decline)
                .addAction(R.drawable.ic_stat_tayfa, com.bayx.chat.t("Cevapla"), answer)
                .build()
        }
        // Israrcı: zil sesi ve titreşim bildirim kapanana kadar tekrar eder.
        notification.flags = notification.flags or android.app.Notification.FLAG_INSISTENT
        return runCatching { NotificationManagerCompat.from(context).notify(CALL_NOTIFICATION_ID, notification) }.isSuccess
    }

    /** Görüşme sürerken (CallService) gösterilen sessiz bildirim; "Kapat" ile görüşme biter. */
    fun ongoingCallNotification(context: Context, peerName: String, video: Boolean): android.app.Notification {
        createChannels(context)
        val hangup = PendingIntent.getBroadcast(
            context, CALL_NOTIFICATION_ID + 3,
            Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setContentTitle(peerName.ifBlank { "Tayfa" })
            .setContentText(if (video) com.bayx.chat.t("Görüntülü görüşme sürüyor") else com.bayx.chat.t("Sesli görüşme sürüyor"))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setSilent(true)
            .setUsesChronometer(true)
            .setContentIntent(openIntent(context, null, CALL_NOTIFICATION_ID + 4))
            .addAction(R.drawable.ic_stat_tayfa, com.bayx.chat.t("Kapat"), hangup)
            .build()
    }

    fun cancelCall(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(CALL_NOTIFICATION_ID) }
    }

    /** Bildirimden gönderilen yanıt gitmediyse sessizce haber ver. */
    fun showReplyFailed(context: Context, roomId: String) {
        if (!canNotify(context)) return
        val id = roomId.hashCode()
        val notification = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_tayfa)
            .setContentTitle("Tayfa")
            .setContentText(com.bayx.chat.t("Yanıt gönderilemedi. Uygulamayı açıp tekrar dene."))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, roomId, id))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun cancelRoom(context: Context, roomId: String) {
        runCatching { NotificationManagerCompat.from(context).cancel(roomId.hashCode()) }
    }

    fun cancelAll(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancelAll() }
    }
}
