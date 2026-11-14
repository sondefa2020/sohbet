package com.bayx.chat.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Bildirimdeki "Yanıtla" ve "Okundu" düğmeleri: uygulamayı açmadan çalışır. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(EXTRA_ROOM) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        val reply = intent.action == ACTION_REPLY
        if (reply && text.isNullOrEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var ok = false
            try {
                ok = withTimeoutOrNull(25_000) {
                    // Uygulama kapalıysa süreç yeni başlamış olabilir: oturumun açılmasını bekle.
                    val state = Bayx.auth.first { it is AuthState.LoggedIn || it is AuthState.LoggedOut || it is AuthState.Offline }
                    if (state is AuthState.LoggedOut) return@withTimeoutOrNull false
                    var session: ChatSession? = null
                    repeat(12) {
                        session = Bayx.openChat(roomId)
                        if (session != null) return@repeat
                        delay(1000)
                    }
                    val s = session ?: return@withTimeoutOrNull false
                    try {
                        if (reply) Bayx.sendText(s, text!!) else true
                    } finally {
                        delay(1500) // okundu bilgisinin gitmesine zaman tanı
                        Bayx.closeChat(s)
                    }
                } ?: false
            } finally {
                Notifier.cancelRoom(context, roomId)
                if (reply && !ok) Notifier.showReplyFailed(context, roomId)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.bayx.chat.YANITLA"
        const val ACTION_READ = "com.bayx.chat.OKUNDU"
        const val EXTRA_ROOM = "oda"
        const val KEY_TEXT = "yanit"
    }
}
