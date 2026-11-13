package com.bayx.chat.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.AssetType
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientDelegate
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.TypingNotificationsListener
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ThumbnailInfo
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import org.matrix.rustcomponents.sdk.VirtualTimelineItem
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import uniffi.matrix_sdk_common.BackgroundTaskFailureReason
import java.io.File
import java.security.SecureRandom
import java.time.Duration
import java.util.Locale

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

enum class MsgKind { TEXT, DATE, IMAGE, VIDEO, VOICE, LOCATION, NUDGE, EFFECT }
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
)

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
    internal var room: Room? = null
    internal var typingHandle: TaskHandle? = null
    internal var expiryJob: Job? = null
    internal var timeline: Timeline? = null
    internal var handle: TaskHandle? = null
    internal val items = ArrayList<TimelineItem>()
}

/** Matrix oturumu, eşitleme ve sohbet işlemlerinin tek merkezi. */
object Bayx {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authMutex = Mutex()
    private lateinit var appContext: Context
    private lateinit var store: SecureStore

    private var client: Client? = null
    private var syncService: SyncService? = null
    private var roomListResult: RoomListEntriesWithDynamicAdaptersResult? = null
    private var delegateHandle: TaskHandle? = null
    private val roomEntries = ArrayList<Room>()
    private var refreshJob: Job? = null
    private var presenceJob: Job? = null
    @Volatile private var foreground = false
    private val nudgeSeen = HashSet<String>()

    /** Şu an çevrimiçi olan üyelerin kullanıcı kimlikleri. */
    val online = MutableStateFlow<Set<String>>(emptySet())

    /** Üyelerin MSN tarzı durumları ve durum mesajları. */
    val presences = MutableStateFlow<Map<String, UserPresence>>(emptyMap())

    /** Kendi durumum (online, busy, away, invisible) ve durum mesajım. */
    val myMode = MutableStateFlow("online")
    val myStatus = MutableStateFlow("")
    val myAvatarUrl = MutableStateFlow<String?>(null)

    /** Gelen ya da gönderilen sohbet efekti (kalp, konfeti, kar). */
    val effects = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private val effectSeen = HashSet<String>()

    /** Gelen titreşimler: gönderenin görünen adı. */
    val nudges = MutableSharedFlow<NudgeEvent>(extraBufferCapacity = 4)

    /** Gelen ya da gönderilen titreşim. */
    data class NudgeEvent(val sender: String, val mine: Boolean)

    /** İlk oda listesi yüklenene kadar "yeni mesaj" sesi çalınmaz. */
    @Volatile private var roomsPrimed = false

    /** SDK platform başlatması başarısız olduysa nedeni; giriş hatalarında gösterilir. */
    @Volatile var platformError: String? = null

