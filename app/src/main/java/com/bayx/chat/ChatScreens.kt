package com.bayx.chat

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
private fun ChatContent(session: ChatSession, onBack: () -> Unit) {
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

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Avatar(title, 52.dp, url = headerAvatar, mode = partnerPresence?.mode ?: if (partnerOnline) "online" else null)
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
            IconButton(onClick = {
                val now = System.currentTimeMillis()
                if (now - lastNudge < 5_000) {
                    toast("Biraz bekle; titreşim 5 saniyede bir gönderilebilir.")
                } else {
                    lastNudge = now
                    scope.launch { if (!Bayx.sendNudge(session)) toast("Titreşim gönderilemedi.") }
                }
            }) {
                Icon(Icons.Outlined.Vibration, "Titreşim gönder", tint = Color(0xFF8A4B00))
            }
            IconButton(onClick = { placeCall(false) }) {
                Icon(Icons.Outlined.Call, "Sesli ara", tint = Ink)
            }
            IconButton(onClick = { placeCall(true) }) {
                Icon(Icons.Outlined.Videocam, "Görüntülü ara", tint = Ink, modifier = Modifier.size(28.dp))
            }
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
                }
            }
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
                    onLongPress = { if (it.mine && it.eventId != null) deleteTarget = it },
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
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = Color.White, shadowElevation = 4.dp, modifier = Modifier.size(48.dp)) {
                    IconButton(onClick = { showAttach = true }) { Icon(Icons.Filled.Add, "Ekle", tint = Ink) }
                }
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color.White,
                    shadowElevation = 4.dp,
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
                                draft = ""
                                scope.launch {
                                    if (!Bayx.sendText(session, text)) toast("Mesaj gönderilemedi.")
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

    viewer?.let { msg -> MediaViewerDialog(msg) { viewer = null } }
    if (showGallery) MediaGalleryDialog(session) { showGallery = false }
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
    val fg = if (msg.mine) Color.White else Ink
    val sub = if (msg.mine) Color.White else Muted
    val visual = msg.kind == MsgKind.IMAGE || msg.kind == MsgKind.VIDEO
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = shape,
            color = if (msg.mine) Blue else Color.White,
            shadowElevation = 3.dp,
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(shape)
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
                            tint = if (msg.state == MsgState.READ) Color(0xFF9EF0FF) else sub,
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
fun ProfileScreen(me: AuthState.LoggedIn, onBack: () -> Unit, onAdmin: () -> Unit) {
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

    Column(
        Modifier
            .fillMaxSize()
            .background(PageBrush)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text(
                "Profilim",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = Ink,
                fontSize = 23.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.clickable(enabled = !uploading) {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
        ) {
            Avatar(name, 148.dp, url = myAvatar, mode = myMode)
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Blue),
                contentAlignment = Alignment.Center,
            ) {
                if (uploading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Icon(Icons.Outlined.PhotoCamera, "Fotoğrafı değiştir", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { editName = true }) {
            Text(name, color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.Edit, "Adı düzenle", tint = SoftBlue, modifier = Modifier.size(20.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { pickMode = true }.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(modeColor(myMode) ?: Muted))
            Spacer(Modifier.width(6.dp))
            Text(modeLabel(myMode) + " ▾", color = SoftBlue, fontSize = 17.sp)
        }
        Text(
            myStatus.ifBlank { "Durum mesajı ekle…" },
            color = if (myStatus.isBlank()) Muted else Ink,
            fontSize = 15.sp,
            fontStyle = if (myStatus.isBlank()) FontStyle.Italic else FontStyle.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { editStatus = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(24.dp))
        Surface(
            modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            shadowElevation = 5.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SettingRow(Icons.Outlined.Person, "Hesap Bilgileri") {
                    info = "Kullanıcı adı: " + me.userId.substringBefore(":").removePrefix("@") + "\nSunucu: " + Api.SERVER_NAME
                }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Shield, "Gizlilik ve Güvenlik") {
                    info = "Mesajların uçtan uca şifrelidir. Bu cihazdaki sohbet verileri şifreli saklanır ve çıkış " +
                        "yaptığında tamamen silinir."
                }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Notifications, "Bildirimler") { openNotificationSettings(context) }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Storage, "Depolama ve Medya") {
                    info = "Gelen fotoğraf, video ve sesler galeriye kaydedilmez; yalnızca uygulamanın kendi şifreli " +
                        "alanında tutulur ve çıkış yaptığında silinir. Bir sohbetin medyasını görmek için sohbette ⋮ > " +
                        "Fotoğraf ve videolar'a ya da ana ekrandaki Medya sekmesine dokun."
                }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.HowToReg, "Üyelik Onayları", onClick = onAdmin)
                HorizontalDivider(color = Line)
                SettingRow(Icons.AutoMirrored.Outlined.Logout, "Çıkış Yap") { confirmLogout = true }
            }
        }
        Spacer(Modifier.height(24.dp))
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
private fun SettingRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, modifier = Modifier.weight(1f), color = Ink, fontSize = 16.5.sp)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ink)
    }
}

// ---------- Yönetim ----------

@Composable
fun AdminScreen(me: AuthState.LoggedIn, onBack: () -> Unit) {
    var data by remember { mutableStateOf<AdminMembers?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
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

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text("Üyelik Onayları", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
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
                val pending = current.members.filter { it.pending }
                val active = current.members.filter { !it.pending }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("Üye kapasitesi", color = Muted, fontSize = 13.sp)
                                Text(
                                    "${current.count} / ${current.max} kişi",
                                    color = Ink,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                )
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { (current.count.toFloat() / current.max.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Blue,
                                    trackColor = Line,
                                )
                            }
                        }
                    }
                    item { SectionTitle("Onay bekleyenler (${pending.size})") }
                    if (pending.isEmpty()) {
                        item { Text("Onay bekleyen kayıt yok.", color = Muted, modifier = Modifier.padding(horizontal = 4.dp)) }
                    }
                    items(pending, key = { it.userId }) { member ->
                        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                MemberHeader(member)
                                Spacer(Modifier.height(12.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        enabled = !busy,
                                        onClick = { act("reject", member, done = "Kayıt reddedildi.") },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                    ) { Text("Reddet", color = Color(0xFFA32117)) }
                                    Button(
                                        enabled = !busy,
                                        onClick = { act("approve", member, done = "${member.displayName} onaylandı.") },
                                        modifier = Modifier.weight(1f).height(46.dp),
                                    ) { Text("Onayla") }
                                }
                            }
                        }
                    }
                    item { SectionTitle("Üyeler (${active.size})") }
                    items(active, key = { it.userId }) { member ->
                        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                MemberHeader(member)
                                if (member.userId != me.userId) {
                                    Row {
                                        TextButton(enabled = !busy, onClick = { resetFor = member }) { Text("Şifre sıfırla") }
                                        TextButton(enabled = !busy, onClick = { removeFor = member }) {
                                            Text("Üyelikten çıkar", color = Color(0xFFA32117))
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
                    Text("${member.displayName} için yeni şifre belirle. Açık oturumları kapatılır.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        singleLine = true,
                        label = { Text("Yeni şifre (en az 8 karakter)") },
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
            title = { Text("Üyelikten çıkar") },
            text = { Text("${member.displayName} üyelikten çıkarılacak ve hesabı kapatılacak. Bu işlem geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = {
                    act("remove", member, done = "Üye çıkarıldı.")
                    removeFor = null
                }) { Text("Çıkar", color = Color(0xFFA32117)) }
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
