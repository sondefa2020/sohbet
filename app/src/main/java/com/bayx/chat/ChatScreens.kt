package com.bayx.chat

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
fun ChatScreen(roomId: String, onBack: () -> Unit) {
    var session by remember { mutableStateOf<ChatSession?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(roomId) {
        val opened = runCatching { Bayx.openChat(roomId) }.getOrNull()
        if (opened == null) failed = true else session = opened
    }
    DisposableEffect(roomId) {
        onDispose { session?.let { Bayx.closeChat(it) } }
    }

    val current = session
    if (current == null) {
        Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (failed) Text("Sohbet açılamadı.", color = Muted) else CircularProgressIndicator(color = Blue)
            }
        }
    } else {
        ChatContent(current, onBack)
    }
}

@Composable
internal fun ChatContent(session: ChatSession, onBack: () -> Unit) {
    val messages by session.messages.collectAsState()
    val title by session.title.collectAsState()
    val partner by session.dmUserId.collectAsState()
    val onlineUsers by Bayx.online.collectAsState()
    val presences by Bayx.presences.collectAsState()
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
    val askCallPermissions = rememberCallPermissions { toast("Arama için mikrofon (ve görüntülü aramada kamera) izni gerekli.") }
    fun placeCall(video: Boolean) {
        val peer = partner
        when {
            !session.isDirect -> toast("Grup araması henüz yok; birebir sohbetten arayabilirsin.")
            peer == null -> toast("Bu sohbette arama yapılamıyor.")
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
            toast("Kayıt başlatılamadı.")
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            sendMedia("Fotoğraf gönderilemedi.") {
                val image = withContext(Dispatchers.IO) { MediaTools.prepareImage(context, uri) }
                if (image == null) false else Bayx.sendImage(session, image.bytes, image.width, image.height)
            }
        }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            sendMedia("Video gönderilemedi. En fazla 50 MB'lık videolar gönderilebilir.") {
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
            sendMedia("Fotoğraf gönderilemedi.") {
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
            sendMedia("Konum alınamadı. Konum servisinin açık olduğunu kontrol et.") {
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
    Box(Modifier.fillMaxSize()) {
      ChatBackground(wallpaper)
      Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(GlassBar).padding(start = 4.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Avatar(title, 44.dp, url = headerAvatar, mode = partnerPresence?.mode ?: if (partnerOnline) "online" else null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
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
                            typing.isNotEmpty() -> if (session.isDirect || session.secret) "yazıyor…" else typing.joinToString() + " yazıyor…"
                            session.secret -> "Gizli · mesajlar ${lifetimeLabel(session.lifetimeMs)} sonra kaybolur"
                            partner == null -> "Uçtan uca şifreli"
                            !partnerPresence?.status.isNullOrBlank() -> modeLabel(partnerPresence?.mode) + " · " + partnerPresence?.status
                            partnerPresence != null -> modeLabel(partnerPresence.mode) + " · şifreli"
                            partnerOnline -> "Çevrimiçi · şifreli"
                            else -> "Çevrimdışı · şifreli"
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
            ChipIcon(Icons.Outlined.Vibration, "Titreşim gönder", size = 38.dp, tint = Color(0xFF9A6236)) {
                val now = System.currentTimeMillis()
                if (now - lastNudge < 5_000) {
                    toast("Biraz bekle; titreşim 5 saniyede bir gönderilebilir.")
                } else {
                    lastNudge = now
                    scope.launch { if (!Bayx.sendNudge(session)) toast("Titreşim gönderilemedi.") }
                }
            }
            Spacer(Modifier.width(6.dp))
            ChipIcon(Icons.Outlined.Call, "Sesli ara", size = 38.dp) { placeCall(false) }
            Spacer(Modifier.width(6.dp))
            ChipIcon(Icons.Outlined.Videocam, "Görüntülü ara", size = 38.dp) { placeCall(true) }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Diğer", tint = Ink) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Mesajlarda ara") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        onClick = { menuOpen = false; searchOpen = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Fotoğraf ve videolar") },
                        leadingIcon = { Icon(Icons.Outlined.PermMedia, contentDescription = null) },
                        onClick = { menuOpen = false; showGallery = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Arka plan") },
                        leadingIcon = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) },
                        onClick = { menuOpen = false; showWallpaper = true },
                    )
                    partner?.let { peer ->
                        DropdownMenuItem(
                            text = { Text("Engelle", color = Color(0xFFB4232A)) },
                            leadingIcon = { Icon(Icons.Outlined.Block, contentDescription = null, tint = Color(0xFFB4232A)) },
                            onClick = { menuOpen = false; blockPeer = peer },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = Line)
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
                        if (searchQuery.isNotBlank()) Text("$hits sonuç", color = Muted, fontSize = 13.sp)
                        IconButton(onClick = { searchOpen = false; searchQuery = "" }) {
                            Icon(Icons.Filled.Close, "Aramayı kapat", tint = Muted)
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
                    "Ekran görüntüsü alınamaz.",
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
                )
            }
        }
        if (showEmoji && !recording) {
            EmojiPanel(
                onEmoticon = { emo -> draft = (draft.trimEnd() + " " + emo.code + " ").trimStart() },
                onEffect = { name ->
                    showEmoji = false
                    scope.launch { if (!Bayx.sendEffect(session, name)) toast("Efekt gönderilemedi.") }
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
                        toast("Kayıt çok kısa.")
                    } else {
                        sendMedia("Sesli mesaj gönderilemedi.") {
                            Bayx.sendVoice(session, result.bytes, result.mime, result.durationMs, result.waveform)
                        }
                    }
                },
            )
        } else {
            val banner = editTarget?.let { "Düzenleniyor" to it } ?: replyTarget?.let { (if (it.mine) "Sen" else it.sender) to it }
            banner?.let { (who, msg) ->
                Row(
                    Modifier.fillMaxWidth().background(GlassBar).padding(start = 16.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(Blue))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (editTarget != null) "Mesajı düzenle" else "$who yanıtlanıyor", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                                Icon(Icons.Outlined.EmojiEmotions, "İfadeler ve efektler", tint = if (showEmoji) Blue else Muted)
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
                                    if (!ok) toast("Mesaj gönderilemedi.")
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
                        Icon(Icons.AutoMirrored.Filled.Send, "Gönder", tint = Color.White)
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
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onCamera = {
                showAttach = false
                val launched = runCatching {
                    val file = MediaTools.newCameraFile(context)
                    cameraFile = file
                    takePicture.launch(FileProvider.getUriForFile(context, context.packageName + ".dosya", file))
                }.isSuccess
                if (!launched) toast("Kamera açılamadı.")
            },
            onVideo = {
                showAttach = false
                pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
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
        )
    }
    forwardMsg?.let { msg ->
        ForwardDialog(msg) { forwardMsg = null }
    }
    blockPeer?.let { peer ->
        val uname = peer.substringBefore(":").removePrefix("@")
        AlertDialog(
            onDismissRequest = { blockPeer = null },
            title = { Text("Engelle") },
            text = { Text("@$uname engellensin mi? Artık sana istek gönderemez ve kişilerinden çıkar.") },
            confirmButton = {
                TextButton(onClick = {
                    blockPeer = null
                    scope.launch {
                        if (Bayx.blockContact(uname)) { toast("@$uname engellendi."); onBack() } else toast("Engellenemedi.")
                    }
                }) { Text("Engelle", color = Color(0xFFB4232A)) }
            },
            dismissButton = { TextButton(onClick = { blockPeer = null }) { Text("Vazgeç") } },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Mesajı sil") },
            text = { Text("Bu mesaj herkesten silinsin mi? Sohbette \"Mesaj silindi\" yazar.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch { if (!Bayx.deleteMessage(session, target)) toast("Mesaj silinemedi.") }
                }) { Text("Herkesten sil", color = Color(0xFFA32117)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Vazgeç") } },
        )
    }
}

/** İfade paneli: hareketli ifadeler ve iki tarafta oynayan efektler. */
@Composable
private fun EmojiPanel(onEmoticon: (Emo) -> Unit, onEffect: (String) -> Unit) {
    Surface(color = Color.White, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text("İfadeler", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
            Text("Efektler (karşı tarafta da oynar)", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Bayx.EFFECTS.forEach { name ->
                    AssistChip(onClick = { onEffect(name) }, label = { Text(effectTitle(name), fontSize = 12.sp) })
                }
            }
        }
    }
}

private fun formatDay(millis: Long): String =
    SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("tr")).format(Date(millis))

private fun formatClock(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.forLanguageTag("tr")).format(Date(millis))

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    msg: ChatMessage,
    showSender: Boolean,
    onOpen: (ChatMessage) -> Unit,
    onLongPress: (ChatMessage) -> Unit = {},
) {
    if (msg.kind == MsgKind.EFFECT) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                (if (msg.mine) "Sen: " else msg.sender + ": ") + effectTitle(msg.text) + " · tekrar oynat",
                color = Color(0xFF4A2FB0),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEDE7FF))
                    .clickable { Bayx.effects.tryEmit(msg.text) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        return
    }
    if (msg.kind == MsgKind.NUDGE) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFFFF4D6))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Vibration, contentDescription = null, tint = Color(0xFF6B4A00), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (msg.mine) "Titreşim gönderdin" else "${msg.sender} sana titreşim gönderdi") + " · " + formatClock(msg.time),
                    color = Color(0xFF6B4A00),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        return
    }
    if (msg.kind == MsgKind.DATE) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                formatDay(msg.time),
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFEAF0F8))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
        return
    }
    val shape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomEnd = if (msg.mine) 6.dp else 22.dp,
        bottomStart = if (msg.mine) 22.dp else 6.dp,
    )
    val fg = Ink
    val sub = if (msg.mine) Color(0xFF8A7460) else Muted
    val visual = msg.kind == MsgKind.IMAGE || msg.kind == MsgKind.VIDEO
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = shape,
            color = if (msg.mine) Color.Transparent else Color.White,
            shadowElevation = if (msg.mine) 0.dp else 3.dp,
            modifier = Modifier
                .widthIn(max = 310.dp)
                .then(if (msg.mine) Modifier.shadow(3.dp, shape, ambientColor = SoftShadow, spotColor = SoftShadow) else Modifier)
                .clip(shape)
                .then(if (msg.mine) Modifier.background(PeachBrush) else Modifier)
                .combinedClickable(onClick = {}, onLongClick = { onLongPress(msg) }),
        ) {
            Column(
                Modifier.padding(horizontal = if (visual) 6.dp else 16.dp, vertical = if (visual) 6.dp else 10.dp),
            ) {
                if (showSender && !msg.mine) {
                    Text(
                        msg.sender,
                        color = Blue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = if (visual) 8.dp else 0.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                if (msg.kind == MsgKind.TEXT || msg.media == null) {
                    val big = singleEmoticon(msg.text)
                    if (big != null) {
                        Emoticon(big, size = 64.dp)
                    } else {
                        EmoticonText(msg.text, color = fg, fontSize = 16.sp)
                    }
                } else {
                    MediaContent(msg, tint = fg, onOpen = onOpen)
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.align(Alignment.End).padding(horizontal = if (visual) 8.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatClock(msg.time), color = sub, fontSize = 12.sp)
                    val stateIcon = when (msg.state) {
                        MsgState.SENDING -> Icons.Outlined.Schedule to "Gönderiliyor"
                        MsgState.SENT -> Icons.Filled.Done to "Gönderildi"
                        MsgState.READ -> Icons.Filled.DoneAll to "Okundu"
                        MsgState.FAILED -> Icons.Outlined.ErrorOutline to "Gönderilemedi"
                        MsgState.NONE -> null
                    }
                    if (stateIcon != null) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            stateIcon.first,
                            stateIcon.second,
                            tint = if (msg.state == MsgState.READ) Color(0xFF2D9CDB) else sub,
                            modifier = Modifier.size(if (msg.state == MsgState.READ) 17.dp else 15.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------- Profil ----------

@Composable
fun ProfileScreen(me: AuthState.LoggedIn, onBack: () -> Unit, onAdmin: () -> Unit, onWebLink: () -> Unit) {
    var confirmLogout by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val context = LocalContext.current
    val name = me.displayName.ifEmpty { me.userId.substringBefore(":").removePrefix("@") }
    val myMode by Bayx.myMode.collectAsState()
    val myStatus by Bayx.myStatus.collectAsState()
    val myAvatar by Bayx.myAvatarUrl.collectAsState()
    var editName by remember { mutableStateOf(false) }
    var editStatus by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Bayx.refreshMyAvatar() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            uploading = true
            scope.launch {
                val jpeg = withContext(Dispatchers.IO) { avatarJpeg(context, uri) }
                val ok = jpeg != null && Bayx.setAvatar(jpeg)
                uploading = false
                toast(if (ok) "Profil fotoğrafı güncellendi." else "Fotoğraf yüklenemedi.")
            }
        }
    }

    var isAdmin by remember { mutableStateOf(false) }
    var lockOn by remember { mutableStateOf(Gate.lockEnabled && Gate.lockAvailable()) }
    var shareDialog by remember { mutableStateOf<Triple<String, String, Api.CodeReply>?>(null) }
    var working by remember { mutableStateOf(false) }
    var emailState by remember { mutableStateOf<Api.EmailStatus?>(null) }
    var showEmail by remember { mutableStateOf(false) }
    val username = me.userId.substringBefore(":").removePrefix("@")
    LaunchedEffect(Unit) {
        val token = Bayx.accessToken ?: return@LaunchedEffect
        isAdmin = runCatching { Api.adminMembers(token); true }.getOrDefault(false)
        emailState = runCatching { Api.emailStatus(token) }.getOrNull()
    }
    fun withToken(block: suspend (String) -> Unit) {
        val token = Bayx.accessToken ?: return
        if (working) return
        working = true
        scope.launch {
            try {
                block(token)
            } catch (e: ApiException) {
                info = e.message
            }
            working = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(PageBrush)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text(
                "Profil & Ayarlar",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = Ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(124.dp)
                    .shadow(8.dp, CircleShape, ambientColor = SoftShadow, spotColor = SoftShadow)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color.White, Color(0xFFEDE3F4), Color(0xFFD9C7B4))))
                    .padding(5.dp)
                    .clickable(enabled = !uploading) {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
            ) {
                Avatar(name, 114.dp, url = myAvatar)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(BlueBrush),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uploading) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Outlined.PhotoCamera, "Fotoğrafı değiştir", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { editName = true }) {
                    Text(name, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (isAdmin) {
                        Spacer(Modifier.width(8.dp))
                        Row(
                            Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF1F1B18)).padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("♛", color = Color(0xFFF2C14E), fontSize = 12.sp)
                            Spacer(Modifier.width(3.dp))
                            Text("Yönetici", color = Color(0xFFF2C14E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    myStatus.ifBlank { "Durum mesajı ekle…" },
                    color = if (myStatus.isBlank()) SoftBlue else Muted,
                    fontSize = 16.sp,
                    fontStyle = if (myStatus.isBlank()) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { editStatus = true }.padding(vertical = 3.dp),
                )
                Text("@$username", color = SoftBlue, fontSize = 15.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { pickMode = true }.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Durum", color = Ink, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Row(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(ChipBg).padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(modeColor(myMode) ?: Color(0xFFB8AFA6)))
                    Spacer(Modifier.width(8.dp))
                    Text(modeLabel(myMode), color = Ink, fontSize = 15.sp)
                    Spacer(Modifier.width(18.dp))
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Ink, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                SettingRow(Icons.Outlined.Person, "Hesabım") {
                    info = "Kullanıcı adı: @$username\nSunucu: ${Api.SERVER_NAME}\n\nŞifren yok: hesabına kurtarma kodu, " +
                        "başka cihazdan giriş kodu ya da yöneticinin giriş bağlantısıyla girersin."
                }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Lock, "Gizlilik ve Güvenlik") {
                    info = "Mesajların uçtan uca şifrelidir. Bu cihazdaki oturum ve sohbet verileri şifreli saklanır; gelen " +
                        "fotoğraf, video ve sesler galeriye kaydedilmez. Çıkış yaptığında bu cihazdaki her şey silinir."
                }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Notifications, "Bildirimler") { openNotificationSettings(context) }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Fingerprint, "Uygulama kilidi", trailing = {
                    Switch(
                        checked = lockOn,
                        onCheckedChange = { on ->
                            if (on && !Gate.lockAvailable()) {
                                info = "Uygulama kilidi için önce telefonuna ekran kilidi ya da parmak izi tanımla."
                            } else {
                                Gate.lockEnabled = on
                                lockOn = on
                            }
                        },
                    )
                }) {
                    info = "Açıksa, Tayfa'ya 30 saniyeden uzun süre sonra döndüğünde parmak izi ya da ekran kilidi istenir."
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                SettingRow(
                    Icons.Outlined.AlternateEmail,
                    "E-posta",
                    trailing = {
                        val st = emailState
                        when {
                            st == null -> Unit
                            st.email.isBlank() -> Text("Ekle", color = SoftBlue, fontSize = 14.sp)
                            st.verified -> Text("Doğrulandı ✓", color = Color(0xFF2E9E5B), fontSize = 13.sp)
                            else -> Text("Doğrula", color = Color(0xFFE5872A), fontSize = 13.sp)
                        }
                    },
                ) { showEmail = true }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Computer, "Web'e bağlan", onClick = onWebLink)
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Password, "Web için geçici şifre") {
                    withToken { token ->
                        shareDialog = Triple(
                            "Web için geçici şifre",
                            "Tayfa Web bir güvenlik adımında şifre sorarsa bunu yaz (tireler dahil). 5 dakika sonra geçersiz olur.",
                            Api.CodeReply(Api.webPassword(token), ""),
                        )
                    }
                }
                if (isAdmin) {
                    HorizontalDivider(color = Line)
                    SettingRow(Icons.Outlined.HowToReg, "Yönetim ve davetler", onClick = onAdmin)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                SettingRow(Icons.Outlined.Info, "Uygulama Hakkında") {
                    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                    info = "Tayfa ${version.orEmpty()}\nÖzel, şifresiz ve uçtan uca şifreli sohbet."
                }
                HorizontalDivider(color = Line)
                SettingRow(Icons.AutoMirrored.Outlined.Logout, "Çıkış Yap", tint = Color(0xFFB4232A)) { confirmLogout = true }
            }
        }
        Spacer(Modifier.height(28.dp))
    }

    if (showEmail) {
        EmailDialog(
            status = emailState,
            onDismiss = { showEmail = false },
            onSave = { address ->
                withToken { token -> emailState = Api.emailSet(token, address) }
            },
            onResend = { withToken { token -> emailState = Api.emailResend(token); toast("Doğrulama e-postası yeniden gönderildi.") } },
            onRemove = { withToken { token -> emailState = Api.emailSet(token, "") } },
        )
    }
    shareDialog?.let { (title, text, reply) ->
        ShareCodeDialog(
            title = title,
            explanation = text,
            code = reply.code,
            url = reply.url.ifEmpty { null },
            shareMessage = null,
            sensitive = true,
            onDismiss = { shareDialog = null },
        )
    }
    if (editName) {
        TextInputDialog(
            title = "Görünen ad",
            initial = name,
            maxLength = 40,
            onDismiss = { editName = false },
        ) { value ->
            editName = false
            if (value.isNotBlank() && value != name) {
                scope.launch { toast(if (Bayx.setDisplayName(value.trim())) "Adın güncellendi." else "Ad değiştirilemedi.") }
            }
        }
    }
    if (editStatus) {
        TextInputDialog(
            title = "Durum mesajı",
            initial = myStatus,
            maxLength = 80,
            onDismiss = { editStatus = false },
        ) { value ->
            editStatus = false
            Bayx.setStatusMessage(value.trim())
        }
    }
    if (pickMode) {
        AlertDialog(
            onDismissRequest = { pickMode = false },
            title = { Text("Durumun") },
            text = {
                Column {
                    listOf("online", "busy", "away", "invisible").forEach { mode ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                pickMode = false
                                Bayx.setMode(mode)
                            }.padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(14.dp).clip(CircleShape).background(modeColor(mode) ?: Color(0xFFB8C2CC)))
                            Spacer(Modifier.width(12.dp))
                            Text(modeLabel(mode), fontSize = 17.sp, fontWeight = if (mode == myMode) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickMode = false }) { Text("Kapat") } },
        )
    }
    info?.let { message -> InfoDialog(message) { info = null } }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { if (!loggingOut) confirmLogout = false },
            title = { Text("Çıkış yap") },
            text = {
                Text("Oturumun kapatılacak ve bu cihazdaki tüm mesajlar, anahtarlar ve önbellek silinecek. Devam edilsin mi?")
            },
            confirmButton = {
                TextButton(enabled = !loggingOut, onClick = {
                    loggingOut = true
                    scope.launch { Bayx.logout() }
                }) { Text(if (loggingOut) "Çıkış yapılıyor…" else "Çıkış yap") }
            },
            dismissButton = {
                TextButton(enabled = !loggingOut, onClick = { confirmLogout = false }) { Text("Vazgeç") }
            },
        )
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    label: String,
    tint: Color = Ink,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, modifier = Modifier.weight(1f), color = tint, fontSize = 17.sp)
        if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SoftBlue)
    }
}

