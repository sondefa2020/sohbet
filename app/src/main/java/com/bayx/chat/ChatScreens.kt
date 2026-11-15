package com.bayx.chat

import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.runtime.mutableStateListOf
import com.bayx.chat.data.PollInfo
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.outlined.Block
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.graphics.Brush
import com.bayx.chat.data.Gate
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Search
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bayx.chat.data.MediaTools
import com.bayx.chat.data.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.AdminMembers
import com.bayx.chat.data.Api
import com.bayx.chat.data.ApiException
import com.bayx.chat.data.AuthState
import com.bayx.chat.data.Bayx
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bayx.chat.data.RecoveryRepo
import com.bayx.chat.data.ProfileRepo
import com.bayx.chat.data.PresenceRepo
import com.bayx.chat.data.ContactsRepo
import com.bayx.chat.data.Calls
import com.bayx.chat.data.ChatMessage
import com.bayx.chat.data.ChatSession
import com.bayx.chat.data.Member
import com.bayx.chat.data.MsgKind
import com.bayx.chat.data.MsgState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- Sohbet ----------

@Composable
fun ChatScreen(roomId: String, onBack: () -> Unit, onGroupInfo: (String) -> Unit = {}) {
    // Sohbet oturumu ViewModel'de: ekran döndürülse ya da tema değişse bile yeniden açılmaz.
    val vm = viewModel(key = "sohbet:$roomId") { com.bayx.chat.ui.ChatViewModel(roomId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val current = (state as? com.bayx.chat.ui.ChatViewModel.State.Ready)?.session
    val failed = state is com.bayx.chat.ui.ChatViewModel.State.Failed

    if (current == null) {
        Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (failed) Text(t("Sohbet açılamadı."), color = Muted) else CircularProgressIndicator(color = Blue)
            }
        }
    } else {
        ChatContent(current, onBack, onGroupInfo)
    }
}

