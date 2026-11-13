package com.bayx.chat

import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sms
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.bayx.chat.data.Api
import com.bayx.chat.data.ApiException
import com.bayx.chat.data.AuthState
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.ChatSummary
import com.bayx.chat.data.Member
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal val BlueBrush = Brush.verticalGradient(listOf(BlueLight, BlueDark))
internal val SoftShadow = Color(0x26825A32)
internal val PageBrush = Brush.verticalGradient(listOf(Color(0xFFFCFAF7), Color(0xFFF8F3ED), Color(0xFFF1E9DF)))

// ---------- Ortak parçalar ----------

private val avatarTints = listOf(
    Color(0xFFFFE3EC) to Color(0xFFA3204E),
    Color(0xFFDCE9FF) to Color(0xFF1247A8),
    Color(0xFFDDF5E6) to Color(0xFF136A38),
    Color(0xFFFFEBD2) to Color(0xFF8A4B00),
    Color(0xFFE9E4FF) to Color(0xFF4A2FB0),
)

@Composable
internal fun Avatar(name: String, size: Dp, online: Boolean = false, url: String? = null, mode: String? = null) {
    val (bg, fg) = avatarTints[(name.firstOrNull()?.code ?: 0) % avatarTints.size]
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = url?.let { Bayx.loadAvatar(it) }
    }
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            val current = bitmap
            if (current != null) {
                Image(
                    current.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = name.trim().take(1).uppercase().ifEmpty { "?" },
                    color = fg,
                    fontSize = (size.value * 0.36f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        val dot = modeColor(mode ?: if (online) "online" else null)
        if (dot != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.24f)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
        }
    }
}

/** MSN tarzı durum rengi: yeşil çevrimiçi, kırmızı meşgul, turuncu dışarıda. */
internal fun modeColor(mode: String?): Color? = when (mode) {
    "online" -> Online
    "busy" -> Color(0xFFE5484D)
    "away" -> Color(0xFFF59E0B)
    else -> null
}

internal fun modeLabel(mode: String?): String = when (mode) {
    "online" -> "Çevrimiçi"
    "busy" -> "Meşgul"
    "away" -> "Dışarıda"
    "invisible" -> "Çevrimdışı görün"
    else -> "Çevrimdışı"
}

@Composable
internal fun Wordmark(nameSize: TextUnit, subSize: TextUnit, centered: Boolean) {
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text("Tayfa", fontSize = nameSize, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, color = Ink)
        Text("Chat", fontSize = subSize, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, color = SoftBlue)
    }
}

@Composable
internal fun GradientButton(text: String, busy: Boolean = false, showArrow: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(31.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .shadow(10.dp, shape, ambientColor = Blue, spotColor = Blue)
            .clip(shape)
            .background(BlueBrush)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = SandInk, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = SandInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (showArrow) {
                    Spacer(Modifier.width(40.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = SandInk)
                }
            }
        }
    }
}

@Composable
internal fun PillField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Ascii,
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
            keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            unfocusedBorderColor = Line,
            focusedBorderColor = Blue,
        ),
    )
}

@Composable
internal fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { message: String -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() } }
}

/** Uygulamanın Android bildirim ayarlarını açar. */
internal fun openNotificationSettings(context: android.content.Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

internal fun formatTime(millis: Long): String {
    if (millis <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "dd.MM.yy"
    return SimpleDateFormat(pattern, Locale.forLanguageTag("tr")).format(Date(millis))
}

// ---------- Açılış, çevrimdışı ----------

@Composable
fun SplashScreen() {
    Column(
        Modifier.fillMaxSize().background(PageBrush),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LogoMark(120.dp)
        Spacer(Modifier.height(14.dp))
        Text("Tayfa", color = Ink, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(28.dp))
        CircularProgressIndicator(color = Blue)
    }
}

@Composable
fun OfflineScreen() {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(PageBrush).systemBarsPadding().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(16.dp))
        Text("Bağlantı kurulamadı", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Oturumun açık, ama sunucuya şu an ulaşılamıyor. İnternet bağlantını kontrol edip tekrar dene.",
            color = Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        GradientButton("Tekrar dene", showArrow = false) { Bayx.retry() }
        Spacer(Modifier.height(8.dp))
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch { Bayx.logout() }
        }) { Text("Çıkış yap") }
    }
}