// ---------- Yönetim ----------

@Composable
fun AdminScreen(me: AuthState.LoggedIn, onBack: () -> Unit) {
    var data by remember { mutableStateOf<AdminMembers?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var resetFor by remember { mutableStateOf<Member?>(null) }
    var removeFor by remember { mutableStateOf<Member?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()

    LaunchedEffect(reload) {
        val token = Bayx.accessToken
        try {
            data = if (token == null) null else Api.adminMembers(token)
            error = null
        } catch (e: ApiException) {
            error = e.message
        }
    }

    fun act(action: String, member: Member, newPassword: String? = null, done: String) {
        if (busy) return
        val token = Bayx.accessToken ?: return
        busy = true
        scope.launch {
            try {
                Api.adminAction(token, action, member.userId, newPassword)
                toast(done)
            } catch (e: ApiException) {
                toast(e.message ?: "İşlem tamamlanamadı.")
            }
            busy = false
            reload++
        }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text("Yönetim", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
        }
        val current = data
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(error ?: "", color = Muted, textAlign = TextAlign.Center)
            }
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Blue)
            }
            else -> {
                val shown = current.members.filter {
                    query.isBlank() || it.displayName.contains(query, true) || it.userId.contains(query, true)
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("Üye sayısı", color = Muted, fontSize = 13.sp)
                                Text("${current.count} / ${current.max} kişi", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { (current.count.toFloat() / current.max.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Blue,
                                    trackColor = Line,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Yönetici olarak her üyenin şifresini sıfırlayabilir ya da hesabını kapatabilirsin.",
                                    color = Muted,
                                    fontSize = 12.5.sp,
                                )
                            }
                        }
                    }
                    item { AppConfigCard() }
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Üye ara", color = Muted) },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted) },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item { SectionTitle("Üyeler (${shown.size})") }
                    items(shown, key = { it.userId }) { member ->
                        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                MemberHeader(member)
                                if (member.userId != me.userId) {
                                    Row {
                                        TextButton(enabled = !busy, onClick = { resetFor = member }) { Text("Şifre sıfırla") }
                                        TextButton(enabled = !busy, onClick = { removeFor = member }) {
                                            Text("Hesabı kapat", color = Color(0xFFA32117))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    resetFor?.let { member ->
        var newPassword by remember(member.userId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { resetFor = null },
            title = { Text("Şifre sıfırla") },
            text = {
                Column {
                    Text("${member.displayName} için yeni bir şifre belirle. Açık oturumları kapatılır; bu şifreyi kendisine ilet.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        singleLine = true,
                        label = { Text("Yeni şifre (en az 8 karakter)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = newPassword.length >= 8, onClick = {
                    act("reset-password", member, newPassword, done = "Şifre değiştirildi.")
                    resetFor = null
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { resetFor = null }) { Text("Vazgeç") } },
        )
    }

    removeFor?.let { member ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text("Hesabı kapat") },
            text = { Text("${member.displayName} üyelikten çıkarılacak ve hesabı kapatılacak. Bu işlem geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = {
                    act("remove", member, done = "Hesap kapatıldı.")
                    removeFor = null
                }) { Text("Kapat", color = Color(0xFFA32117)) }
            },
            dismissButton = { TextButton(onClick = { removeFor = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
}

@Composable
private fun MemberHeader(member: Member) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(member.displayName, 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(member.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(member.userId.substringBefore(":").removePrefix("@"), color = Muted, fontSize = 13.sp)
        }
        if (member.admin) Text("Yönetici", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}


@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    maxLength: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { if (it.length <= maxLength) value = it },
                singleLine = true,
                supportingText = { Text("${value.length}/$maxLength") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Kaydet") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

/** Seçilen resmi ortadan kare kırpar, 512 piksele indirger ve JPEG yapar. */
private fun avatarJpeg(context: android.content.Context, uri: android.net.Uri): ByteArray? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    val src = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        ?: return@runCatching null
    val side = minOf(src.width, src.height)
    val matrix = android.graphics.Matrix().apply {
        val scale = minOf(1f, 512f / side)
        postScale(scale, scale)
    }
    val square = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side, matrix, true)
    java.io.ByteArrayOutputStream().use { out ->
        square.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    }
}.getOrNull()

@Composable
private fun EmailDialog(
    status: Api.EmailStatus?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onResend: () -> Unit,
    onRemove: () -> Unit,
) {
    var value by remember { mutableStateOf(status?.email ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("E-posta") },
        text = {
            Column {
                when {
                    status == null -> Text("E-posta durumu yükleniyor…", color = Muted)
                    !status.mailReady -> Text("Sunucuda e-posta gönderimi henüz ayarlı değil. Yönetici ayarladığında kullanabilirsin.", color = Muted, fontSize = 13.5.sp)
                    status.email.isNotBlank() && status.verified -> Text("E-postan doğrulandı. Şifreni unutursan bu adrese sıfırlama kodu gönderilir.", color = Muted, fontSize = 13.5.sp)
                    status.email.isNotBlank() -> Text("Doğrulama bağlantısı ${status.email} adresine gönderildi. Gelen kutunu ve istenmeyen klasörünü kontrol et. Kalan süre: ${status.daysLeft} gün.", color = Color(0xFFB07A2E), fontSize = 13.5.sp)
                    else -> Text("Şifreni unutursan e-postayla sıfırlayabilmen için bir adres ekle. İsteğe bağlıdır.", color = Muted, fontSize = 13.5.sp)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    singleLine = true,
                    label = { Text("E-posta adresi") },
                    enabled = status?.mailReady != false,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (status != null && status.email.isNotBlank() && !status.verified) {
                    TextButton(onClick = onResend) { Text("Doğrulama e-postasını yeniden gönder") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = value.contains("@") && value != status?.email,
                onClick = { onSave(value); onDismiss() },
            ) { Text("Kaydet") }
        },
        dismissButton = {
            if (status != null && status.email.isNotBlank()) {
                TextButton(onClick = { onRemove(); onDismiss() }) { Text("Kaldır", color = Muted) }
            } else {
                TextButton(onClick = onDismiss) { Text("Kapat") }
            }
        },
    )
}

/** Admin paneli: duyuru ve güncelleme bilgisini sunucudan yönet (modüler). */
@Composable
private fun AppConfigCard() {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var announcement by remember { mutableStateOf("") }
    var apkUrl by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val token = Bayx.accessToken ?: return@LaunchedEffect
        runCatching { Api.getConfig(token) }.getOrNull()?.let {
            announcement = it.announcement
            apkUrl = it.apkUrl
            version = if (it.latestVersion > 0) it.latestVersion.toString() else ""
            loaded = true
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Duyuru ve güncelleme", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                "Buradan yazdığın duyuru tüm üyelere anında gösterilir. Yeni APK yayınlayınca bağlantısını ve sürüm " +
                    "numarasını gir; eski sürümdeki üyeler \"Yeni sürüm var\" uyarısı alır.",
                color = Muted, fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = announcement,
                onValueChange = { announcement = it.take(500) },
                label = { Text("Duyuru (boş bırakılırsa gösterilmez)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = apkUrl,
                onValueChange = { apkUrl = it.trim() },
                singleLine = true,
                label = { Text("APK bağlantısı (https://)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = version,
                onValueChange = { v -> version = v.filter { it.isDigit() }.take(6) },
                singleLine = true,
                label = { Text("En son sürüm numarası (versionCode)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            SandButton("Kaydet", busy = busy) {
                val token = Bayx.accessToken ?: return@SandButton
                busy = true
                scope.launch {
                    try {
                        Api.setConfig(token, announcement.trim(), version.toIntOrNull() ?: 0, apkUrl.trim())
                        toast("Kaydedildi.")
                    } catch (e: ApiException) {
                        toast(e.message ?: "Kaydedilemedi.")
                    }
                    busy = false
                }
            }
        }
    }
}

/** Mesaja uzun basınca açılan eylem menüsü: yanıtla, ilet, düzenle, sil. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    msg: ChatMessage,
    canEdit: Boolean,
    canDelete: Boolean,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            ActionRow(Icons.AutoMirrored.Outlined.Reply, "Yanıtla", onReply)
            if (msg.kind == MsgKind.TEXT) ActionRow(Icons.AutoMirrored.Outlined.Send, "İlet", onForward)
            if (canEdit) ActionRow(Icons.Outlined.Edit, "Düzenle", onEdit)
            if (canDelete) ActionRow(Icons.Outlined.Delete, "Sil", onDelete, tint = Color(0xFFB4232A))
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Ink) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, color = tint, fontSize = 17.sp)
    }
}

/** İletme: kişilerden birini seç, metni ona ilet. */
@Composable
private fun ForwardDialog(msg: ChatMessage, onDismiss: () -> Unit) {
    val contacts by Bayx.contacts.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("İlet") },
        text = {
            if (contacts.friends.isEmpty()) {
                Text("İletebileceğin bir kişi yok.", color = Muted)
            } else {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(contacts.friends, key = { it.userId }) { friend ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                busy = true
                                scope.launch {
                                    val roomId = Bayx.startDirectChat(friend.userId)
                                    val ok = roomId != null && Bayx.forwardText(roomId, msg.text)
                                    busy = false
                                    toast(if (ok) "${friend.displayName} kişisine iletildi." else "İletilemedi.")
                                    onDismiss()
                                }
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(friend.displayName, 42.dp, url = friend.avatarUrl)
                            Spacer(Modifier.width(12.dp))
                            Text(friend.displayName, fontSize = 16.sp, color = Ink)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}
