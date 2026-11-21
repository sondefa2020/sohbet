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
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ContentCopy
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MessageRow(
    msg: ChatMessage,
    showSender: Boolean,
    onOpen: (ChatMessage) -> Unit,
    onLongPress: (ChatMessage) -> Unit = {},
    onReact: (ChatMessage, String) -> Unit = { _, _ -> },
    onVote: (PollInfo, String) -> Unit = { _, _ -> },
    onSwipeReply: (ChatMessage) -> Unit = {},
    onQuote: (String) -> Unit = {},
    onSender: (String) -> Unit = {},
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
                    (if (msg.mine) t("Titreşim gönderdin") else tf("%s sana titreşim gönderdi", msg.sender)) + " · " + formatClock(msg.time),
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
                    .background(if (ThemeState.dark) Color(0xFF2A2521) else Color(0xFFEAF0F8))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
        return
    }
    // WhatsApp gibi: köşesi gönderen tarafında üstte sivri balon.
    val shape = RoundedCornerShape(
        topStart = if (msg.mine) 16.dp else 4.dp,
        topEnd = if (msg.mine) 4.dp else 16.dp,
        bottomEnd = 16.dp,
        bottomStart = 16.dp,
    )
    val fg = if (msg.mine && ThemeState.dark) Color(0xFFFCEDEC) else Ink
    val sub = if (msg.mine) (if (ThemeState.dark) Color(0xFFE7B2AE) else Color(0xFF7D6551)) else Muted
    val visual = msg.kind == MsgKind.IMAGE || msg.kind == MsgKind.VIDEO
    // Sağa kaydırınca yanıtla (WhatsApp gibi).
    val density = androidx.compose.ui.platform.LocalDensity.current
    val trigger = with(density) { 64.dp.toPx() }
    var dragX by remember(msg.key) { mutableStateOf(0f) }
    val shownX by androidx.compose.animation.core.animateFloatAsState(dragX, label = "kaydir")
    val starredList by com.bayx.chat.data.Starred.items.collectAsState()
    val starred = starredList.any { it.key == msg.key }
    Box(Modifier.fillMaxWidth()) {
        if (shownX > 8f) {
            Icon(
                Icons.AutoMirrored.Outlined.Reply, null, tint = Blue.copy(alpha = (shownX / trigger).coerceIn(0f, 1f)),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).size(24.dp),
            )
        }
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { translationX = shownX }
            .then(
                if (msg.eventId != null) Modifier.pointerInput(msg.key) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (dragX >= trigger) onSwipeReply(msg); dragX = 0f },
                        onDragCancel = { dragX = 0f },
                    ) { change, amount ->
                        val next = (dragX + amount).coerceIn(0f, trigger * 1.25f)
                        if (next != dragX) { dragX = next; change.consume() }
                    }
                } else Modifier,
            ),
        horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = shape,
            color = if (msg.mine) Color.Transparent else (if (ThemeState.dark) Color(0xFF1E1E21) else Color.White),
            shadowElevation = if (msg.mine) 0.dp else 1.dp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .then(if (msg.mine) Modifier.shadow(1.dp, shape) else Modifier)
                .clip(shape)
                .then(if (msg.mine) Modifier.background(PeachBrush) else Modifier)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onLongPress(msg) },
                    // Çift dokun: kalp bırak.
                    onDoubleClick = if (msg.eventId != null) ({ onReact(msg, "❤️") }) else null,
                ),
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
                        modifier = Modifier.padding(horizontal = if (visual) 8.dp else 0.dp)
                            .then(if (msg.senderId.isNotEmpty()) Modifier.clickable { onSender(msg.senderId) } else Modifier),
                    )
                    Spacer(Modifier.height(2.dp))
                }
                // Yanıtlanan mesajın alıntısı (WhatsApp gibi): dokununca o mesaja gider.
                msg.replyTo?.let { r ->
                    Row(
                        Modifier
                            .padding(horizontal = if (visual) 2.dp else 0.dp)
                            .padding(bottom = 6.dp)
                            .widthIn(min = 120.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (msg.mine) Color.Black.copy(alpha = 0.06f) else Blue.copy(alpha = 0.08f))
                            .clickable(enabled = r.ready) { onQuote(r.eventId) }
                            .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                    ) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(if (r.mine) Color(0xFF1E8E4E) else Blue))
                        Column(Modifier.padding(horizontal = 9.dp, vertical = 6.dp)) {
                            if (r.ready) {
                                Text(
                                    if (r.mine) t("Sen") else r.sender, color = if (r.mine) Color(0xFF1E8E4E) else Blue,
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(r.text, color = sub, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                val poll = msg.poll
                if (msg.kind == MsgKind.POLL && poll != null) {
                    PollContent(poll, fg, sub) { answer -> onVote(poll, answer) }
                } else if (msg.kind == MsgKind.TEXT || msg.media == null) {
                    val big = singleEmoticon(msg.text)
                    if (big != null) {
                        Emoticon(big, size = 64.dp)
                    } else {
                        EmoticonText(msg.text, color = fg, fontSize = 16.sp, links = true)
                    }
                } else {
                    MediaContent(msg, tint = fg, onOpen = onOpen)
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.align(Alignment.End).padding(horizontal = if (visual) 8.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (starred) {
                        Icon(Icons.Filled.Star, t("Yıldızlı"), tint = sub, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(formatClock(msg.time), color = sub, fontSize = 12.sp)
                    val stateIcon = when (msg.state) {
                        MsgState.SENDING -> Icons.Outlined.Schedule to t("Gönderiliyor")
                        MsgState.SENT -> Icons.Filled.Done to t("Gönderildi")
                        MsgState.READ -> Icons.Filled.DoneAll to "Okundu"
                        MsgState.FAILED -> Icons.Outlined.ErrorOutline to t("Gönderilemedi")
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
    if (msg.reactions.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start,
        ) {
            msg.reactions.forEach { r ->
                Text(
                    if (r.count > 1) "${r.key} ${r.count}" else r.key,
                    color = Ink,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (r.mine) Blue.copy(alpha = 0.22f) else ChipBg)
                        .clickable { onReact(msg, r.key) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** Sohbet içindeki anket: seçeneğe dokununca oy verilir, çubuklar oy oranını gösterir. */
@Composable
internal fun PollContent(poll: PollInfo, fg: Color, sub: Color, onVote: (String) -> Unit) {
    Column(Modifier.widthIn(min = 220.dp, max = 270.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Poll, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(poll.question, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        poll.answers.forEach { (id, text) ->
            val count = poll.counts[id] ?: 0
            val share = if (poll.total > 0) count.toFloat() / poll.total else 0f
            val chosen = id in poll.mine
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !poll.ended) { onVote(id) }
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen, onClick = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text, color = fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text("$count", color = sub, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(ChipBg)) {
                    Box(Modifier.fillMaxWidth(share).fillMaxHeight().background(if (chosen) Blue else SoftBlue))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (poll.ended) t("Anket bitti") else tf("%s oy · oy vermek için seçeneğe dokun", poll.total),
            color = sub, fontSize = 12.sp,
        )
    }
}

/** Anket oluşturma: soru + 2-6 seçenek. */
@Composable
internal fun PollComposer(onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var question by remember { mutableStateOf("") }
    val answers = remember { mutableStateListOf("", "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Anket oluştur")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = question, onValueChange = { question = it.take(200) },
                    label = { Text(t("Soru")) }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                answers.forEachIndexed { i, a ->
                    OutlinedTextField(
                        value = a, onValueChange = { answers[i] = it.take(100) },
                        label = { Text(tf("Seçenek %s", i + 1)) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    )
                }
                if (answers.size < 6) {
                    TextButton(onClick = { answers.add("") }) { Text(t("+ Seçenek ekle"), color = Blue) }
                }
            }
        },
        confirmButton = {
            val clean = answers.map { it.trim() }.filter { it.isNotEmpty() }
            TextButton(
                enabled = question.isNotBlank() && clean.size >= 2,
                onClick = { onCreate(question.trim(), clean) },
            ) { Text(t("Gönder")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Vazgeç")) } },
    )
}

// ---------- Profil ----------

/** Mesaja uzun basınca açılan eylem menüsü: yanıtla, ilet, düzenle, sil. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionSheet(
    msg: ChatMessage,
    canEdit: Boolean,
    canDelete: Boolean,
    onReply: () -> Unit,
    onForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit = {},
    onRemind: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onStar: (() -> Unit)? = null,
    starred: Boolean = false,
    onPin: (() -> Unit)? = null,
    pinned: Boolean = false,
    onInfo: (() -> Unit)? = null,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardBg) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            if (msg.eventId != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf(t("👍"), "❤️", t("😂"), t("😮"), t("😢"), t("🙏")).forEach { e ->
                        val mine = msg.reactions.any { it.key == e && it.mine }
                        Text(
                            e, fontSize = 28.sp,
                            modifier = Modifier.clip(CircleShape)
                                .background(if (mine) Blue.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { onReact(e) }
                                .padding(8.dp),
                        )
                    }
                }
                HorizontalDivider(color = Line)
            }
            ActionRow(Icons.AutoMirrored.Outlined.Reply, t("Yanıtla"), onReply)
            if (msg.kind == MsgKind.TEXT) ActionRow(Icons.AutoMirrored.Outlined.Send, t("İlet"), onForward)
            if (onCopy != null && msg.text.isNotBlank()) ActionRow(Icons.Outlined.ContentCopy, t("Kopyala"), onCopy)
            if (onStar != null) ActionRow(if (starred) Icons.Outlined.StarOutline else Icons.Filled.Star, if (starred) t("Yıldızı kaldır") else t("Yıldızla"), onStar)
            if (onPin != null && msg.eventId != null) ActionRow(Icons.Outlined.PushPin, if (pinned) t("Sabitlemeyi kaldır") else t("Sabitle"), onPin)
            if (onRemind != null) ActionRow(Icons.Outlined.Alarm, t("Hatırlat"), onRemind)
            if (onInfo != null && msg.mine) ActionRow(Icons.Outlined.Info, t("Bilgi"), onInfo)
            if (canEdit) ActionRow(Icons.Outlined.Edit, t("Düzenle"), onEdit)
            if (canDelete) ActionRow(Icons.Outlined.Delete, t("Sil"), onDelete, tint = Color(0xFFB4232A))
        }
    }
}

@Composable
internal fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Ink) {
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
internal fun ForwardDialog(msg: ChatMessage, onDismiss: () -> Unit) {
    val contacts by ContactsRepo.contacts.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("İlet")) },
        text = {
            if (contacts.friends.isEmpty()) {
                Text(t("İletebileceğin bir kişi yok."), color = Muted)
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
                                    toast(if (ok) tf("%s kişisine iletildi.", friend.displayName) else t("İletilemedi."))
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
