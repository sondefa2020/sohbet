package com.bayx.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BlueBrush = Brush.verticalGradient(listOf(BlueLight, BlueDark))
private val SoftShadow = Color(0x33153E82)

// ---------- Ortak parçalar ----------

private val avatarTints = listOf(
    Color(0xFFFFE3EC) to Color(0xFFA3204E),
    Color(0xFFDCE9FF) to Color(0xFF1247A8),
    Color(0xFFDDF5E6) to Color(0xFF136A38),
    Color(0xFFFFEBD2) to Color(0xFF8A4B00),
    Color(0xFFE9E4FF) to Color(0xFF4A2FB0),
)

@Composable
fun Avatar(name: String, size: Dp, online: Boolean = false) {
    val (bg, fg) = avatarTints[(name.firstOrNull()?.code ?: 0) % avatarTints.size]
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase(),
                color = fg,
                fontSize = (size.value * 0.36f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.24f)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Online),
            )
        }
    }
}

@Composable
fun Wordmark(nameSize: TextUnit, subSize: TextUnit, centered: Boolean) {
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            "Bayx",
            fontSize = nameSize,
            fontWeight = FontWeight.ExtraBold,
            fontStyle = FontStyle.Italic,
            color = Ink,
        )
        Text(
            "Chat",
            fontSize = subSize,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            color = SoftBlue,
        )
    }
}

@Composable
private fun GradientButton(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(31.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .shadow(10.dp, shape, ambientColor = Blue, spotColor = Blue)
            .clip(shape)
            .background(BlueBrush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(40.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White)
        }
    }
}

@Composable
private fun PillField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    isPassword: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = SoftShadow, spotColor = SoftShadow),
        placeholder = { Text(placeholder, color = Muted) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = Ink) },
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "Şifreyi gizle" else "Şifreyi göster",
                        tint = Ink,
                    )
                }
            }
        } else null,
        singleLine = true,
        shape = shape,
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Ascii,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            unfocusedBorderColor = Line,
            focusedBorderColor = Blue,
        ),
    )
}

// ---------- Giriş ----------

@Composable
fun LoginScreen(onLogin: () -> Unit) {
    // Bilerek remember (rememberSaveable değil): giriş bilgileri hiçbir yere kaydedilmez.
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color.White, Color.White, Color(0xFFE6EEFB))))
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(72.dp))
        Icon(Icons.Outlined.Sms, contentDescription = "Bayx logosu", tint = Blue, modifier = Modifier.size(104.dp))
        Spacer(Modifier.height(10.dp))
        Wordmark(nameSize = 40.sp, subSize = 34.sp, centered = true)
        Spacer(Modifier.height(48.dp))
        PillField(user, { user = it }, "Kullanıcı Adı", Icons.Outlined.Person)
        Spacer(Modifier.height(16.dp))
        PillField(pass, { pass = it }, "Şifre", Icons.Outlined.Lock, isPassword = true)
        Spacer(Modifier.height(30.dp))
        GradientButton("Giriş Yap") {
            user = ""
            pass = ""
            onLogin()
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                info = "Kayıt yakında açılacak. Yeni hesaplar yönetici onayından sonra kullanılabilir."
            }) { Text("Hesap Oluştur", fontSize = 15.sp) }
            Box(Modifier.width(1.dp).height(16.dp).background(SoftBlue))
            TextButton(onClick = {
                info = "Şifreni yalnızca yönetici sıfırlayabilir. Lütfen yöneticine başvur."
            }) { Text("Şifremi Unuttum", fontSize = 15.sp) }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "Yeni hesaplar yönetici onayıyla açılır.\nBu cihazda giriş bilgisi saklanmaz.",
            color = Muted,
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(24.dp))
    }

    info?.let { message ->
        AlertDialog(
            onDismissRequest = { info = null },
            confirmButton = { TextButton(onClick = { info = null }) { Text("Tamam") } },
            text = { Text(message) },
        )
    }
}

// ---------- Sohbet listesi ----------

private data class ChatPreview(
    val name: String,
    val last: String,
    val time: String,
    val unread: Int = 0,
    val online: Boolean = true,
    val icon: ImageVector? = null,
    val read: Boolean = false,
)

private val sampleChats = listOf(
    ChatPreview("Zeynep", "Akşam buluşalım mı?", "14:25", unread = 2),
    ChatPreview("Mert", "Fotoğraf gönderdi", "14:12", icon = Icons.Outlined.Image),
    ChatPreview("Kaan", "Sesli mesaj (0:24)", "13:48", icon = Icons.Outlined.Mic),
    ChatPreview("Elif", "Tamamdır, görüşürüz", "12:20", read = true),
    ChatPreview("Deniz", "Konum paylaştı", "Dün", online = false, icon = Icons.Outlined.LocationOn),
)

