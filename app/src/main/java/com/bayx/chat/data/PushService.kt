package com.bayx.chat.data

import com.google.firebase.messaging.FirebaseMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase'den gelen uyandırma mesajları. Sunucu yalnızca oda ve olay kimliği gönderir;
 * mesaj metni Google'ın sunucularından geçmez.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Bayx.onPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        when (data["type"]) {
            "message" -> {
                val roomId = data["room_id"].orEmpty()
                if (roomId.isEmpty() || Bayx.isForeground) return
                val unread = data["unread"]?.toIntOrNull() ?: 1
                Notifier.showMessage(applicationContext, roomId, Bayx.roomName(roomId), unread)
            }
            "call" -> {
                // Uygulama arka plandaysa davetin kendisini almak için yoklamayı hemen başlat.
                Calls.ensurePolling()
                if (Bayx.isForeground || Calls.state.value != null) return
                val from = data["from"].orEmpty()
                val name = data["name"].orEmpty().ifBlank { from.substringBefore(":").removePrefix("@") }
                Notifier.showIncomingCall(applicationContext, name, data["video"] == "1", from, data["call_id"])
            }
            "contact_request" -> {
                val name = data["name"].orEmpty().ifBlank { "Biri" }
                if (!Bayx.isForeground) Notifier.showContactRequest(applicationContext, name)
                // Uygulama açıksa kişi listesini tazele ki istek hemen görünsün.
                CoroutineScope(Dispatchers.IO).launch { runCatching { Bayx.refreshContacts() } }
            }
        }
    }
}