    val auth = MutableStateFlow<AuthState>(AuthState.Loading)
    val rooms = MutableStateFlow<List<ChatSummary>>(emptyList())

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = SecureStore(appContext)
        Calls.init(appContext)
        Sounds.init(appContext)
        SecretLock.init(appContext)
        Notifier.createChannels(appContext)
        scope.launch { restore() }
    }

    /** Uygulama ekranda mı? (Bildirim gösterip göstermemeye karar vermek için.) */
    val isForeground: Boolean
        get() = foreground

    /** Bildirime dokunularak açılması istenen sohbet. */
    val pendingRoom = MutableStateFlow<String?>(null)
    val pendingOpen = MutableStateFlow<String?>(null)

    fun roomName(roomId: String): String? =
        rooms.value.firstOrNull { it.id == roomId }?.takeIf { !it.secret }?.name

    // -------------------------------------------------------------- bildirim

    private const val PUSH_APP_ID = "com.tayfa.chat"

    /** Synapse bildirimleri aynı sunucudaki Tayfa servisine iletir; servis de Firebase'e. */
    private const val PUSH_URL = "http://127.0.0.1:8010/_matrix/push/v1/notify"

    fun onPushToken(token: String) {
        scope.launch { registerPush(token) }
    }

    private suspend fun registerPush(knownToken: String? = null) {
        val c = client ?: return
        val token = knownToken ?: runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        runCatching {
            c.setPusher(
                PusherIdentifiers(token, PUSH_APP_ID),
                PusherKind.Http(HttpPusherData(PUSH_URL, PushFormat.EVENT_ID_ONLY, null)),
                "Tayfa",
                android.os.Build.MODEL ?: "Android",
                null,
                "tr",
                false,
            )
        }
    }

    /** Bu cihazın bildirim kaydını siler; çıkıştan sonra eski hesabın bildirimleri gelmez. */
    private suspend fun unregisterPush(c: Client?) {
        val token = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
        if (c != null && token != null) runCatching { c.deletePusher(PusherIdentifiers(token, PUSH_APP_ID)) }
        runCatching { FirebaseMessaging.getInstance().deleteToken().await() }
    }

    val accessToken: String?
        get() = runCatching { client?.session()?.accessToken }.getOrNull()

    // ---------------------------------------------------------------- oturum

    private fun dataDir() = File(appContext.filesDir, "matrix")
    private fun cacheDir() = File(appContext.cacheDir, "matrix")

    private suspend fun buildClient(passphrase: String, forLogin: Boolean): Client {
        dataDir().mkdirs()
        cacheDir().mkdirs()
        var builder = ClientBuilder()
            .homeserverUrl(Api.HOMESERVER)
            .sqliteStore(SqliteStoreBuilder(dataDir().absolutePath, cacheDir().absolutePath).passphrase(passphrase))
            .autoEnableCrossSigning(true)
            .userAgent("Tayfa Android")
        if (forLogin) builder = builder.slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
        return builder.build()
    }

    private fun randomPassphrase(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    private suspend fun restore() = authMutex.withLock {
        val saved = store.load()
        if (saved == null) {
            wipeLocal()
            auth.value = AuthState.LoggedOut
            return@withLock
        }
        auth.value = AuthState.Loading
        try {
            val json = JSONObject(saved)
            val c = buildClient(json.getString("passphrase"), forLogin = false)
            c.restoreSession(
                org.matrix.rustcomponents.sdk.Session(
                    accessToken = json.getString("accessToken"),
                    refreshToken = json.optString("refreshToken").ifEmpty { null },
                    userId = json.getString("userId"),
                    deviceId = json.getString("deviceId"),
                    homeserverUrl = json.getString("homeserverUrl"),
                    oauthData = null,
                    slidingSyncVersion = SlidingSyncVersion.NATIVE,
                ),
            )
            client = c
            auth.value = AuthState.LoggedIn(json.getString("userId"), json.optString("displayName"))
            startSync(c)
        } catch (e: Exception) {
            // Kayıtlı oturumu silme: sorun geçici (ör. internet yok) olabilir.
            teardown()
            auth.value = AuthState.Offline
        }
    }

    fun retry() {
        scope.launch { restore() }
    }

    /** Giriş yapar. Başarılıysa null, değilse kullanıcıya gösterilecek mesajı döndürür. */
    suspend fun login(username: String, password: String): String? = withContext(Dispatchers.IO) {
        authMutex.withLock {
            teardown()
            wipeLocal()
            val passphrase = randomPassphrase()
            var c: Client? = null
            try {
                c = buildClient(passphrase, forLogin = true)
                c.login(username.trim().lowercase(), password, "Tayfa Android", null)
                val session = c.session()
                when (Api.accountProblem(session.accessToken)) {
                    null -> Unit
                    "M_USER_LOCKED" -> throw PendingApproval()
                    else -> throw ApiException("HESAP", "Hesap şu an kullanılamıyor.")
                }
                val name = runCatching { c.displayName() }.getOrDefault("").ifEmpty { username }
                store.save(
                    JSONObject()
                        .put("accessToken", session.accessToken)
                        .put("refreshToken", session.refreshToken ?: "")
                        .put("userId", session.userId)
                        .put("deviceId", session.deviceId)
                        .put("homeserverUrl", session.homeserverUrl)
                        .put("displayName", name)
                        .put("passphrase", passphrase)
                        .toString(),
                )
                client = c
                auth.value = AuthState.LoggedIn(session.userId, name)
                startSync(c)
                null
            } catch (e: Exception) {
                runCatching { c?.close() }
                teardown()
                wipeLocal()
                loginError(e)
            }
        }
    }

    private class PendingApproval : Exception()

    private fun loginError(e: Exception): String = when {
        e is PendingApproval -> "Hesabın henüz onaylanmadı. Yönetici onaylayınca giriş yapabilirsin."
        e is ClientException.MatrixApi && e.code == "M_USER_LOCKED" ->
            "Hesabın henüz onaylanmadı. Yönetici onaylayınca giriş yapabilirsin."
        e is ClientException.MatrixApi && e.code == "M_FORBIDDEN" -> "Giriş süresi doldu. Lütfen tekrar dene."
        e is ClientException.MatrixApi && e.code == "M_USER_DEACTIVATED" -> "Bu hesap kapatılmış."
        e is ClientException.MatrixApi && e.code == "M_LIMIT_EXCEEDED" -> "Çok fazla deneme yapıldı. Biraz sonra tekrar dene."
        e is ApiException -> e.message ?: "Giriş yapılamadı."
        else -> "Sunucuya bağlanılamadı. İnternet bağlantını kontrol edip tekrar dene." + errorDetail(e)
    }

    /** Sorun giderme için hatanın teknik özeti (şifre ya da anahtar içermez). */
    private fun errorDetail(e: Exception): String {
        val text = (e::class.simpleName ?: "Hata") + ": " + (e.message ?: "").take(300)
        val platform = platformError?.let { "\nPlatform: " + it.take(200) } ?: ""
        return "\n\nAyrıntı: $text$platform"
    }

    /** Oturumu sunucuda sonlandırır ve bu cihazdaki tüm anahtarları, mesajları ve önbelleği siler. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        authMutex.withLock {
            val c = client
            val sync = syncService
            Calls.shutdown()
            runCatching { withTimeoutOrNull(5_000) { unregisterPush(c) } }
            runCatching { withTimeoutOrNull(5_000) { sync?.stop() } }
            runCatching { withTimeoutOrNull(8_000) { c?.logout() } }
            teardown()
            wipeLocal()
            Notifier.cancelAll(appContext)
            auth.value = AuthState.LoggedOut
        }
    }

    private fun forceLocalLogout() {
        scope.launch {
            authMutex.withLock {
                Calls.shutdown()
                runCatching { withTimeoutOrNull(5_000) { unregisterPush(null) } }
                teardown()
                wipeLocal()
                Notifier.cancelAll(appContext)
                auth.value = AuthState.LoggedOut
            }
        }
    }

    private fun teardown() {
        refreshJob?.cancel()
        presenceJob?.cancel()
        presenceJob = null
        roomsPrimed = false
        online.value = emptySet()
        presences.value = emptyMap()
        contacts.value = Api.Contacts(emptyList(), emptyList(), emptyList())
        myMode.value = "online"
        myStatus.value = ""
        myAvatarUrl.value = null
        avatarCache.evictAll()
        synchronized(effectSeen) { effectSeen.clear() }
        synchronized(nudgeSeen) { nudgeSeen.clear() }
        runCatching { roomListResult?.entriesStream()?.cancel() }
        runCatching { delegateHandle?.cancel() }
        runCatching { syncService?.close() }
        runCatching { client?.close() }
        roomListResult = null
        delegateHandle = null
        syncService = null
        client = null
        synchronized(roomEntries) { roomEntries.clear() }
        rooms.value = emptyList()
        mediaCache.evictAll()
    }

    /** Bu cihazda oturuma ait her şeyi siler: şifreli oturum kaydı, yerel veritabanı, medya ve önbellek. */
    private fun wipeLocal() {
        store.clear()
        SecretLock.clear()
        runCatching { dataDir().deleteRecursively() }
        runCatching { appContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { File(appContext.filesDir, "media").deleteRecursively() }
    }

    // --------------------------------------------------------------- eşitleme

    private suspend fun startSync(c: Client) {
        delegateHandle = c.setDelegate(object : ClientDelegate {
            override fun didReceiveAuthError(isSoftLogout: Boolean) {
                forceLocalLogout()
            }

            override fun onBackgroundTaskErrorReport(taskName: String, error: BackgroundTaskFailureReason) = Unit
        })
        val sync = c.syncService().withOfflineMode().finish()
        syncService = sync
        val roomList = sync.roomListService().allRooms()
        val result = roomList.entriesWithDynamicAdapters(
            200u,
            object : RoomListEntriesListener {
                override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                    applyRoomUpdates(roomEntriesUpdate)
                }
            },
        )
        result.controller().setFilter(RoomListEntriesDynamicFilterKind.NonLeft)
        roomListResult = result
        sync.start()
        startPresence()
        scope.launch { registerPush() }
        scope.launch { refreshMyAvatar() }
    }

    // ------------------------------------------------------ çevrimiçi durumu

    private fun startPresence() {
        presenceJob?.cancel()
        presenceJob = scope.launch {
            while (isActive) {
                val token = accessToken
                if (foreground && token != null) {
                    runCatching { Api.presence(token, active = true) }.onSuccess { applyPresence(it) }
                }
                delay(30_000)
            }
        }
    }

    /** Uygulama öne geldiğinde ya da arka plana geçtiğinde çağrılır. */
    fun setForeground(active: Boolean) {
        foreground = active
        if (!active) SecretLock.lock()
        Calls.onForeground(active)
        val token = accessToken ?: return
        scope.launch {
            runCatching { Api.presence(token, active) }.onSuccess { if (active) applyPresence(it) }
        }
    }

    // --------------------------------------------------------------- titreşim

    private const val NUDGE_MARK = "\u2060\u2060"
    private const val NUDGE_BODY = NUDGE_MARK + "Titreşim gönderdi"

    private fun isNudge(body: String) = body.startsWith(NUDGE_MARK)

    /** Yeni gelen (son 20 saniye) ve daha önce işlenmemiş bir titreşimi bir kez bildirir. */
    private fun noticeNudge(senderId: String, senderName: String, time: Long) {
        if (System.currentTimeMillis() - time > 20_000) return
        val fresh = synchronized(nudgeSeen) { nudgeSeen.add("$senderId:$time") }
        if (fresh) nudges.tryEmit(NudgeEvent(senderName, mine = false))
    }

    suspend fun sendNudge(session: ChatSession): Boolean {
        val ok = sendText(session, NUDGE_BODY)
        if (ok) nudges.tryEmit(NudgeEvent("", mine = true))
        return ok
    }

    private fun applyRoomUpdates(updates: List<RoomListEntriesUpdate>) {
        synchronized(roomEntries) {
            for (u in updates) {
                when (u) {
                    is RoomListEntriesUpdate.Append -> roomEntries.addAll(u.values)
                    is RoomListEntriesUpdate.Clear -> roomEntries.clear()
                    is RoomListEntriesUpdate.PushFront -> roomEntries.add(0, u.value)
                    is RoomListEntriesUpdate.PushBack -> roomEntries.add(u.value)
                    is RoomListEntriesUpdate.PopFront -> if (roomEntries.isNotEmpty()) roomEntries.removeAt(0)
                    is RoomListEntriesUpdate.PopBack -> if (roomEntries.isNotEmpty()) roomEntries.removeAt(roomEntries.size - 1)
                    is RoomListEntriesUpdate.Insert -> roomEntries.add(u.index.toInt().coerceIn(0, roomEntries.size), u.value)
                    is RoomListEntriesUpdate.Set -> if (u.index.toInt() < roomEntries.size) roomEntries[u.index.toInt()] = u.value
                    is RoomListEntriesUpdate.Remove -> if (u.index.toInt() < roomEntries.size) roomEntries.removeAt(u.index.toInt())
                    is RoomListEntriesUpdate.Truncate -> while (roomEntries.size > u.length.toInt()) roomEntries.removeAt(roomEntries.size - 1)
                    is RoomListEntriesUpdate.Reset -> {
                        roomEntries.clear()
                        roomEntries.addAll(u.values)
                    }
                }
            }
        }
        refreshRooms()
    }

    private fun refreshRooms() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(120)
            val snapshot = synchronized(roomEntries) { roomEntries.toList() }
            val previous = rooms.value.associateBy { it.id }
            val next = snapshot.mapNotNull { room -> runCatching { summarize(room) }.getOrNull() }
            if (roomsPrimed && foreground) {
                val now = System.currentTimeMillis()
                val incoming = next.any { r ->
                    val old = previous[r.id]
                    !r.lastFromMe && r.lastMessage != NUDGE_LABEL && r.time > (old?.time ?: 0L) &&
                        now - r.time < 30_000
                }
                if (incoming) Sounds.incoming()
            }
            rooms.value = next
            if (next.isNotEmpty()) roomsPrimed = true
        }
    }

    private suspend fun summarize(room: Room): ChatSummary {
        val info = room.roomInfo()
        if (info.membership == Membership.INVITED) {
            // Özel bir arkadaş çevresi: davetler otomatik kabul edilir.
            scope.launch { runCatching { room.join() } }
        }
        var text = ""
        var time = 0L
        var fromMe = false
        when (val latest = runCatching { room.latestEvent() }.getOrNull()) {
            is LatestEventValue.Remote -> {
                text = describe(latest.content).orEmpty()
                time = latest.timestamp.toLong()
                fromMe = latest.isOwn
                if (!latest.isOwn && text == NUDGE_LABEL) {
                    val profile = latest.profile
                    val name = if (profile is ProfileDetails.Ready) profile.displayName ?: latest.sender else latest.sender
                    noticeNudge(latest.sender, name, time)
                }
            }
            is LatestEventValue.Local -> {
                text = describe(latest.content).orEmpty()
                time = latest.timestamp.toLong()
                fromMe = true
            }
            is LatestEventValue.RemoteInvite -> {
                text = "Sohbete davet edildin"
                time = latest.timestamp.toLong()
            }
            else -> Unit
        }
        return ChatSummary(
            id = info.id,
            name = info.displayName?.takeIf { it.isNotBlank() } ?: "Sohbet",
            lastMessage = text,
            time = time,
            unread = info.numUnreadMessages.toInt(),
            isDirect = info.isDirect,
            lastFromMe = fromMe,
            secret = secretLifetime(info.topic) != null,
            avatarUrl = info.avatarUrl ?: if (info.isDirect || secretLifetime(info.topic) != null) heroAvatar(room) else null,
            dmUserId = if (info.isDirect) dmPartner(room) else null,
        )
    }

    private const val NUDGE_LABEL = "Titreşim gönderdi"

    /** Birebir sohbetteki karşı tarafın kullanıcı kimliği. */
    private suspend fun dmPartner(room: Room): String? {
        val me = (auth.value as? AuthState.LoggedIn)?.userId
        return runCatching { room.heroes().map { it.userId }.firstOrNull { it != me } }.getOrNull()
    }

    /** Mesaj içeriğini kısa bir metne çevirir; sohbette gösterilmeyecek olaylar için null döner. */
    private fun describe(content: TimelineItemContent): String? {
        if (content !is TimelineItemContent.MsgLike) return null
        return when (val kind = content.content.kind) {
            is MsgLikeKind.Message -> when (val type = kind.content.msgType) {
                is MessageType.Text -> when {
                    isNudge(type.content.body) -> NUDGE_LABEL
                    type.content.body.startsWith(EFFECT_MARK) -> effectLabel(type.content.body.removePrefix(EFFECT_MARK))
                    else -> type.content.body
                }
                is MessageType.Notice -> type.content.body
                is MessageType.Emote -> type.content.body
                is MessageType.Image -> "Fotoğraf"
                is MessageType.Video -> "Video"
                is MessageType.Audio -> "Sesli mesaj"
                is MessageType.Location -> "Konum"
                is MessageType.File -> "Dosya"
                else -> kind.content.body
            }
            is MsgLikeKind.UnableToDecrypt -> "Şifreli mesaj (bu cihazda açılamıyor)"
            is MsgLikeKind.Redacted -> "Mesaj silindi"
            else -> null
        }
    }

    // ---------------------------------------------------------------- sohbet

    suspend fun openChat(roomId: String): ChatSession? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        val room = c.getRoom(roomId) ?: runCatching { syncService?.roomListService()?.room(roomId) }.getOrNull()
            ?: return@withContext null
        val session = ChatSession(roomId)
        session.room = room
        Notifier.cancelRoom(appContext, roomId)
        runCatching {
            val info = room.roomInfo()
            session.title.value = info.displayName.orEmpty()
            session.isDirect = info.isDirect
            val lifetime = secretLifetime(info.topic)
            if (lifetime != null) {
                session.secret = true
                session.lifetimeMs = lifetime
                session.dmUserId.value = dmPartner(room)
            } else if (info.isDirect) {
                session.dmUserId.value = dmPartner(room)
            }
        }
        runCatching { syncService?.roomListService()?.setRoomSubscriptions(listOf(roomId)) }
        val timeline = runCatching {
            room.timelineWithConfiguration(
                TimelineConfiguration(
                    focus = TimelineFocus.Live(hideThreadedEvents = false),
                    filter = TimelineFilter.All,
                    internalIdPrefix = null,
                    dateDividerMode = DateDividerMode.DAILY,
                    trackReadReceipts = TimelineReadReceiptTracking.MESSAGE_LIKE_EVENTS,
                    reportUtds = false,
                ),
            )
        }.getOrElse { room.timeline() }
        session.typingHandle = runCatching {
            room.subscribeToTypingNotifications(object : TypingNotificationsListener {
                override fun call(typingUserIds: List<String>) {
                    val me = (auth.value as? AuthState.LoggedIn)?.userId
                    session.typing.value = typingUserIds.filter { it != me }.map { uid ->
                        presenceName(uid)
                    }
                }
            })
        }.getOrNull()
        if (session.lifetimeMs > 0) {
            // Süresi dolan mesajlar ekrandan da kalksın.
            session.expiryJob = scope.launch {
                while (isActive) {
                    delay(30_000)
                    applyTimelineDiffs(session, emptyList())
                }
            }
        }
        session.timeline = timeline
        session.handle = timeline.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                applyTimelineDiffs(session, diff)
            }
        })
        scope.launch { runCatching { timeline.paginateBackwards(40.toUShort()) } }
        scope.launch { runCatching { timeline.markAsRead(ReceiptType.READ) } }
        session
    }

    fun closeChat(session: ChatSession) {
        session.expiryJob?.cancel()
        runCatching { session.typingHandle?.cancel() }
        runCatching { session.room?.let { r -> scope.launch { runCatching { r.typingNotice(false) } } } }
        runCatching { session.handle?.cancel() }
        runCatching { session.timeline?.close() }
        session.handle = null
        session.timeline = null
    }

    private fun applyTimelineDiffs(session: ChatSession, diffs: List<TimelineDiff>) {
        val items = session.items
        val mapped = synchronized(items) {
            for (d in diffs) {
                when (d) {
                    is TimelineDiff.Append -> items.addAll(d.values)
                    is TimelineDiff.Clear -> items.clear()
                    is TimelineDiff.PushFront -> items.add(0, d.value)
                    is TimelineDiff.PushBack -> items.add(d.value)
                    is TimelineDiff.PopFront -> if (items.isNotEmpty()) items.removeAt(0)
                    is TimelineDiff.PopBack -> if (items.isNotEmpty()) items.removeAt(items.size - 1)
                    is TimelineDiff.Insert -> items.add(d.index.toInt().coerceIn(0, items.size), d.value)
                    is TimelineDiff.Set -> if (d.index.toInt() < items.size) items[d.index.toInt()] = d.value
                    is TimelineDiff.Remove -> if (d.index.toInt() < items.size) items.removeAt(d.index.toInt())
                    is TimelineDiff.Truncate -> while (items.size > d.length.toInt()) items.removeAt(items.size - 1)
                    is TimelineDiff.Reset -> {
                        items.clear()
                        items.addAll(d.values)
                    }
                }
            }
            mapWithReceipts(session, items)
        }
        session.messages.value = mapped
        val timeline = session.timeline
        if (timeline != null) scope.launch { runCatching { timeline.markAsRead(ReceiptType.READ) } }
    }

    private fun toMessage(item: TimelineItem): ChatMessage? {
        val key = item.uniqueId().id
        val virtual = item.asVirtual()
        if (virtual != null) {
            return if (virtual is VirtualTimelineItem.DateDivider) {
                ChatMessage(key, MsgKind.DATE, "", false, "", virtual.ts.toLong(), MsgState.NONE)
            } else {
                null
            }
        }
        val event = item.asEvent() ?: return null
        val text = describe(event.content) ?: return null
        val media = mediaOf(event.content)
        val state = when (event.localSendState) {
            is EventSendState.NotSentYet -> MsgState.SENDING
            is EventSendState.SendingFailed -> MsgState.FAILED
            is EventSendState.Sent -> MsgState.SENT
            else -> if (event.isOwn) MsgState.SENT else MsgState.NONE
        }
        val profile = event.senderProfile
        val sender = if (profile is ProfileDetails.Ready) profile.displayName ?: event.sender else event.sender
        val effect = effectName(event.content)
        if (effect != null) {
            if (!event.isOwn) noticeEffect(event.sender, effect, event.timestamp.toLong())
            return ChatMessage(
                key, MsgKind.EFFECT, effect, event.isOwn, sender, event.timestamp.toLong(), state,
                eventId = event.eventOrTransactionId,
            )
        }
        val nudge = media == null && text == NUDGE_LABEL
        if (nudge && !event.isOwn) noticeNudge(event.sender, sender, event.timestamp.toLong())
        val kind = if (nudge) MsgKind.NUDGE else media?.first ?: MsgKind.TEXT
        return ChatMessage(
            key, kind, text, event.isOwn, sender, event.timestamp.toLong(), state, media?.second,
            eventId = event.eventOrTransactionId,
        )
    }

    private fun mediaOf(content: TimelineItemContent): Pair<MsgKind, MediaRef>? {
        if (content !is TimelineItemContent.MsgLike) return null
        val message = content.content.kind as? MsgLikeKind.Message ?: return null
        return when (val type = message.content.msgType) {
            is MessageType.Image -> {
                val info = type.content.info
                MsgKind.IMAGE to MediaRef(
                    type.content.source, info?.thumbnailSource, info?.mimetype ?: "image/jpeg", 0L,
                    info?.width?.toInt() ?: 0, info?.height?.toInt() ?: 0, null,
                )
            }
            is MessageType.Video -> {
                val info = type.content.info
                MsgKind.VIDEO to MediaRef(
                    type.content.source, info?.thumbnailSource, info?.mimetype ?: "video/mp4",
                    info?.duration?.toMillis() ?: 0L, info?.width?.toInt() ?: 0, info?.height?.toInt() ?: 0, null,
                )
            }
            is MessageType.Audio -> {
                val info = type.content.info
                MsgKind.VOICE to MediaRef(
                    type.content.source, null, info?.mimetype ?: "audio/ogg",
                    info?.duration?.toMillis() ?: type.content.audio?.duration?.toMillis() ?: 0L, 0, 0, null,
                )
            }
            is MessageType.Location -> MsgKind.LOCATION to MediaRef(null, null, "", 0L, 0, 0, type.content.geoUri)
            else -> null
        }
    }

    // ----------------------------------------------------------------- medya

    /** Çözülmüş medya yalnızca bellekte tutulur; diske düz (şifresiz) dosya yazılmaz. */
    private val mediaCache = object : LruCache<String, ByteArray>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** Medyayı indirir ve şifresini çözer. Kalıcı kopya SDK'nın şifreli veritabanında durur. */
    suspend fun loadMedia(source: MediaSource): ByteArray? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        val key = runCatching { source.url() }.getOrNull() ?: return@withContext null
        mediaCache.get(key)?.let { return@withContext it }
        val bytes = runCatching { c.getMediaContent(source) }.getOrNull() ?: return@withContext null
        if (bytes.size < 32 * 1024 * 1024) mediaCache.put(key, bytes)
        bytes
    }

    private fun upload(bytes: ByteArray, filename: String) = UploadParameters(
        source = UploadSource.Data(bytes, filename),
        caption = null,
        formattedCaption = null,
        mentions = null,
        inReplyTo = null,
    )

    suspend fun sendImage(session: ChatSession, bytes: ByteArray, width: Int, height: Int): Boolean =
        withContext(Dispatchers.IO) {
            val timeline = session.timeline ?: return@withContext false
            runCatching {
                timeline.sendImage(
                    upload(bytes, "foto.jpg"),
                    null,
                    ImageInfo(
                        height = height.toULong(),
                        width = width.toULong(),
                        mimetype = "image/jpeg",
                        size = bytes.size.toULong(),
                        thumbnailInfo = null,
                        thumbnailSource = null,
                        blurhash = null,
                        isAnimated = false,
                    ),
                ).join()
            }.isSuccess
        }

    suspend fun sendVideo(
        session: ChatSession,
        bytes: ByteArray,
        mime: String,
        width: Int,
        height: Int,
        durationMs: Long,
        thumbnail: ByteArray?,
        thumbWidth: Int,
        thumbHeight: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        val timeline = session.timeline ?: return@withContext false
        runCatching {
            timeline.sendVideo(
                upload(bytes, "video.mp4"),
                thumbnail?.let { UploadSource.Data(it, "kapak.jpg") },
                VideoInfo(
                    duration = Duration.ofMillis(durationMs),
                    height = height.toULong(),
                    width = width.toULong(),
                    mimetype = mime,
                    size = bytes.size.toULong(),
                    thumbnailInfo = thumbnail?.let {
                        ThumbnailInfo(thumbHeight.toULong(), thumbWidth.toULong(), "image/jpeg", it.size.toULong())
                    },
                    thumbnailSource = null,
                    blurhash = null,
                ),
            ).join()
        }.isSuccess
    }

    suspend fun sendVoice(
        session: ChatSession,
        bytes: ByteArray,
        mime: String,
        durationMs: Long,
        waveform: List<Float>,
    ): Boolean = withContext(Dispatchers.IO) {
        val timeline = session.timeline ?: return@withContext false
        runCatching {
            timeline.sendVoiceMessage(
                upload(bytes, if (mime == "audio/ogg") "sesli-mesaj.ogg" else "sesli-mesaj.m4a"),
                AudioInfo(Duration.ofMillis(durationMs), bytes.size.toULong(), mime),
                waveform,
            ).join()
        }.isSuccess
    }

    suspend fun sendLocation(session: ChatSession, latitude: Double, longitude: Double): Boolean =
        withContext(Dispatchers.IO) {
            val timeline = session.timeline ?: return@withContext false
            val coordinates = String.format(Locale.US, "%.6f,%.6f", latitude, longitude)
            runCatching {
                timeline.sendLocation("Konum: $coordinates", "geo:$coordinates", null, null, AssetType.SENDER, null)
            }.isSuccess
        }

    suspend fun sendText(session: ChatSession, text: String): Boolean = withContext(Dispatchers.IO) {
        val timeline = session.timeline ?: return@withContext false
        val ok = runCatching { timeline.send(messageEventContentFromMarkdown(text)) }.isSuccess
        if (ok && text != NUDGE_BODY && !text.startsWith(EFFECT_MARK)) Sounds.play(Sounds.Kind.OUTGOING)
        ok
    }

    /** Kişiyle birebir sohbeti bulur ya da uçtan uca şifreli yeni bir sohbet açar; oda kimliğini döndürür. */
    suspend fun startDirectChat(userId: String): String? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        runCatching {
            c.getDmRoom(userId)?.id() ?: c.createRoom(
                CreateRoomParameters(
                    name = null,
                    isEncrypted = true,
                    isDirect = true,
                    visibility = RoomVisibility.Private,
                    preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
                    invite = listOf(userId),
                ),
            )
        }.getOrNull()
    }

    suspend fun createGroup(name: String, userIds: List<String>): String? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        runCatching {
            c.createRoom(
                CreateRoomParameters(
                    name = name,
                    isEncrypted = true,
                    isDirect = false,
                    visibility = RoomVisibility.Private,
                    preset = RoomPreset.PRIVATE_CHAT,
                    invite = userIds,
                ),
            )
        }.getOrNull()
    }

    // ------------------------------------------------------------- okundu

    /** Mesajları eşler; karşı tarafın okundu bilgisi geçtiği yerden önceki kendi mesajlarımı "okundu" yapar. */
    private fun mapWithReceipts(session: ChatSession, items: List<TimelineItem>): List<ChatMessage> {
        val me = (auth.value as? AuthState.LoggedIn)?.userId
        val now = System.currentTimeMillis()
        var readSeen = false
        val out = ArrayList<ChatMessage>(items.size)
        for (index in items.indices.reversed()) {
            val item = items[index]
            val event = runCatching { item.asEvent() }.getOrNull()
            if (event != null && event.readReceipts.keys.any { it != me }) readSeen = true
            var msg = runCatching { toMessage(item) }.getOrNull() ?: continue
            if (session.lifetimeMs > 0 && msg.kind != MsgKind.DATE && now - msg.time > session.lifetimeMs) continue
            if (msg.mine && readSeen && msg.state == MsgState.SENT) msg = msg.copy(state = MsgState.READ)
            out.add(msg)
        }
        out.reverse()
        // Süresi dolan mesajlar yüzünden boş kalan tarih ayraçlarını at.
        return out.filterIndexed { i, m -> m.kind != MsgKind.DATE || (i + 1 < out.size && out[i + 1].kind != MsgKind.DATE) }
    }

    // ------------------------------------------------------------- yazıyor

    fun setTyping(session: ChatSession, typing: Boolean) {
        val room = session.room ?: return
        scope.launch { runCatching { room.typingNotice(typing) } }
    }

    private fun presenceName(userId: String): String {
        val local = userId.substringBefore(":").removePrefix("@")
        return memberNames[userId] ?: local
    }

    /** Üye listesinden öğrenilen görünen adlar (yazıyor göstergesi için). */
    private val memberNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun rememberMembers(members: List<Member>) {
        members.forEach { memberNames[it.userId] = it.displayName }
    }

    // ------------------------------------------------------------- kişiler

    val contacts = MutableStateFlow(Api.Contacts(emptyList(), emptyList(), emptyList()))

    suspend fun refreshContacts() {
        val token = accessToken ?: return
        runCatching { Api.contacts(token) }.onSuccess {
            contacts.value = it
            rememberMembers(it.friends + it.incoming + it.outgoing)
        }
    }

    suspend fun findUser(username: String): Api.ContactFind? {
        val token = accessToken ?: return null
        return runCatching { Api.contactFind(token, username) }.getOrNull()
    }

    /** Kişi isteği gönderir; hata varsa mesajı döndürür. */
    suspend fun sendContactRequest(username: String): String? {
        val token = accessToken ?: return "Oturum yok."
        return try {
            Api.contactRequest(token, username)
            refreshContacts()
            null
        } catch (e: ApiException) {
            e.message
        }
    }

    suspend fun acceptContact(username: String): Boolean {
        val token = accessToken ?: return false
        val ok = runCatching { Api.contactAccept(token, username) }.isSuccess
        if (ok) refreshContacts()
        return ok
    }

    suspend fun rejectContact(username: String): Boolean {
        val token = accessToken ?: return false
        val ok = runCatching { Api.contactReject(token, username) }.isSuccess
        if (ok) refreshContacts()
        return ok
    }

    suspend fun blockContact(username: String): Boolean {
        val token = accessToken ?: return false
        val ok = runCatching { Api.contactBlock(token, username) }.isSuccess
        if (ok) refreshContacts()
        return ok
    }

    suspend fun unblockContact(username: String): Boolean {
        val token = accessToken ?: return false
        val ok = runCatching { Api.contactUnblock(token, username) }.isSuccess
        if (ok) refreshContacts()
        return ok
    }

    // ------------------------------------------------------------- silme

    /** Kendi mesajını herkesten siler. */
    suspend fun deleteMessage(session: ChatSession, msg: ChatMessage): Boolean = withContext(Dispatchers.IO) {
        val timeline = session.timeline ?: return@withContext false
        val id = msg.eventId ?: return@withContext false
        runCatching { timeline.redactEvent(id, null) }.isSuccess
    }

    /** Bir mesaja alıntılı yanıt gönderir. */
    suspend fun sendReply(session: ChatSession, text: String, replyTo: EventOrTransactionId): Boolean = withContext(Dispatchers.IO) {
        val timeline = session.timeline ?: return@withContext false
        val eventId = (replyTo as? EventOrTransactionId.EventId)?.eventId ?: return@withContext false
        val ok = runCatching { timeline.sendReply(messageEventContentFromMarkdown(text), eventId) }.isSuccess
        if (ok) Sounds.play(Sounds.Kind.OUTGOING)
        ok
    }

    /** Gönderilmiş kendi mesajını düzenler. */
    suspend fun editMessage(session: ChatSession, msg: ChatMessage, newText: String): Boolean = withContext(Dispatchers.IO) {
        val timeline = session.timeline ?: return@withContext false
        val id = msg.eventId ?: return@withContext false
        runCatching {
            timeline.edit(id, EditedContent.RoomMessage(messageEventContentFromMarkdown(newText)))
        }.isSuccess
    }

    /** Bir metin mesajını başka bir sohbete iletir. */
    suspend fun forwardText(targetRoomId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val session = openChat(targetRoomId) ?: return@withContext false
        val ok = runCatching { session.timeline?.send(messageEventContentFromMarkdown("↪ " + text)); true }.getOrDefault(false)
        closeChat(session)
        ok
    }

    // ------------------------------------------------------------- durum

    private fun applyPresence(result: PresenceResult) {
        presences.value = result.users
        online.value = result.users.filterValues { it.mode != "offline" }.keys
        myMode.value = result.myMode
        myStatus.value = result.myStatus
    }

    /** MSN tarzı durum: online, busy, away ya da invisible (çevrimdışı görün). */
    fun setMode(mode: String) {
        myMode.value = mode
        val token = accessToken ?: return
        scope.launch { runCatching { Api.presence(token, foreground, mode = mode) }.onSuccess { applyPresence(it) } }
    }

    fun setStatusMessage(text: String) {
        myStatus.value = text
        val token = accessToken ?: return
        scope.launch { runCatching { Api.presence(token, foreground, status = text) }.onSuccess { applyPresence(it) } }
    }

    // ------------------------------------------------------------- profil

    suspend fun setDisplayName(name: String): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext false
        val ok = runCatching { c.setDisplayName(name) }.isSuccess
        if (ok) {
            val current = auth.value as? AuthState.LoggedIn
            if (current != null) auth.value = current.copy(displayName = name)
            store.load()?.let { saved -> runCatching { store.save(JSONObject(saved).put("displayName", name).toString()) } }
        }
        ok
    }

    suspend fun setAvatar(jpeg: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext false
        val ok = runCatching { c.uploadAvatar("image/jpeg", jpeg) }.isSuccess
        if (ok) myAvatarUrl.value = runCatching { c.avatarUrl() }.getOrNull()
        ok
    }

    suspend fun refreshMyAvatar() {
        val c = client ?: return
        myAvatarUrl.value = runCatching { c.avatarUrl() }.getOrNull()
    }

    private val avatarCache = LruCache<String, Bitmap>(60)

    /** Profil fotoğrafını (mxc adresi) indirir; yalnızca bellekte önbelleğe alır. */
    suspend fun loadAvatar(mxc: String): Bitmap? = withContext(Dispatchers.IO) {
        avatarCache.get(mxc)?.let { return@withContext it }
        val c = client ?: return@withContext null
        val bytes = runCatching {
            MediaSource.fromUrl(mxc).use { source -> c.getMediaThumbnail(source, 256uL, 256uL) }
        }.getOrNull() ?: return@withContext null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
        avatarCache.put(mxc, bitmap)
        bitmap
    }

    private suspend fun heroAvatar(room: Room): String? {
        val me = (auth.value as? AuthState.LoggedIn)?.userId
        return runCatching { room.heroes().firstOrNull { it.userId != me }?.avatarUrl }.getOrNull()
    }

    // ------------------------------------------------------------- efektler

    private const val EFFECT_MARK = "\u2060\u2061"
    val EFFECTS = listOf("kalp", "konfeti", "kar", "havaifisek")

    fun effectLabel(name: String): String = when (name) {
        "kalp" -> "Kalp yağmuru gönderdi"
        "konfeti" -> "Konfeti patlattı"
        "kar" -> "Kar yağdırdı"
        "havaifisek" -> "Havai fişek attı"
        else -> "Efekt gönderdi"
    }

    private fun effectName(content: TimelineItemContent): String? {
        val kind = (content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message ?: return null
        val type = kind.content.msgType as? MessageType.Text ?: return null
        val body = type.content.body
        return if (body.startsWith(EFFECT_MARK)) body.removePrefix(EFFECT_MARK).takeIf { it in EFFECTS } else null
    }

    private fun noticeEffect(senderId: String, name: String, time: Long) {
        if (System.currentTimeMillis() - time > 20_000) return
        val fresh = synchronized(effectSeen) { effectSeen.add("$senderId:$time") }
        if (fresh) effects.tryEmit(name)
    }

    suspend fun sendEffect(session: ChatSession, name: String): Boolean {
        val ok = sendText(session, EFFECT_MARK + name)
        if (ok) effects.tryEmit(name)
        return ok
    }

    // ---------------------------------------------------------- gizli sohbet

    /** Gizli sohbetler oda konusunda bu işaretle tanınır: "<işaret>:<ms>". */
    private const val SECRET_MARK = "\u2060tayfa-gizli"

    private fun secretLifetime(topic: String?): Long? {
        if (topic == null || !topic.startsWith(SECRET_MARK)) return null
        return topic.substringAfter(":", "").toLongOrNull() ?: 0L
    }

    /**
     * Kişiyle yeni bir gizli sohbet açar. Mesajlar [lifetimeMs] sonra sunucudan silinir
     * (Synapse saklama politikası) ve uygulamada gösterilmez.
     */
    suspend fun createSecretChat(userId: String, lifetimeMs: Long): String? = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext null
        val roomId = runCatching {
            c.createRoom(
                CreateRoomParameters(
                    name = null,
                    topic = "$SECRET_MARK:$lifetimeMs",
                    isEncrypted = true,
                    isDirect = false,
                    visibility = RoomVisibility.Private,
                    preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
                    invite = listOf(userId),
                ),
            )
        }.getOrNull() ?: return@withContext null
        var room: Room? = null
        for (attempt in 0 until 20) {
            room = c.getRoom(roomId)
            if (room != null) break
            delay(300)
        }
        runCatching {
            room?.sendStateEventRaw("m.room.retention", "", JSONObject().put("max_lifetime", lifetimeMs).toString())
        }
        roomId
    }
}
