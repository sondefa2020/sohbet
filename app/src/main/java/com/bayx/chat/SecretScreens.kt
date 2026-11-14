package com.bayx.chat

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.TimerOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Api
import com.bayx.chat.data.ApiException
import com.bayx.chat.data.AuthState
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.ChatMessage
import com.bayx.chat.data.ChatSession
import com.bayx.chat.data.Member
import com.bayx.chat.data.MsgKind
import com.bayx.chat.data.SecretLock
import kotlinx.coroutines.launch

private val SecretBg = Brush.verticalGradient(listOf(Color(0xFF1B1530), Color(0xFF0E0B1C)))
private val SecretAccent = Color(0xFFB79CFF)

/** Gizli ekranlarda ekran görüntüsü ve ekran kaydı engellenir. */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

fun lifetimeLabel(ms: Long): String = when {
    ms <= 0L -> "kaybolmaz"
    ms < 3_600_000L -> "${ms / 60_000} dakika"
    ms < 86_400_000L -> "${ms / 3_600_000} saat"
    else -> "${ms / 86_400_000} gün"
}

/** Gizli sohbetler bölümü: PIN ile açılır, içeride yalnızca gizli sohbetler listelenir. */
@Composable
fun SecretAreaScreen(me: AuthState.LoggedIn, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    SecureWindow()
    val unlocked by SecretLock.unlocked.collectAsState()
    if (!unlocked) {
        PinScreen(onBack = onBack)
    } else {
        SecretListScreen(me, onBack, onOpenChat)
    }
}

