package com.bayx.chat.data

import android.content.Context
import android.util.LruCache
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.AssetType
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientDelegate
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
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
)

enum class MsgKind { TEXT, DATE, IMAGE, VIDEO, VOICE, LOCATION, NUDGE }
enum class MsgState { NONE, SENDING, SENT, FAILED }

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
)

/** Açık bir sohbetin canlı durumu. */
class ChatSession internal constructor(val roomId: String) {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val title = MutableStateFlow("")
    @Volatile var isDirect: Boolean = true
    val dmUserId = MutableStateFlow<String?>(null)
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

    /** Gelen titreşimler: gönderenin görünen adı. */
    val nudges = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** SDK platform başlatması başarısız olduysa nedeni; giriş hatalarında gösterilir. */
    @Volatile var platformError: String? = null

    val auth = MutableStateFlow<AuthState>(AuthState.Loading)
    val rooms = MutableStateFlow<List<ChatSummary>>(emptyList())

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = SecureStore(appContext)
        scope.launch { restore() }
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
        e is PendingApproval -> "Hesabın henüz onaylanmadı. Yönetici onayladıktan sonra giriş yapabilirsin."
        e is ClientException.MatrixApi && e.code == "M_USER_LOCKED" ->
            "Hesabın henüz onaylanmadı. Yönetici onayladıktan sonra giriş yapabilirsin."
        e is ClientException.MatrixApi && e.code == "M_FORBIDDEN" -> "Kullanıcı adı veya şifre hatalı."
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
            runCatching { withTimeoutOrNull(5_000) { sync?.stop() } }
            runCatching { withTimeoutOrNull(8_000) { c?.logout() } }
            teardown()
            wipeLocal()
            auth.value = AuthState.LoggedOut
        }
    }

    private fun forceLocalLogout() {
        scope.launch {
            authMutex.withLock {
                teardown()
                wipeLocal()
                auth.value = AuthState.LoggedOut
            }
        }
    }

    private fun teardown() {
        refreshJob?.cancel()
        presenceJob?.cancel()
        presenceJob = null
        online.value = emptySet()
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
    }

    // ------------------------------------------------------ çevrimiçi durumu

    private fun startPresence() {
        presenceJob?.cancel()
        presenceJob = scope.launch {
            while (isActive) {
                val token = accessToken
                if (foreground && token != null) {
                    runCatching { Api.presence(token, active = true) }.onSuccess { online.value = it }
                }
                delay(30_000)
            }
        }
    }

    /** Uygulama öne geldiğinde ya da arka plana geçtiğinde çağrılır. */
    fun setForeground(active: Boolean) {
        foreground = active
        val token = accessToken ?: return
        scope.launch {
            runCatching { Api.presence(token, active) }.onSuccess { if (active) online.value = it }
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
        if (fresh) nudges.tryEmit(senderName)
    }

    suspend fun sendNudge(session: ChatSession): Boolean = sendText(session, NUDGE_BODY)

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
            rooms.value = snapshot.mapNotNull { room -> runCatching { summarize(room) }.getOrNull() }
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
        when (val latest = runCatching { room.latestEvent() }.getOrNull()) {
            is LatestEventValue.Remote -> {
                text = describe(latest.content).orEmpty()
                time = latest.timestamp.toLong()
                if (!latest.isOwn && text == NUDGE_LABEL) {
                    val profile = latest.profile
                    val name = if (profile is ProfileDetails.Ready) profile.displayName ?: latest.sender else latest.sender
                    noticeNudge(latest.sender, name, time)
                }
            }
            is LatestEventValue.Local -> {
                text = describe(latest.content).orEmpty()
                time = latest.timestamp.toLong()
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
                is MessageType.Text -> if (isNudge(type.content.body)) NUDGE_LABEL else type.content.body
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
        runCatching {
            val info = room.roomInfo()
            session.title.value = info.displayName.orEmpty()
            session.isDirect = info.isDirect
            if (info.isDirect) session.dmUserId.value = dmPartner(room)
        }
        runCatching { syncService?.roomListService()?.setRoomSubscriptions(listOf(roomId)) }
        val timeline = room.timeline()
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
            items.mapNotNull { runCatching { toMessage(it) }.getOrNull() }
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
        val nudge = media == null && text == NUDGE_LABEL
        if (nudge && !event.isOwn) noticeNudge(event.sender, sender, event.timestamp.toLong())
        val kind = if (nudge) MsgKind.NUDGE else media?.first ?: MsgKind.TEXT
        return ChatMessage(key, kind, text, event.isOwn, sender, event.timestamp.toLong(), state, media?.second)
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
        runCatching { timeline.send(messageEventContentFromMarkdown(text)) }.isSuccess
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
}