@Composable
fun ChatListScreen(onOpenChat: (String) -> Unit, onProfile: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        "Sohbetler" to Icons.Outlined.ChatBubbleOutline,
        "Kişiler" to Icons.Outlined.Group,
        "Gruplar" to Icons.Outlined.Groups,
    )

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Sms, contentDescription = null, tint = Blue, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) { Wordmark(nameSize = 23.sp, subSize = 18.sp, centered = false) }
                IconButton(onClick = {}) { Icon(Icons.Outlined.Search, "Ara", tint = Ink, modifier = Modifier.size(26.dp)) }
                IconButton(onClick = {}) { Icon(Icons.Outlined.Notifications, "Bildirimler", tint = Ink, modifier = Modifier.size(26.dp)) }
                IconButton(onClick = onProfile) { Icon(Icons.Filled.MoreVert, "Menü", tint = Ink) }
            }
            Spacer(Modifier.height(20.dp))
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(27.dp),
                color = Color.White,
                shadowElevation = 4.dp,
            ) {
                Row {
                    tabs.forEachIndexed { index, (label, icon) ->
                        val selected = index == tab
                        val tint = if (selected) Color.White else Ink
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(27.dp))
                                .then(if (selected) Modifier.background(BlueBrush) else Modifier)
                                .clickable { tab = index },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> LazyColumn {
                        items(sampleChats) { chat -> ChatRow(chat) { onOpenChat(chat.name) } }
                    }
                    1 -> LazyColumn {
                        items(sampleChats.sortedBy { it.name }) { chat ->
                            ChatRow(
                                chat.copy(
                                    last = if (chat.online) "Çevrimiçi" else "Çevrimdışı",
                                    time = "",
                                    unread = 0,
                                    icon = null,
                                    read = false,
                                ),
                            ) { onOpenChat(chat.name) }
                        }
                    }
                    else -> Text(
                        "Henüz grup yok",
                        color = Muted,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Surface(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth().height(76.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 6.dp,
            ) {
                Row {
                    NavItem("Sohbetler", Icons.Filled.Sms, selected = true) {}
                    NavItem("Aramalar", Icons.Outlined.Call, selected = false) {}
                    NavItem("Medya", Icons.Outlined.PermMedia, selected = false) {}
                    NavItem("Ayarlar", Icons.Outlined.Settings, selected = false, onClick = onProfile)
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 22.dp, bottom = 108.dp)
                .size(64.dp)
                .shadow(10.dp, CircleShape, ambientColor = Blue, spotColor = Blue)
                .clip(CircleShape)
                .background(BlueBrush)
                .clickable { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = "Yeni sohbet", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Blue else Ink
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(22.dp))
            .then(if (selected) Modifier.background(Color(0xFFE6F0FF)) else Modifier)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun ChatRow(chat: ChatPreview, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat.name, 60.dp, online = chat.online)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chat.name, modifier = Modifier.weight(1f), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(chat.time, color = Ink, fontSize = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.icon != null) {
                    Icon(chat.icon, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    chat.last,
                    modifier = Modifier.weight(1f),
                    color = Muted,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chat.read) {
                    Icon(Icons.Filled.DoneAll, contentDescription = "Okundu", tint = Blue, modifier = Modifier.size(18.dp))
                }
                if (chat.unread > 0) {
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(Blue),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${chat.unread}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ---------- Sohbet ----------

private sealed interface Msg {
    val mine: Boolean
    val time: String
}

private data class TextMsg(val text: String, override val mine: Boolean, override val time: String) : Msg
private data class VideoMsg(val length: String, override val mine: Boolean, override val time: String) : Msg
private data class VoiceMsg(val length: String, override val mine: Boolean, override val time: String) : Msg

@Composable
fun ChatScreen(name: String, onBack: () -> Unit) {
    val messages = remember {
        mutableStateListOf<Msg>(
            TextMsg("Selam 😊", mine = false, time = "14:20"),
            TextMsg("İyiyim, sen nasılsın? 💙", mine = true, time = "14:22"),
            TextMsg("Harika, birazdan görüşelim mi? ☕", mine = false, time = "14:24"),
            VideoMsg("0:24", mine = true, time = "14:25"),
            VoiceMsg("0:18", mine = false, time = "14:26"),
        )
    }
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Avatar(name, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Online))
                    Spacer(Modifier.width(6.dp))
                    Text("Çevrimiçi", color = Muted, fontSize = 14.sp)
                }
            }
            IconButton(onClick = {}) { Icon(Icons.Outlined.Call, "Sesli ara", tint = Ink) }
            IconButton(onClick = {}) { Icon(Icons.Outlined.Videocam, "Görüntülü ara", tint = Ink, modifier = Modifier.size(28.dp)) }
            IconButton(onClick = {}) { Icon(Icons.Filled.MoreVert, "Menü", tint = Ink) }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Bugün",
                        color = Muted,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFFEAF0F8))
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            items(messages) { msg -> MessageBubble(msg) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = CircleShape, color = Color.White, shadowElevation = 4.dp, modifier = Modifier.size(48.dp)) {
                IconButton(onClick = {}) { Icon(Icons.Filled.Add, "Ekle", tint = Ink) }
            }
            Spacer(Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.White,
                shadowElevation = 4.dp,
                modifier = Modifier.weight(1f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Mesaj yaz...", color = Muted) },
                        maxLines = 4,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                    IconButton(onClick = {}) { Icon(Icons.Outlined.EmojiEmotions, "İfadeler", tint = Ink) }
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(56.dp)
                    .shadow(8.dp, CircleShape, ambientColor = Blue, spotColor = Blue)
                    .clip(CircleShape)
                    .background(BlueBrush)
                    .clickable {
                        if (draft.isNotBlank()) {
                            messages.add(TextMsg(draft.trim(), mine = true, time = "şimdi"))
                            draft = ""
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

@Composable
private fun MessageBubble(msg: Msg) {
    val shape = RoundedCornerShape(
        topStart = 22.dp,
        topEnd = 22.dp,
        bottomEnd = if (msg.mine) 6.dp else 22.dp,
        bottomStart = if (msg.mine) 22.dp else 6.dp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.mine) Arrangement.End else Arrangement.Start) {
        when (msg) {
            is TextMsg -> Surface(
                shape = shape,
                color = if (msg.mine) Blue else Color.White,
                shadowElevation = 3.dp,
                modifier = Modifier.widthIn(max = 310.dp),
            ) {
                val fg = if (msg.mine) Color.White else Ink
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
                    Text(msg.text, color = fg, fontSize = 16.sp, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(10.dp))
                    Text(msg.time, color = if (msg.mine) Color.White else Muted, fontSize = 13.sp)
                    if (msg.mine) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.DoneAll, "Okundu", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            is VideoMsg -> Box(
                Modifier
                    .size(width = 290.dp, height = 170.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF5B7FB4), Color(0xFFE9A27A), Color(0xFF3E5878)))),
            ) {
                Box(
                    Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color(0x9E0F1B2D)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, "Videoyu oynat", tint = Color.White, modifier = Modifier.size(30.dp)) }
                MediaChip(msg.length, Modifier.align(Alignment.BottomStart))
                MediaChip(msg.time, Modifier.align(Alignment.BottomEnd))
            }
            is VoiceMsg -> Surface(shape = shape, color = Color.White, shadowElevation = 3.dp) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(BlueBrush),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.PlayArrow, "Sesli mesajı oynat", tint = Color.White) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf(4, 4, 8, 6, 4, 10, 16, 24, 12, 4, 6, 10, 18, 24, 14, 4, 10, 20, 12, 8, 4, 4, 4).forEach { h ->
                                Box(Modifier.width(4.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(Blue))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Text(msg.length, color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.width(96.dp))
                            Text(msg.time, color = Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaChip(text: String, modifier: Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xB80F1B2D))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

// ---------- Profil ----------

@Composable
fun ProfileScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    var notifications by remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Bg, Bg, Color(0xFFE6EEFB))))
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
            IconButton(onClick = {}) { Icon(Icons.Outlined.Edit, "Profili düzenle", tint = Ink) }
        }
        Spacer(Modifier.height(14.dp))
        Avatar("Mehmet", 148.dp, online = true)
        Spacer(Modifier.height(12.dp))
        Text("Mehmet", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
        Text("Çevrimiçi", color = SoftBlue, fontSize = 17.sp)
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.padding(horizontal = 28.dp).fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(26.dp),
            color = Color.White,
            shadowElevation = 3.dp,
        ) {
            Box(contentAlignment = Alignment.Center) { Text("Bayx'tayım", color = Ink, fontSize = 16.sp) }
        }
        Spacer(Modifier.height(24.dp))
        Surface(
            modifier = Modifier.padding(horizontal = 18.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = Color.White,
            shadowElevation = 5.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SettingRow(Icons.Outlined.Person, "Hesap Bilgileri") {}
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Shield, "Gizlilik ve Güvenlik") {}
                HorizontalDivider(color = Line)
                SettingRow(
                    Icons.Outlined.Notifications,
                    "Bildirimler",
                    trailing = { Switch(checked = notifications, onCheckedChange = { notifications = it }) },
                ) { notifications = !notifications }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Storage, "Depolama ve Medya") {}
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.HowToReg, "Üyelik Onayları") {}
                HorizontalDivider(color = Line)
                SettingRow(Icons.AutoMirrored.Outlined.Logout, "Çıkış Yap", onClick = onLogout)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    label: String,
    trailing: @Composable () -> Unit = {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Ink)
    },
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, modifier = Modifier.weight(1f), color = Ink, fontSize = 16.5.sp)
        trailing()
    }
}