@Composable
internal fun ChatContent(session: ChatSession, onBack: () -> Unit, onGroupInfo: (String) -> Unit = {}) {
    val messages by session.messages.collectAsState()
    val title by session.title.collectAsState()
    val partner by session.dmUserId.collectAsState()
    val onlineUsers by PresenceRepo.online.collectAsState()
    val presences by PresenceRepo.presences.collectAsState()
    val typing by session.typing.collectAsState()
    val rooms by Bayx.rooms.collectAsState()
    val partnerOnline = partner != null && partner in onlineUsers
    val partnerPresence = partner?.let { presences[it] }
    val headerAvatar = rooms.firstOrNull { it.id == session.roomId }?.avatarUrl
    var showEmoji by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var actionMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var editTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var forwardMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var blockPeer by remember { mutableStateOf<String?>(null) }
    if (session.secret) SecureWindow()
    var lastNudge by remember { mutableLongStateOf(0L) }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(draft) {
        if (draft.isNotBlank()) {
            Bayx.setTyping(session, true)
            delay(5_000)
            Bayx.setTyping(session, false)
        } else {
            Bayx.setTyping(session, false)
        }
    }
    var showAttach by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<ChatMessage?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val recorder = remember { VoiceRecorder() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val context = LocalContext.current
    val askCallPermissions = rememberCallPermissions { toast(t("Arama için mikrofon (ve görüntülü aramada kamera) izni gerekli.")) }
    fun placeCall(video: Boolean) {
        val peer = partner
        when {
            !session.isDirect -> toast(t("Grup araması henüz yok; birebir sohbetten arayabilirsin."))
            peer == null -> toast(t("Bu sohbette arama yapılamıyor."))
            else -> askCallPermissions(video) { Calls.startCall(peer, title.ifBlank { peer }, video) }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
    }
    LaunchedEffect(recording) {
        while (recording) {
            delay(150)
            recorder.sample()
            recordSeconds = recorder.elapsedSeconds()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            VoicePlayer.stop()
        }
    }

    /** Medyayı hazırlayıp gönderir; [block] başarısızsa [failMessage] gösterilir. */
    fun sendMedia(failMessage: String, block: suspend () -> Boolean) {
        sending = true
        scope.launch {
            val ok = runCatching { block() }.getOrDefault(false)
            sending = false
            if (!ok) toast(failMessage)
        }
    }

    fun startRecording() {
        VoicePlayer.stop()
        if (recorder.start(context)) {
            recordSeconds = 0
            recording = true
        } else {
            toast(t("Kayıt başlatılamadı."))
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            sendMedia(t("Fotoğraf gönderilemedi.")) {
                val image = withContext(Dispatchers.IO) { MediaTools.prepareImage(context, uri) }
                if (image == null) false else Bayx.sendImage(session, image.bytes, image.width, image.height)
            }
        }
    }
    // Galeriden birden fazla fotoğraf/video seçip sırayla gönder.
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isNotEmpty()) {
            sending = true
            scope.launch {
                var failed = 0
                for (uri in uris) {
                    val mime = context.contentResolver.getType(uri).orEmpty()
                    val ok = runCatching {
                        if (mime.startsWith("video")) {
                            val video = withContext(Dispatchers.IO) { MediaTools.prepareVideo(context, uri) }
                            video != null && Bayx.sendVideo(
                                session, video.bytes, video.mime, video.width, video.height, video.durationMs,
                                video.thumbnail, video.thumbWidth, video.thumbHeight,
                            )
                        } else {
                            val image = withContext(Dispatchers.IO) { MediaTools.prepareImage(context, uri) }
                            image != null && Bayx.sendImage(session, image.bytes, image.width, image.height)
                        }
                    }.getOrDefault(false)
                    if (!ok) failed++
                }
                sending = false
                if (failed > 0) toast(tf("%s öğe gönderilemedi. Videolar en fazla 50 MB olabilir.", failed))
            }
        }
    }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sendMedia(t("Belge gönderilemedi. En fazla 50 MB'lık dosyalar gönderilebilir.")) {
                val doc = withContext(Dispatchers.IO) { MediaTools.prepareDocument(context, uri) }
                doc != null && Bayx.sendDocument(session, doc.bytes, doc.mime, doc.name)
            }
        }
    }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sendMedia(t("Müzik gönderilemedi. En fazla 50 MB'lık dosyalar gönderilebilir.")) {
                val audio = withContext(Dispatchers.IO) { MediaTools.prepareAudio(context, uri) }
                audio != null && Bayx.sendAudioFile(session, audio.bytes, audio.mime, audio.name, audio.durationMs)
            }
        }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            sendMedia(t("Video gönderilemedi. En fazla 50 MB'lık videolar gönderilebilir.")) {
                val video = withContext(Dispatchers.IO) { MediaTools.prepareVideo(context, uri) }
                if (video == null) {
                    false
                } else {
                    Bayx.sendVideo(
                        session, video.bytes, video.mime, video.width, video.height, video.durationMs,
                        video.thumbnail, video.thumbWidth, video.thumbHeight,
                    )
                }
            }
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val file = cameraFile
        cameraFile = null
        if (taken && file != null) {
            sendMedia(t("Fotoğraf gönderilemedi.")) {
                val image = withContext(Dispatchers.IO) {
                    val prepared = MediaTools.prepareImage(context, Uri.fromFile(file))
                    file.delete()
                    prepared
                }
                if (image == null) false else Bayx.sendImage(session, image.bytes, image.width, image.height)
            }
        } else {
            file?.delete()
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) {
            sendMedia(t("Konum alınamadı. Konum servisinin açık olduğunu kontrol et.")) {
                val location = MediaTools.currentLocation(context)
                if (location == null) false else Bayx.sendLocation(session, location.latitude, location.longitude)
            }
        } else {
            toast("Konum izni verilmedi.")
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else toast("Mikrofon izni verilmedi.")
    }

    val wallpaper by Wallpaper.selected.collectAsState()
    var showWallpaper by remember { mutableStateOf(false) }
    var showPoll by remember { mutableStateOf(false) }
    var showDisappear by remember { mutableStateOf(false) }
    val disappearing by session.disappearing.collectAsState()
    Box(Modifier.fillMaxSize()) {
      ChatBackground(wallpaper)
      Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(GlassBar).padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            val isGroup = !session.isDirect && !session.secret
            Avatar(title, 44.dp, url = headerAvatar, mode = partnerPresence?.mode ?: if (partnerOnline) "online" else null)
            Spacer(Modifier.width(12.dp))
            Column(
                Modifier.weight(1f).then(
                    if (isGroup) Modifier.clickable { onGroupInfo(session.roomId) } else Modifier,
                ),
            ) {
                Text(
                    title.ifEmpty { "Sohbet" },
                    color = Ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = Muted, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            typing.isNotEmpty() -> if (session.isDirect || session.secret) t("yazıyor…") else typing.joinToString() + t(" yazıyor…")
                            session.secret -> "Gizli · mesajlar ${lifetimeLabel(session.lifetimeMs)} sonra kaybolur"
                            partner == null -> t("Uçtan uca şifreli")
                            !partnerPresence?.status.isNullOrBlank() -> modeLabel(partnerPresence?.mode) + " · " + partnerPresence?.status
                            partnerPresence != null -> modeLabel(partnerPresence.mode) + t(" · şifreli")
                            partnerOnline -> t("Çevrimiçi · şifreli")
                            else -> t("Çevrimdışı · şifreli")
                        },
                        color = when {
                            typing.isNotEmpty() -> Blue
                            session.secret -> Color(0xFF6D4FC2)
                            else -> modeColor(partnerPresence?.mode)?.let { if (it == Online) Color(0xFF136A38) else it } ?: Muted
                        },
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ChipIcon(Icons.Outlined.Vibration, t("Titreşim gönder"), size = 38.dp, tint = Color(0xFF9A6236)) {
                val now = System.currentTimeMillis()
                if (now - lastNudge < 5_000) {
                    toast(t("Biraz bekle; titreşim 5 saniyede bir gönderilebilir."))
                } else {
                    lastNudge = now
                    scope.launch { if (!Bayx.sendNudge(session)) toast(t("Titreşim gönderilemedi.")) }
                }
            }
            Spacer(Modifier.width(6.dp))
            ChipIcon(Icons.Outlined.Call, "Sesli ara", size = 38.dp) { placeCall(false) }
            Spacer(Modifier.width(6.dp))
            ChipIcon(Icons.Outlined.Videocam, t("Görüntülü ara"), size = 38.dp) { placeCall(true) }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, t("Diğer"), tint = Ink) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(t("Mesajlarda ara")) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        onClick = { menuOpen = false; searchOpen = true },
                    )
                    DropdownMenuItem(
                        text = { Text(t("Fotoğraf ve videolar")) },
                        leadingIcon = { Icon(Icons.Outlined.PermMedia, contentDescription = null) },
                        onClick = { menuOpen = false; showGallery = true },
                    )
                    DropdownMenuItem(
                        text = { Text(t("Arka plan")) },
                        leadingIcon = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) },
                        onClick = { menuOpen = false; showWallpaper = true },
                    )
                    if (!session.secret) {
                        DropdownMenuItem(
                            text = { Text(t("Kaybolan mesajlar")) },
                            leadingIcon = { Icon(Icons.Outlined.Timer, contentDescription = null) },
                            onClick = { menuOpen = false; showDisappear = true },
                        )
                    }
                    partner?.let { peer ->
                        DropdownMenuItem(
                            text = { Text(t("Engelle"), color = Color(0xFFB4232A)) },
                            leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null, tint = Color(0xFFB4232A)) },
                            onClick = { menuOpen = false; blockPeer = peer },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = Line)
        if (!session.secret && disappearing > 0) {
            Text(
                tf("Kaybolan mesajlar açık: mesajlar %s sonra silinir.", lifetimeLabel(disappearing)),
                color = Muted, fontSize = 12.5.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { showDisappear = true }.padding(vertical = 6.dp),
            )
        }
        if (searchOpen) {
            val hits = if (searchQuery.isBlank()) 0 else messages.count { it.kind == MsgKind.TEXT && it.text.contains(searchQuery, true) }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text("Bu sohbette ara", color = Muted) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted) },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchQuery.isNotBlank()) Text(tf("%s sonuç", hits), color = Muted, fontSize = 13.sp)
                        IconButton(onClick = { searchOpen = false; searchQuery = "" }) {
                            Icon(Icons.Filled.Close, t("Aramayı kapat"), tint = Muted)
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (session.secret) {
            Text(
                "Bu gizli sohbette mesajlar ${lifetimeLabel(session.lifetimeMs)} sonra hem cihazlardan hem sunucudan silinir. " +
                    t("Ekran görüntüsü alınamaz."),
                color = Color(0xFF4A2FB0),
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE9E4FF))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val shown = if (searchOpen && searchQuery.isNotBlank()) {
                messages.filter { it.kind == MsgKind.TEXT && it.text.contains(searchQuery, true) }
            } else {
                messages
            }
            items(shown, key = { it.key }) { msg ->
                MessageRow(
                    msg,
                    showSender = !session.isDirect && !session.secret,
                    onOpen = { viewer = it },
                    onLongPress = { actionMsg = it },
                    onReact = { m, key -> scope.launch { Bayx.toggleReaction(session, m, key) } },
                    onVote = { poll, answer ->
                        scope.launch { if (!Bayx.votePoll(session, poll, answer)) toast(t("Oy verilemedi.")) }
                    },
                )
            }
        }
        if (showEmoji && !recording) {
            EmojiPanel(
                onEmoticon = { emo -> draft = (draft.trimEnd() + " " + emo.code + " ").trimStart() },
                onEffect = { name ->
                    showEmoji = false
                    scope.launch { if (!Bayx.sendEffect(session, name)) toast(t("Efekt gönderilemedi.")) }
                },
            )
        }
        if (sending) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = Blue, trackColor = Line)
        }
        if (recording) {
            RecordingBar(
                seconds = recordSeconds,
                onCancel = {
                    recorder.cancel()
                    recording = false
                },
                onSend = {
                    val result = recorder.stop()
                    recording = false
                    if (result == null) {
                        toast(t("Kayıt çok kısa."))
                    } else {
                        sendMedia(t("Sesli mesaj gönderilemedi.")) {
                            Bayx.sendVoice(session, result.bytes, result.mime, result.durationMs, result.waveform)
                        }
                    }
                },
            )
        } else {
            val banner = editTarget?.let { t("Düzenleniyor") to it } ?: replyTarget?.let { (if (it.mine) "Sen" else it.sender) to it }
            banner?.let { (who, msg) ->
                Row(
                    Modifier.fillMaxWidth().background(GlassBar).padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(Blue))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (editTarget != null) t("Mesajı düzenle") else tf("%s yanıtlanıyor", who), color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(
                            msg.text.ifBlank { "Medya" },
                            color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { replyTarget = null; editTarget = null; draft = "" }) {
                        Icon(Icons.Filled.Close, "Kapat", tint = Muted)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(GlassBar).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = GlassPill, shadowElevation = 2.dp, modifier = Modifier.size(48.dp)) {
                    IconButton(onClick = { showAttach = true }) { Icon(Icons.Filled.Add, "Ekle", tint = Ink) }
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = GlassPill,
                    shadowElevation = 2.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Mesaj yaz...", color = Muted) },
                        trailingIcon = {
                            IconButton(onClick = { showEmoji = !showEmoji }) {
                                Icon(Icons.Outlined.EmojiEmotions, t("İfadeler ve efektler"), tint = if (showEmoji) Blue else Muted)
                            }
                        },
                        maxLines = 4,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(56.dp)
                        .shadow(8.dp, CircleShape, ambientColor = Blue, spotColor = Blue)
                        .clip(CircleShape)
                        .background(BlueBrush)
                        .clickable {
                            val text = draft.trim()
                            if (text.isNotEmpty()) {
                                val edit = editTarget
                                val reply = replyTarget
                                draft = ""
                                editTarget = null
                                replyTarget = null
                                scope.launch {
                                    val ok = when {
                                        edit != null -> Bayx.editMessage(session, edit, text)
                                        reply != null && reply.eventId != null -> Bayx.sendReply(session, text, reply.eventId)
                                        else -> Bayx.sendText(session, text)
                                    }
                                    if (!ok) toast(t("Mesaj gönderilemedi."))
                                }
                            } else if (
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                startRecording()
                            } else {
                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (draft.isBlank()) {
                        Icon(Icons.Filled.Mic, "Sesli mesaj kaydet", tint = Color.White, modifier = Modifier.size(26.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, t("Gönder"), tint = Color.White)
                    }
                }
            }
        }
    }

    if (showAttach) {
        AttachSheet(
            onDismiss = { showAttach = false },
            onPhoto = {
                showAttach = false
                pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onCamera = {
                showAttach = false
                val launched = runCatching {
                    val file = MediaTools.newCameraFile(context)
                    cameraFile = file
                    takePicture.launch(FileProvider.getUriForFile(context, context.packageName + ".dosya", file))
                }.isSuccess
                if (!launched) toast(t("Kamera açılamadı."))
            },
            onVideo = {
                showAttach = false
                pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            },
            onMusic = {
                showAttach = false
                pickMusic.launch("audio/*")
            },
            onDocument = {
                showAttach = false
                pickDocument.launch("*/*")
            },
            onPoll = {
                showAttach = false
                showPoll = true
            },
            onLocation = {
                showAttach = false
                locationPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            },
        )
    }

    }
    viewer?.let { msg -> MediaViewerDialog(msg) { viewer = null } }
    if (showGallery) MediaGalleryDialog(session) { showGallery = false }
    if (showWallpaper) WallpaperPicker(context, wallpaper) { showWallpaper = false }
    actionMsg?.let { msg ->
        MessageActionSheet(
            msg = msg,
            canEdit = msg.mine && msg.kind == MsgKind.TEXT && msg.eventId != null,
            canDelete = msg.mine && msg.eventId != null,
            onReply = { replyTarget = msg; editTarget = null; actionMsg = null },
            onForward = { forwardMsg = msg; actionMsg = null },
            onEdit = { editTarget = msg; replyTarget = null; draft = msg.text; actionMsg = null },
            onDelete = { deleteTarget = msg; actionMsg = null },
            onDismiss = { actionMsg = null },
            onReact = { key -> actionMsg = null; scope.launch { Bayx.toggleReaction(session, msg, key) } },
        )
    }
    forwardMsg?.let { msg ->
        ForwardDialog(msg) { forwardMsg = null }
    }
    if (showPoll) {
        PollComposer(
            onDismiss = { showPoll = false },
            onCreate = { question, answers ->
                showPoll = false
                sendMedia(t("Anket gönderilemedi.")) { Bayx.createPoll(session, question, answers) }
            },
        )
    }
    if (showDisappear) {
        val options = listOf(0L, 86_400_000L, 7 * 86_400_000L, 90 * 86_400_000L)
        AlertDialog(
            onDismissRequest = { showDisappear = false },
            title = { Text(t("Kaybolan mesajlar")) },
            text = {
                Column {
                    Text(
                        t("Açıksa yeni ve eski mesajlar seçtiğin süre sonra iki tarafta da silinir. Bu sohbetteki herkes için geçerlidir."),
                        color = Muted, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    options.forEach { ms ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                showDisappear = false
                                scope.launch { if (!Bayx.setDisappearing(session, ms)) toast(t("Ayar değiştirilemedi. Grupta bunu yalnızca yöneticiler yapabilir.")) }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = disappearing == ms, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (ms == 0L) t("Kapalı") else lifetimeLabel(ms), color = Ink, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDisappear = false }) { Text(t("Kapat")) } },
        )
    }
    blockPeer?.let { peer ->
        val uname = peer.substringBefore(":").removePrefix("@")
        AlertDialog(
            onDismissRequest = { blockPeer = null },
            title = { Text("Engelle") },
            text = { Text(tf("@%s engellensin mi? Artık sana istek gönderemez ve kişilerinden çıkar.", uname)) },
            confirmButton = {
                TextButton(onClick = {
                    blockPeer = null
                    scope.launch {
                        if (ContactsRepo.blockContact(uname)) { toast("@$uname engellendi."); onBack() } else toast("Engellenemedi.")
                    }
                }) { Text("Engelle", color = Color(0xFFB4232A)) }
            },
            dismissButton = { TextButton(onClick = { blockPeer = null }) { Text(t("Vazgeç")) } },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t("Mesajı sil")) },
            text = { Text(t("Bu mesaj herkesten silinsin mi? Sohbette \"Mesaj silindi\" yazar.")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { if (!Bayx.deleteMessage(session, target)) toast("Mesaj silinemedi.") }
                }) { Text("Herkesten sil", color = Color(0xFFA32117)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(t("Vazgeç")) } },
        )
    }
}

/** İfade paneli: hareketli ifadeler ve iki tarafta oynayan efektler. */
@Composable
internal fun EmojiPanel(onEmoticon: (Emo) -> Unit, onEffect: (String) -> Unit) {
    Surface(color = Color.White, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(t("İfadeler"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Emo.entries.chunked(7).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { emo ->
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).clickable { onEmoticon(emo) },
                            contentAlignment = Alignment.Center,
                        ) { Emoticon(emo, size = 32.dp) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(t("Efektler (karşı tarafta da oynar)"), color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Bayx.EFFECTS.forEach { name ->
                    AssistChip(onClick = { onEffect(name) }, label = { Text(effectTitle(name), fontSize = 12.sp) })
                }
            }
        }
    }
}

internal fun formatDay(millis: Long): String =
    SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("tr")).format(Date(millis))

internal fun formatClock(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr")).format(Date(millis))
