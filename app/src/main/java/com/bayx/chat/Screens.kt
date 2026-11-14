package com.bayx.chat

import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PersonSearch
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
import com.bayx.chat.data.ChatFlags
import com.bayx.chat.data.ChatPrefs
import com.bayx.chat.data.ChatSummary
import com.bayx.chat.data.Member
import com.bayx.chat.data.UserPresence
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonChecked
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal val BlueBrush = Brush.verticalGradient(listOf(BlueLight, BlueDark))
internal val SoftShadow = Color(0x26825A32)
internal val PageBrush: Brush
    get() = if (ThemeState.dark) {
        Brush.verticalGradient(listOf(Color(0xFF17130F), Color(0xFF14110F), Color(0xFF100D0B)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFCFAF7), Color(0xFFF8F3ED), Color(0xFFF1E9DF)))
    }

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
                    .background(Bg)
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
    "online" -> t("Çevrimiçi")
    "busy" -> t("Meşgul")
    "away" -> t("Dışarıda")
    "invisible" -> t("Çevrimdışı görün")
    else -> t("Çevrimdışı")
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
        placeholder = { Text(placeholder, color = Muted, fontSize = 16.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, color = Ink),
        leadingIcon = { Icon(icon, contentDescription = null, tint = Ink) },
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) t("Şifreyi gizle") else t("Şifreyi göster"),
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
            t("Oturumun açık, ama sunucuya şu an ulaşılamıyor. İnternet bağlantını kontrol edip tekrar dene."),
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
    onRequests: () -> Unit = {},
    onCalls: () -> Unit = {},
    onStatus: () -> Unit = {},
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
    val contacts by Bayx.contacts.collectAsState()
    val members = contacts.friends.filter { it.userId != me.userId }
    val chatFlagMap by ChatPrefs.state.collectAsState()
    fun chatFlags(roomId: String) = chatFlagMap[roomId] ?: ChatFlags()
    var actionRoom by remember { mutableStateOf<ChatSummary?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    var loadedContacts by remember { mutableStateOf(false) }
    var showNewGroup by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var findText by remember { mutableStateOf("") }
    var findResult by remember { mutableStateOf<Api.ContactFind?>(null) }
    var finding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val context = LocalContext.current
    val tabs = listOf(
        "Sohbetler" to Icons.Outlined.ChatBubbleOutline,
        t("Kişiler") to Icons.Outlined.Group,
        "Gruplar" to Icons.Outlined.Groups,
    )

    LaunchedEffect(Unit) {
        Bayx.refreshContacts()
        loadedContacts = true
    }

    fun doFind() {
        val name = findText.trim().lowercase()
        if (name.isBlank() || finding) return
        finding = true
        scope.launch {
            findResult = Bayx.findUser(name)
            finding = false
            if (findResult?.found != true) toast("@$name adında bir kullanıcı bulunamadı.")
        }
    }

    fun sendRequest(username: String) {
        scope.launch {
            val error = Bayx.sendContactRequest(username)
            if (error == null) {
                toast("İstek gönderildi.")
                findResult = null
                findText = ""
            } else {
                toast(error)
            }
        }
    }

    fun openDirectId(userId: String) {
        if (opening) return
        opening = true
        scope.launch {
            val roomId = Bayx.startDirectChat(userId)
            opening = false
            if (roomId != null) onOpenChat(roomId) else toast("Sohbet açılamadı. Tekrar dene.")
        }
    }

    fun openDirect(member: Member) = openDirectId(member.userId)

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
                Box {
                    ChipIcon(Icons.Outlined.PersonAddAlt, t("Arkadaş istekleri"), size = 44.dp, onClick = onRequests)
                    if (contacts.incoming.isNotEmpty()) {
                        Box(
                            Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(Color(0xFFE5484D)),
                            contentAlignment = Alignment.Center,
                        ) { Text("${contacts.incoming.size}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Spacer(Modifier.width(10.dp))
                ChipIcon(Icons.Outlined.Search, "Ara", size = 44.dp) { searching = !searching; if (!searching) query = "" }
                Spacer(Modifier.width(10.dp))
                ChipIcon(Icons.Outlined.Settings, "Profil ve ayarlar", size = 44.dp, onClick = onProfile)
            }
            Spacer(Modifier.height(18.dp))
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(26.dp),
                color = GlassPill,
                shadowElevation = 2.dp,
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
                                .then(if (selected) Modifier.background(CardBg) else Modifier)
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
                        val active = rooms.filter { !chatFlags(it.id).archived }
                            .sortedByDescending { chatFlags(it.id).pinned }
                        val archivedCount = allRooms.count { !it.secret && chatFlags(it.id).archived }
                        if (archivedCount > 0 && query.isBlank()) {
                            item {
                                Row(
                                    Modifier.fillMaxWidth().clickable { showArchived = true }
                                        .padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.Archive, null, tint = SoftBlue, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(18.dp))
                                    Text("Arşivlenenler", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text("$archivedCount", color = SoftBlue, fontSize = 14.sp)
                                }
                            }
                        }
                        if (active.isEmpty()) {
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
                            items(active, key = { it.id }) { chat ->
                                ChatRow(
                                    chat,
                                    mode = chat.dmUserId?.let { presences[it]?.mode },
                                    flags = chatFlags(chat.id),
                                    onClick = { onOpenChat(chat.id) },
                                    onLongClick = { actionRoom = chat },
                                )
                            }
                        }
                    }
                    1 -> {
                        val friends = members.filter { query.isBlank() || it.displayName.contains(query, true) || it.userId.contains(query, true) }
                        LazyColumn {
                            item {
                                Column(Modifier.padding(horizontal = 16.dp)) {
                                    OutlinedTextField(
                                        value = findText,
                                        onValueChange = { findText = it.lowercase().trim(); findResult = null },
                                        singleLine = true,
                                        placeholder = { Text("Kullanıcı adıyla kişi bul", color = Muted) },
                                        leadingIcon = { Icon(Icons.Outlined.PersonSearch, contentDescription = null, tint = Muted) },
                                        trailingIcon = {
                                            TextButton(onClick = { doFind() }, enabled = findText.isNotBlank() && !finding) { Text("Bul") }
                                        },
                                        shape = RoundedCornerShape(22.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = Color.White,
                                            unfocusedContainerColor = Color.White,
                                            unfocusedBorderColor = Line,
                                            focusedBorderColor = Blue,
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    findResult?.takeIf { it.found }?.let { r -> FindResultCard(r, onRequest = { sendRequest(r.username) }, onOpen = { openDirectId(it) }) }
                                }
                            }
                            if (contacts.incoming.isNotEmpty()) {
                                item { SectionLabel("Gelen istekler (${contacts.incoming.size})") }
                                items(contacts.incoming, key = { "in-" + it.userId }) { m ->
                                    RequestRow(m, incoming = true,
                                        onAccept = { scope.launch { if (Bayx.acceptContact(m.userId.substringBefore(":").removePrefix("@"))) toast("${m.displayName} artık kişilerinde.") } },
                                        onReject = { scope.launch { Bayx.rejectContact(m.userId.substringBefore(":").removePrefix("@")) } })
                                }
                            }
                            if (contacts.outgoing.isNotEmpty()) {
                                item { SectionLabel("Gönderilen istekler (${contacts.outgoing.size})") }
                                items(contacts.outgoing, key = { "out-" + it.userId }) { m ->
                                    RequestRow(m, incoming = false, onAccept = {},
                                        onReject = { scope.launch { Bayx.rejectContact(m.userId.substringBefore(":").removePrefix("@")) } })
                                }
                            }
                            item { SectionLabel("Kişilerim (${friends.size})") }
                            if (friends.isEmpty()) {
                                item {
                                    Text(
                                        t("Henüz kişin yok. Yukarıdan kullanıcı adıyla birini bul ve istek gönder."),
                                        color = Muted, textAlign = TextAlign.Center, lineHeight = 22.sp,
                                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                                    )
                                }
                            }
                            items(friends, key = { it.userId }) { member ->
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
                                    items(groups, key = { it.id }) { chat -> ChatRow(chat, onClick = { onOpenChat(chat.id) }) }
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth().height(74.dp),
                shape = RoundedCornerShape(28.dp),
                color = GlassPill,
                shadowElevation = 6.dp,
            ) {
                Row {
                    NavItem("Sohbetler", Icons.Filled.Sms, selected = true) { tab = 0 }
                    NavItem("Aramalar", Icons.Outlined.Call, selected = false, onClick = onCalls)
                    NavItem("Durum", Icons.Outlined.RadioButtonChecked, selected = false, onClick = onStatus)
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

    actionRoom?.let { chat ->
        val f = chatFlags(chat.id)
        ChatActionSheet(
            name = chat.name,
            flags = f,
            onDismiss = { actionRoom = null },
            onPin = { ChatPrefs.togglePin(chat.id); actionRoom = null },
            onMute = { ChatPrefs.toggleMute(chat.id); actionRoom = null },
            onArchive = { ChatPrefs.toggleArchive(chat.id); actionRoom = null },
        )
    }

    if (showArchived) {
        val archived = allRooms.filter { !it.secret && chatFlags(it.id).archived }
        ArchivedScreen(
            rooms = archived,
            flagsOf = { chatFlags(it) },
            presences = presences,
            onOpen = { id -> showArchived = false; onOpenChat(id) },
            onUnarchive = { ChatPrefs.toggleArchive(it) },
            onBack = { showArchived = false },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChatActionSheet(
    name: String,
    flags: ChatFlags,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onArchive: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardBg) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                name,
                color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
            SheetAction(
                if (flags.pinned) Icons.Outlined.PushPin else Icons.Outlined.PushPin,
                if (flags.pinned) t("Sabitlemeyi kaldır") else "Sabitle", onPin,
            )
            SheetAction(
                Icons.Outlined.NotificationsOff,
                if (flags.muted) t("Sesi aç") else "Sessize al", onMute,
            )
            SheetAction(
                Icons.Outlined.Archive,
                if (flags.archived) t("Arşivden çıkar") else t("Arşivle"), onArchive,
            )
        }
    }
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Text(label, color = Ink, fontSize = 16.sp)
    }
}

@Composable
private fun ArchivedScreen(
    rooms: List<ChatSummary>,
    flagsOf: (String) -> ChatFlags,
    presences: Map<String, UserPresence>,
    onOpen: (String) -> Unit,
    onUnarchive: (String) -> Unit,
    onBack: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text("Arşivlenenler", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (rooms.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Arşivlenen sohbet yok.", color = Muted, fontSize = 15.sp)
            }
        } else {
            LazyColumn {
                items(rooms, key = { it.id }) { chat ->
                    ChatRow(
                        chat,
                        mode = chat.dmUserId?.let { presences[it]?.mode },
                        flags = flagsOf(chat.id),
                        onClick = { onOpen(chat.id) },
                        onLongClick = { onUnarchive(chat.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NewGroupDialog(members: List<Member>, onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
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
                if (members.isEmpty()) {
                    Text("Önce kişi ekle; gruba yalnızca kişilerini davet edebilirsin.", color = Muted)
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
private fun SectionLabel(text: String) {
    Text(text, color = SoftBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 22.dp, top = 16.dp, bottom = 4.dp))
}

/** Kullanıcı adıyla bulunan kişi: ilişkiye göre istek gönder / sohbet aç / bekliyor. */
@Composable
private fun FindResultCard(r: Api.ContactFind, onRequest: () -> Unit, onOpen: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(r.displayName, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${r.username}", color = Muted, fontSize = 13.sp)
            }
            when (r.relation) {
                "friend" -> Button(onClick = { onOpen(r.userId) }, modifier = Modifier.height(40.dp)) { Text("Sohbet") }
                "outgoing" -> Text("İstek gönderildi", color = Muted, fontSize = 13.sp)
                "incoming" -> Button(onClick = onRequest, modifier = Modifier.height(40.dp)) { Text("Kabul et") }
                "self" -> Text("Bu sensin", color = Muted, fontSize = 13.sp)
                else -> Button(onClick = onRequest, modifier = Modifier.height(40.dp)) { Text("İstek gönder") }
            }
        }
    }
}

/** Arkadaş istekleri ekranı: gelen istekleri kabul et/yok de, gönderilenleri geri çek. */
@Composable
fun RequestsScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val contacts by Bayx.contacts.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    LaunchedEffect(Unit) { Bayx.refreshContacts() }

    fun handle(username: String, accept: Boolean, name: String) {
        scope.launch {
            if (accept) {
                if (Bayx.acceptContact(username)) toast("$name artık kişilerinde.")
            } else {
                Bayx.rejectContact(username)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text("Arkadaş istekleri", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
        }
        if (contacts.incoming.isEmpty() && contacts.outgoing.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Şu an bekleyen istek yok.\nKişiler sekmesinden kullanıcı adıyla birini bulup istek gönderebilirsin.",
                    color = Muted, textAlign = TextAlign.Center, lineHeight = 22.sp, modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn {
                if (contacts.incoming.isNotEmpty()) {
                    item { SectionLabel("Gelen istekler (${contacts.incoming.size})") }
                    items(contacts.incoming, key = { "in-" + it.userId }) { m ->
                        val u = m.userId.substringBefore(":").removePrefix("@")
                        RequestRow(m, incoming = true, onAccept = { handle(u, true, m.displayName) }, onReject = { handle(u, false, m.displayName) })
                    }
                }
                if (contacts.outgoing.isNotEmpty()) {
                    item { SectionLabel("Gönderilen istekler (${contacts.outgoing.size})") }
                    items(contacts.outgoing, key = { "out-" + it.userId }) { m ->
                        val u = m.userId.substringBefore(":").removePrefix("@")
                        RequestRow(m, incoming = false, onAccept = {}, onReject = { handle(u, false, m.displayName) })
                    }
                }
                if (contacts.blocked.isNotEmpty()) {
                    item { SectionLabel("Engellenenler (${contacts.blocked.size})") }
                    items(contacts.blocked, key = { "blk-" + it.userId }) { m ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(m.displayName, 48.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(m.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch { if (Bayx.unblockContact(m.userId.substringBefore(":").removePrefix("@"))) toast("Engel kaldırıldı.") }
                            }) { Text("Engeli kaldır") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RequestRow(member: Member, incoming: Boolean, onAccept: () -> Unit, onReject: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(member.displayName, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(member.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (incoming) t("seni kişi olarak eklemek istiyor") else "istek bekliyor", color = Muted, fontSize = 13.sp)
        }
        if (incoming) {
            TextButton(onClick = onReject) { Text("Yok", color = Muted) }
            Button(onClick = onAccept, modifier = Modifier.height(40.dp)) { Text("Ekle") }
        } else {
            TextButton(onClick = onReject) { Text("Geri çek", color = Muted) }
        }
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ChatRow(
    chat: ChatSummary,
    mode: String? = null,
    flags: ChatFlags = ChatFlags(),
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat.name, 60.dp, url = chat.avatarUrl, mode = mode)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.name,
                    color = Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (flags.muted) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Outlined.NotificationsOff, "Sessiz", tint = SoftBlue, modifier = Modifier.size(16.dp))
                }
                if (flags.pinned) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.PushPin, "Sabit", tint = SoftBlue, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(formatTime(chat.time), color = if (chat.unread > 0) Blue else SoftBlue, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.lastMessage.ifEmpty { t("Uçtan uca şifreli sohbet") },
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