@Composable
private fun PinScreen(onBack: () -> Unit) {
    val creating = remember { !SecretLock.hasPin() }
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var message by remember {
        mutableStateOf(if (creating) t("Gizli sohbetler için 4 haneli bir PIN belirle") else "PIN'ini gir")
    }

    fun submit(value: String) {
        if (creating) {
            if (first == null) {
                first = value
                pin = ""
                message = "PIN'i tekrar gir"
            } else if (first == value) {
                SecretLock.setPin(value)
            } else {
                first = null
                pin = ""
                message = t("PIN'ler aynı değil. Baştan belirle.")
            }
        } else {
            when (val result = SecretLock.tryUnlock(value)) {
                SecretLock.Result.Ok -> Unit
                SecretLock.Result.Wrong -> {
                    pin = ""
                    message = t("PIN yanlış. Tekrar dene.")
                }
                is SecretLock.Result.Wait -> {
                    pin = ""
                    message = "Çok fazla yanlış deneme. ${result.seconds} saniye bekle."
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(SecretBg).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White) }
        }
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(Color(0x33B79CFF)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Lock, contentDescription = null, tint = SecretAccent, modifier = Modifier.size(40.dp)) }
        Spacer(Modifier.height(20.dp))
        Text("Gizli sohbetler", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Color(0xFFCFC6E8), fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (i < pin.length) SecretAccent else Color(0x33FFFFFF)),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    row.forEach { key ->
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(if (key.isEmpty()) Color.Transparent else Color(0x1FFFFFFF))
                                .clickable(enabled = key.isNotEmpty()) {
                                    if (key == "<") {
                                        pin = pin.dropLast(1)
                                    } else if (pin.length < 4) {
                                        pin += key
                                        if (pin.length == 4) submit(pin)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (key == "<") {
                                Icon(Icons.AutoMirrored.Filled.Backspace, "Sil", tint = Color.White)
                            } else {
                                Text(key, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SecretListScreen(me: AuthState.LoggedIn, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val rooms by Bayx.rooms.collectAsState()
    val secretRooms = rooms.filter { it.secret }
    var showNew by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()

    Box(Modifier.fillMaxSize().background(SecretBg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text("Gizli sohbetler", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Ekran görüntüsü alınamaz · mesajlar kaybolur", color = Color(0xFFCFC6E8), fontSize = 12.5.sp)
                }
                TextButton(onClick = { SecretLock.lock(); onBack() }) { Text("Kilitle", color = SecretAccent) }
            }
            if (secretRooms.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Henüz gizli sohbet yok.\nAşağıdaki düğmeyle bir kişiyle gizli sohbet başlat.",
                        color = Color(0xFFCFC6E8),
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(secretRooms, key = { it.id }) { chat ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenChat(chat.id) }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(chat.name, 54.dp, url = chat.avatarUrl)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(chat.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(
                                    chat.lastMessage.ifEmpty { "Gizli sohbet" },
                                    color = Color(0xFFCFC6E8),
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (chat.unread > 0) {
                                Box(
                                    Modifier.size(24.dp).clip(CircleShape).background(SecretAccent),
                                    contentAlignment = Alignment.Center,
                                ) { Text("${chat.unread}", color = Color(0xFF1B1530), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { if (!creating) showNew = true },
            containerColor = SecretAccent,
            contentColor = Color(0xFF1B1530),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Gizli sohbet başlat", fontWeight = FontWeight.Bold) },
        )
    }

    if (showNew) {
        NewSecretDialog(
            me = me,
            onDismiss = { showNew = false },
            onCreate = { member, lifetime ->
                showNew = false
                creating = true
                scope.launch {
                    val roomId = Bayx.createSecretChat(member.userId, lifetime)
                    creating = false
                    if (roomId != null) onOpenChat(roomId) else toast("Gizli sohbet açılamadı. Tekrar dene.")
                }
            },
        )
    }
}

@Composable
private fun NewSecretDialog(me: AuthState.LoggedIn, onDismiss: () -> Unit, onCreate: (Member, Long) -> Unit) {
    var members by remember { mutableStateOf<List<Member>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Member?>(null) }
    val lifetimes = listOf(3_600_000L, 86_400_000L, 7 * 86_400_000L)
    var lifetime by remember { mutableLongStateOf(86_400_000L) }
    LaunchedEffect(Unit) {
        val token = Bayx.accessToken ?: return@LaunchedEffect
        try {
            members = Api.members(token).filter { it.userId != me.userId }
        } catch (e: ApiException) {
            error = e.message
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeni gizli sohbet") },
        text = {
            Column {
                Text("Mesajlar şu süre sonra kaybolsun:", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    lifetimes.forEach { value ->
                        FilterChip(
                            selected = lifetime == value,
                            onClick = { lifetime = value },
                            label = { Text(lifetimeLabel(value)) },
                            leadingIcon = if (lifetime == value) {
                                { Icon(Icons.Outlined.TimerOff, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                        )
                    }
                }
                Text("Kiminle?", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                when {
                    error != null -> Text(error ?: "", color = Muted)
                    members == null -> CircularProgressIndicator(color = Blue)
                    members!!.isEmpty() -> Text("Başka üye yok.", color = Muted)
                    else -> Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        members!!.forEach { member ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected?.userId == member.userId) Color(0xFFE9E4FF) else Color.Transparent)
                                    .clickable { selected = member }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(member.displayName, 36.dp, url = member.avatarUrl)
                                Spacer(Modifier.width(10.dp))
                                Text(member.displayName, color = Ink)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let { onCreate(it, lifetime) } }) { Text("Başlat") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

// ------------------------------------------------------------- medya galerisi

/** Bir sohbette paylaşılan fotoğraf ve videolar. */
@Composable
fun MediaGalleryDialog(session: ChatSession, onDismiss: () -> Unit) {
    val messages by session.messages.collectAsState()
    val media = messages.filter { it.kind == MsgKind.IMAGE || it.kind == MsgKind.VIDEO }.reversed()
    var viewer by remember { mutableStateOf<ChatMessage?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Medya") },
        text = {
            if (media.isEmpty()) {
                Text("Bu sohbette henüz fotoğraf ya da video yok.", color = Muted)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    items(media, key = { it.key }) { msg ->
                        MediaThumb(msg, Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { viewer = msg })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
    viewer?.let { msg -> MediaViewerDialog(msg) { viewer = null } }
}
