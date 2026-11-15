package com.bayx.chat.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineItem

// Uygulamanın veri modelleri: oturum durumu, sohbet özeti, mesajlar ve açık sohbet.

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data object Offline : AuthState
    data class LoggedIn(val userId: String, val displayName: String) : AuthState
}

data class ChatSummary(
    val id: String,
    val name: String,
    val lastMessage: String,
    val time: Long,
    val unread: Int,
    val isDirect: Boolean,
    val dmUserId: String? = null,
    val lastFromMe: Boolean = false,
    val secret: Boolean = false,
    val avatarUrl: String? = null,
)

enum class MsgKind { TEXT, DATE, IMAGE, VIDEO, VOICE, LOCATION, NUDGE, EFFECT, FILE, POLL }
enum class MsgState { NONE, SENDING, SENT, READ, FAILED }

/** Bir mesaja bağlı medya: şifreli kaynak adresi ve gösterim için gereken bilgiler. */
class MediaRef(
    val source: MediaSource?,
    val thumbnail: MediaSource?,
    val mime: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val geoUri: String?,
    /** Müzik dosyasıysa şarkı adı, belgede dosya adı; sesli mesajda null. */
    val title: String? = null,
    /** Belgenin bayt cinsinden boyutu. */
    val size: Long = 0L,
)

/** Bir mesaja verilen tepki: emoji, kaç kişi, ben de verdim mi. */
data class MsgReaction(val key: String, val count: Int, val mine: Boolean)

/** Anket: seçenekler (kimlik, metin), oy sayıları, benim oylarım. */
data class PollInfo(
    val eventId: String?,
    val question: String,
    val answers: List<Pair<String, String>>,
    val counts: Map<String, Int>,
    val mine: Set<String>,
    val ended: Boolean,
) {
    val total get() = counts.values.sum()
}

data class ChatMessage(
    val key: String,
    val kind: MsgKind,
    val text: String,
    val mine: Boolean,
    val sender: String,
    val time: Long,
    val state: MsgState,
    val media: MediaRef? = null,
    val eventId: EventOrTransactionId? = null,
    val reactions: List<MsgReaction> = emptyList(),
    val poll: PollInfo? = null,
)

/** Açık bir sohbetin canlı durumu. */
class ChatSession internal constructor(val roomId: String) {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val title = MutableStateFlow("")
    @Volatile var isDirect: Boolean = true
    val dmUserId = MutableStateFlow<String?>(null)
    /** Şu an yazmakta olan diğer üyelerin adları. */
    val typing = MutableStateFlow<List<String>>(emptyList())
    /** Gizli sohbet mi; öyleyse mesajların kaybolma süresi (ms). */
    @Volatile var secret: Boolean = false
    @Volatile var lifetimeMs: Long = 0L
    /** Normal sohbette kaybolan mesaj süresi (ms, 0 = kapalı); ekranda göstermek için. */
    val disappearing = MutableStateFlow(0L)
    internal var room: Room? = null
    internal var typingHandle: TaskHandle? = null
    internal var expiryJob: Job? = null
    internal var timeline: Timeline? = null
    internal var handle: TaskHandle? = null
    internal val items = ArrayList<TimelineItem>()
}