@Composable
internal fun InfoDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tamam") } },
        text = { Text(message) },
    )
}

// ---------- Sohbet listesi ----------

@Composable
fun ChatListScreen(
    me: AuthState.LoggedIn,
    onOpenChat: (String) -> Unit,
    onProfile: () -> Unit,
    onSecret: () -> Unit = {},
    onMedia: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    val allRooms by Bayx.rooms.collectAsState()
    val onlineUsers by Bayx.online.collectAsState()
    val presences by Bayx.presences.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val rooms = allRooms.filter { !it.secret }.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) || it.lastMessage.contains(query, ignoreCase = true)
    }
    var members by remember { mutableStateOf<List<Member>?>(null) }
    var membersError by remember { mutableStateOf<String?>(null) }
    var showNewGroup by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val context = LocalContext.current
    val tabs = listOf(
        "Sohbetler" to Icons.Outlined.ChatBubbleOutline,
        "Kişiler" to Icons.Outlined.Group,
        "Gruplar" to Icons.Outlined.Groups,
    )

    LaunchedEffect(tab, showNewGroup) {
        if ((tab == 1 || showNewGroup) && members == null) {
            val token = Bayx.accessToken
            try {
                val all = if (token == null) emptyList() else Api.members(token)
                Bayx.rememberMembers(all)
                members = all.filter { it.userId != me.userId }
                membersError = null
            } catch (e: ApiException) {
                membersError = e.message
            }
        }
    }

    fun openDirect(member: Member) {
        if (opening) return
        opening = true
        scope.launch {
            val roomId = Bayx.startDirectChat(member.userId)
            opening = false
            if (roomId != null) onOpenChat(roomId) else toast("Sohbet açılamadı. Tekrar dene.")
        }
    }

    Box(Modifier.fillMaxSize().background(PageBrush)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LogoMark(48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tayfa", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    Text(me.displayName.ifEmpty { "Sohbet" }, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ChipIcon(Icons.Outlined.Search, "Ara", size = 44.dp) { searching = !searching; if (!searching) query = "" }
                Spacer(Modifier.width(10.dp))
                ChipIcon(Icons.Outlined.Settings, "Profil ve ayarlar", size = 44.dp, onClick = onProfile)
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                color = ChipBg,
            ) {
                Row(Modifier.padding(4.dp)) {
                    tabs.forEachIndexed { index, (label, icon) ->
                        val selected = index == tab
                        val tint = if (selected) Ink else Muted
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .then(if (selected) Modifier.shadow(3.dp, RoundedCornerShape(22.dp)) else Modifier)
                                .clip(RoundedCornerShape(22.dp))
                                .then(if (selected) Modifier.background(Color.White) else Modifier)
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
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Sohbet, mesaj ya da kişi ara", color = Muted) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted) },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        unfocusedBorderColor = Line,
                        focusedBorderColor = Blue,
                    ),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> LazyColumn {
                        if (query.isBlank()) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    SatinTile("Gruplar", Icons.Outlined.Groups, Satin.Steel, Modifier.weight(1f)) { tab = 2 }
                                    SatinTile("Gizli Oda", Icons.Outlined.Lock, Satin.Lavender, Modifier.weight(1f), onClick = onSecret)
                                    SatinTile("Medya", Icons.Outlined.PermMedia, Satin.Sand, Modifier.weight(1f), onClick = onMedia)
                                }
                            }
                        }
                        if (rooms.isEmpty()) {
                            item {
                                Text(
                                    if (query.isNotBlank()) "Aramaya uyan sohbet yok." else "Henüz sohbet yok.\nKişiler sekmesinden birini seçerek başla.",
                                    color = Muted,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 22.sp,
                                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                                )
                            }
                        } else {
                            items(rooms, key = { it.id }) { chat ->
                                ChatRow(chat, mode = chat.dmUserId?.let { presences[it]?.mode }) { onOpenChat(chat.id) }
                            }
                        }
                    }
                    1 -> {
                        val list = members?.filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
                        when {
                            membersError != null -> EmptyHint(membersError ?: "")
                            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Blue)
                            }
                            list.isEmpty() -> EmptyHint("Henüz başka üye yok.\nArkadaşların kayıt olduğunda burada görünür.")
                            else -> LazyColumn {
                                items(list, key = { it.userId }) { member ->
                                    val p = presences[member.userId]
                                    PersonRow(
                                        member.displayName,
                                        (p?.status ?: member.status).ifBlank { modeLabel(p?.mode ?: member.mode) },
                                        mode = p?.mode ?: member.mode,
                                        url = member.avatarUrl,
                                    ) { openDirect(member) }
                                }
                            }
                        }
                    }
                    else -> {
                        val groups = rooms.filter { !it.isDirect && !it.secret }
                        Column(Modifier.fillMaxSize()) {
                            TextButton(
                                onClick = { showNewGroup = true },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) { Text("+ Yeni grup oluştur", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                            if (groups.isEmpty()) {
                                EmptyHint("Henüz grup yok.")
                            } else {
                                LazyColumn {
                                    items(groups, key = { it.id }) { chat -> ChatRow(chat) { onOpenChat(chat.id) } }
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth().height(74.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color.White,
                shadowElevation = 6.dp,
            ) {
                Row {
                    NavItem("Sohbetler", Icons.Filled.Sms, selected = true) { tab = 0 }
                    NavItem("Aramalar", Icons.Outlined.Call, selected = false) {
                        toast("Aramak için bir sohbet aç ve üstteki telefon ya da kamera simgesine dokun.")
                    }
                    NavItem("Medya", Icons.Outlined.PermMedia, selected = false, onClick = onMedia)
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
                .clickable { tab = 1 },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Edit, contentDescription = "Yeni sohbet", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }

    if (showNewGroup) {
        NewGroupDialog(
            members = members,
            onDismiss = { showNewGroup = false },
            onCreate = { name, ids ->
                showNewGroup = false
                scope.launch {
                    val roomId = Bayx.createGroup(name, ids)
                    if (roomId != null) onOpenChat(roomId) else toast("Grup oluşturulamadı. Tekrar dene.")
                }
            },
        )
    }
}

@Composable
private fun NewGroupDialog(members: List<Member>?, onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Yeni grup") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    label = { Text("Grup adı") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (members == null) {
                    CircularProgressIndicator(color = Blue)
                } else if (members.isEmpty()) {
                    Text("Eklenecek başka üye yok.", color = Muted)
                } else {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        members.forEach { member ->
                            val checked = member.userId in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { if (checked) selected.remove(member.userId) else selected.add(member.userId) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { if (it) selected.add(member.userId) else selected.remove(member.userId) },
                                )
                                Text(member.displayName, color = Ink)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && selected.isNotEmpty(),
                onClick = { onCreate(name.trim(), selected.toList()) },
            ) { Text("Oluştur") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, textAlign = TextAlign.Center, lineHeight = 22.sp)
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Blue else Muted
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(22.dp))
            .then(if (selected) Modifier.background(Color(0xFFF5EADD)) else Modifier)
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
private fun PersonRow(name: String, handle: String, mode: String? = null, url: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, 56.dp, url = url, mode = mode)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(handle, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, mode: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat.name, 60.dp, url = chat.avatarUrl, mode = mode)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.name,
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(formatTime(chat.time), color = if (chat.unread > 0) Blue else SoftBlue, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.lastMessage.ifEmpty { "Uçtan uca şifreli sohbet" },
                    modifier = Modifier.weight(1f),
                    color = Muted,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (chat.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.defaultMinSize(minWidth = 26.dp).height(26.dp).clip(CircleShape).background(Blue).padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (chat.unread > 99) "99+" else "${chat.unread}",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
