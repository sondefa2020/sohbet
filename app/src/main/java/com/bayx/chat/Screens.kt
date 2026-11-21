package com.bayx.chat

import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Contacts
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.ui.graphics.SolidColor
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
import com.bayx.chat.data.ProfileRepo
import com.bayx.chat.data.PresenceRepo
import com.bayx.chat.data.ContactsRepo
import com.bayx.chat.data.PhonebookRepo
import com.bayx.chat.data.ChatFlags
import com.bayx.chat.data.ChatPrefs
import com.bayx.chat.data.ChatSummary
import com.bayx.chat.data.Member
import com.bayx.chat.data.UserPresence
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Close
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal val BlueBrush: Brush get() = Brush.verticalGradient(listOf(BlueLight, BlueDark))
internal val SoftShadow = Color(0x26825A32)
internal val PageBrush: Brush
    get() = SolidColor(Bg)

// ---------- Ortak parçalar ----------

private val avatarTints = listOf(
    Color(0xFFE8D3C0) to Color(0xFF7A4A26),
    Color(0xFFD9E4F5) to Color(0xFF2D5B94),
    Color(0xFFDDEFE3) to Color(0xFF23724A),
    Color(0xFFF6DADA) to Color(0xFFA33A3A),
    Color(0xFFE7DDF6) to Color(0xFF5B3F99),
    Color(0xFFF7E6C4) to Color(0xFF8A5A12),
)
private val avatarTintsDark = listOf(
    Color(0xFF3A2618) to Color(0xFFF0C9A8),
    Color(0xFF1C2A3F) to Color(0xFFA9C6F0),
    Color(0xFF16301F) to Color(0xFF9ED9B4),
    Color(0xFF3D1717) to Color(0xFFF2A9A9),
    Color(0xFF281C40) to Color(0xFFC9B4F2),
    Color(0xFF3A2A0E) to Color(0xFFF2D08A),
)

@Composable
internal fun Avatar(name: String, size: Dp, online: Boolean = false, url: String? = null, mode: String? = null) {
    val tints = if (ThemeState.dark) avatarTintsDark else avatarTints
    val (bg, fg) = tints[(name.firstOrNull()?.code ?: 0) % tints.size]
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, url) {
        value = url?.let { ProfileRepo.loadAvatar(it) }
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
            focusedContainerColor = CardBg,
            unfocusedContainerColor = CardBg,
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
        Text(t("Bağlantı kurulamadı"), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
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
        }) { Text(t("Çıkış yap")) }
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
    onPerson: (String) -> Unit = {},
    onNewChat: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var unreadOnly by remember { mutableStateOf(false) }
    // Küçük ekranlar (ör. 360dp altı): daha sıkı yerleşim.
    val narrow = LocalAppWidth.current < 380
    val allRooms by Bayx.rooms.collectAsState()
    val onlineUsers by PresenceRepo.online.collectAsState()
    val presences by PresenceRepo.presences.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var scanQr by remember { mutableStateOf(false) }
    var deleteRoom by remember { mutableStateOf<ChatSummary?>(null) }
    // Mesaj istekleri (tanımadığın kişilerden) listede değil, ayrı bir şeritte durur.
    val requests = allRooms.filter { it.request }
    val rooms = allRooms.filter { !it.secret && !it.request }.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) || it.lastMessage.contains(query, ignoreCase = true)
    }
    val contacts by ContactsRepo.contacts.collectAsState()
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
    val context = LocalContext.current
    val phonebook by PhonebookRepo.entries.collectAsState()
    val phonebookSyncing by PhonebookRepo.syncing.collectAsState()
    var contactsAllowed by remember { mutableStateOf(PhonebookRepo.hasPermission(context)) }
    val contactsPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        contactsAllowed = granted
        if (granted) scope.launch { PhonebookRepo.sync(context) }
    }
    val toast = rememberToast()
    val tabs = listOf(
        "Sohbetler" to Icons.Outlined.ChatBubbleOutline,
        t("Kişiler") to Icons.Outlined.Group,
        "Gruplar" to Icons.Outlined.Groups,
    )

    LaunchedEffect(Unit) {
        ContactsRepo.refreshContacts()
        loadedContacts = true
    }
    // Kişiler sekmesi açılınca rehberi yeniden eşleştir: yeni kaydolan kişiler hemen görünsün.
    val phonebookError by PhonebookRepo.error.collectAsState()
    LaunchedEffect(tab) {
        if (tab == 1) {
            PhonebookRepo.syncIfStale(context)
            ContactsRepo.refreshContacts()
        }
    }

    fun doFind() {
        val name = findText.trim().lowercase()
        if (name.isBlank() || finding) return
        finding = true
        scope.launch {
            findResult = ContactsRepo.findUser(name)
            finding = false
            if (findResult?.found != true) toast(tf("@%s adında bir kullanıcı bulunamadı.", name))
        }
    }

    // Kullanıcı adı ya da telefon numarasıyla Tayfa'da arama (yazmayı bırakınca otomatik).
    var globalHit by remember { mutableStateOf<Api.ContactFind?>(null) }
    var globalByPhone by remember { mutableStateOf(false) }
    // Numara aranırken durum: "aranıyor", "Tayfa'da değil" (davet et) ya da bağlantı hatası.
    var globalLooking by remember { mutableStateOf(false) }
    var globalMissPhone by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query, tab) {
        globalHit = null
        globalMissPhone = null
        globalLooking = false
        val q = query.trim()
        val digits = q.filter { it.isDigit() }
        val phoneLike = digits.length >= 10 && q.all { it.isDigit() || it in "+ ()-." }
        val nameLike = !phoneLike && Regex("^@?[a-zA-Z0-9][a-zA-Z0-9._-]{2,19}$").matches(q)
        if (tab > 1 || (!phoneLike && !nameLike)) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        val key = if (phoneLike) {
            PhonebookRepo.normalize(q, PhonebookRepo.region(context))?.let { "+$it" } ?: return@LaunchedEffect
        } else q.lowercase().removePrefix("@")
        globalLooking = true
        val hit = ContactsRepo.findUser(key)
        globalLooking = false
        if (hit?.found == true && hit.userId != me.userId) {
            globalByPhone = phoneLike
            globalHit = hit
        } else if (phoneLike && hit != null && !hit.found) {
            globalMissPhone = key
        }
    }

    fun sendRequest(username: String) {
        scope.launch {
            val error = ContactsRepo.sendContactRequest(username)
            if (error == null) {
                toast(t("İstek gönderildi."))
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
            if (roomId != null) onOpenChat(roomId) else toast(t("Sohbet açılamadı. Tekrar dene."))
        }
    }

    fun openDirect(member: Member) = openDirectId(member.userId)

    Box(Modifier.fillMaxSize().background(PageBrush)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // WhatsApp düzeni: solda vurgu renginde "Tayfa", sağda QR, kamera ve menü.
                Text(
                    "Tayfa", color = Blue, fontSize = if (narrow) 25.sp else 28.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp, maxLines = 1, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { scanQr = true }) { Icon(Icons.Outlined.QrCodeScanner, t("QR okut"), tint = Ink) }
                IconButton(onClick = onStatus) { Icon(Icons.Outlined.PhotoCamera, t("Durum ekle"), tint = Ink) }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, t("Menü"), tint = Ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = CardBg) {
                        DropdownMenuItem(text = { Text(t("Yeni grup"), color = Ink) }, onClick = { menu = false; showNewGroup = true })
                        DropdownMenuItem(text = { Text(t("Medya"), color = Ink) }, onClick = { menu = false; onMedia() })
                        DropdownMenuItem(text = { Text(t("Gizli Oda"), color = Ink) }, onClick = { menu = false; onSecret() })
                        DropdownMenuItem(text = { Text(t("Ayarlar"), color = Ink) }, onClick = { menu = false; onProfile() })
                    }
                }
            }
            // Her zaman görünen arama kutusu (WhatsApp gibi): sohbet, kişi, kullanıcı adı ya da numara.
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp).fillMaxWidth().height(46.dp)
                    .clip(RoundedCornerShape(23.dp)).background(GlassPill).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(t("Ara veya yeni sohbet başlat"), color = Muted, fontSize = 15.sp, maxLines = 1)
                    androidx.compose.foundation.text.BasicTextField(
                        query, { query = it; searching = it.isNotEmpty() }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 15.sp),
                        cursorBrush = SolidColor(Blue), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Outlined.Close, t("Temizle"), tint = Muted, modifier = Modifier.size(20.dp).clickable { query = ""; searching = false })
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val unreadCount = rooms.count { it.unread > 0 }
                FilterChipPill(t("Tümü"), tab == 0 && !unreadOnly) { tab = 0; unreadOnly = false }
                FilterChipPill(if (unreadCount > 0) tf("Okunmamış %s", unreadCount) else t("Okunmamış"), tab == 0 && unreadOnly) { tab = 0; unreadOnly = true }
                FilterChipPill(t("Kişiler"), tab == 1) { tab = 1; unreadOnly = false }
                FilterChipPill(t("Gruplar"), tab == 2) { tab = 2; unreadOnly = false }
                FilterChipPill(t("Gizli Oda"), false, onClick = onSecret)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> LazyColumn {
                        globalHit?.let { hit -> item(key = "bul") { FoundUserRow(hit, globalByPhone, onMessage = { openDirectId(hit.userId); query = ""; searching = false }, onRequest = { sendRequest(hit.username); globalHit = null }, onPerson = { onPerson(hit.userId) }) } }
                        if (globalLooking) item(key = "bul-yukleniyor") { SearchLookingRow() }
                        globalMissPhone?.let { phone -> item(key = "bul-yok") { SearchMissRow(phone) } }
                        if (query.isBlank() && requests.isNotEmpty()) {
                            item(key = "istekler") { MessageRequestsBar(requests) { id -> onOpenChat(id) } }
                        }
                        val active = rooms.filter { !chatFlags(it.id).archived && (!unreadOnly || it.unread > 0) }
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
                                    Text(t("Arşivlenenler"), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Text("$archivedCount", color = SoftBlue, fontSize = 14.sp)
                                }
                            }
                        }
                        if (active.isEmpty()) {
                            item {
                                Text(
                                    if (query.isNotBlank()) t("Aramaya uyan sohbet yok.") else if (unreadOnly) t("Okunmamış mesajın yok.") else t("Henüz sohbet yok.\nSağ alttaki düğmeye dokunarak birine yaz."),
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
                                    onAvatar = chat.dmUserId?.takeIf { chat.isDirect }?.let { uid -> { onPerson(uid) } },
                                )
                            }
                        }
                    }
                    1 -> {
                        // WhatsApp mantığı: kişiler cihaz rehberinden gelir; yalnızca numarası kayıtlı olanlar.
                        val book = phonebook.filter { query.isBlank() || it.name.contains(query, true) || it.phone.contains(query.filter { c -> c.isDigit() }) }
                        LazyColumn {
                            globalHit?.let { hit -> item(key = "bul") { FoundUserRow(hit, globalByPhone, onMessage = { openDirectId(hit.userId); query = ""; searching = false }, onRequest = { sendRequest(hit.username); globalHit = null }, onPerson = { onPerson(hit.userId) }) } }
                        if (globalLooking) item(key = "bul-yukleniyor") { SearchLookingRow() }
                        globalMissPhone?.let { phone -> item(key = "bul-yok") { SearchMissRow(phone) } }
                            if (!contactsAllowed) {
                                item {
                                    Column(
                                        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg).padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Icon(Icons.Outlined.Contacts, contentDescription = null, tint = Blue, modifier = Modifier.size(40.dp))
                                        Spacer(Modifier.height(10.dp))
                                        Text(t("Rehberindeki Tayfa kullanıcılarını bul"), color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center)
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            t("Tayfa, rehberindeki numaralardan kimlerin Tayfa kullandığını bulur. Numaralar sunucuya açık gönderilmez."),
                                            color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                                        )
                                        Spacer(Modifier.height(14.dp))
                                        SandButton(t("Rehbere erişime izin ver")) { contactsPermission.launch(android.Manifest.permission.READ_CONTACTS) }
                                    }
                                }
                            } else {
                                item {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(tf("Tayfa'daki kişilerin (%s)", phonebook.size), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { scope.launch { PhonebookRepo.sync(context) } }, enabled = !phonebookSyncing) {
                                            Text(if (phonebookSyncing) t("Yenileniyor…") else t("Yenile"))
                                        }
                                    }
                                }
                                phonebookError?.let { err ->
                                    item { Text(t(err), color = Color(0xFFB4232A), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
                                }
                                if (book.isEmpty() && !phonebookSyncing && members.isEmpty()) {
                                    item {
                                        Text(
                                            t("Rehberinde henüz Tayfa kullanan kimse yok. Arkadaşlarını davet et."),
                                            color = Muted, textAlign = TextAlign.Center, lineHeight = 22.sp,
                                            modifier = Modifier.fillMaxWidth().padding(28.dp),
                                        )
                                    }
                                }
                                items(book, key = { "pb-" + it.userId }) { entry ->
                                    val p = presences[entry.userId]
                                    PersonRow(
                                        entry.name,
                                        (p?.status ?: entry.status).ifBlank { "+" + entry.phone },
                                        mode = p?.mode,
                                        url = entry.avatarUrl,
                                        onAvatar = { onPerson(entry.userId) },
                                    ) { openDirectId(entry.userId) }
                                }
                                item {
                                    TextButton(
                                        onClick = {
                                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    android.content.Intent.EXTRA_TEXT,
                                                    tf("Tayfa'da benimle konuş: %s", inviteLink(AccountCache.load(context).username.ifBlank { me.userId.substringBefore(":").removePrefix("@") })),
                                                )
                                            }
                                            context.startActivity(android.content.Intent.createChooser(send, t("Arkadaşlarını davet et")))
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                    ) {
                                        Icon(Icons.Outlined.PersonAddAlt, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(t("Arkadaşlarını davet et"), fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            val others = members.filter { m ->
                                phonebook.none { it.userId == m.userId } && (query.isBlank() || m.displayName.contains(query, true))
                            }
                            if (others.isNotEmpty()) {
                                item { SectionLabel(t("Diğer kişilerin")) }
                                items(others, key = { "fr-" + it.userId }) { m ->
                                    val p = presences[m.userId]
                                    PersonRow(
                                        m.displayName,
                                        (p?.status ?: m.status).ifBlank { "@" + m.userId.substringBefore(":").removePrefix("@") },
                                        mode = p?.mode,
                                        url = m.avatarUrl,
                                        onAvatar = { onPerson(m.userId) },
                                    ) { openDirectId(m.userId) }
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
                            ) { Text(t("+ Yeni grup oluştur"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                            if (groups.isEmpty()) {
                                EmptyHint(t("Henüz grup yok."))
                            } else {
                                LazyColumn {
                                    items(groups, key = { it.id }) { chat -> ChatRow(chat, onClick = { onOpenChat(chat.id) }) }
                                }
                            }
                        }
                    }
                }
            }
            BottomNav(AppTab.CHATS) { selected ->
                when (selected) {
                    AppTab.CHATS -> tab = 0
                    AppTab.CALLS -> onCalls()
                    AppTab.STATUS -> onStatus()
                    AppTab.MEDIA -> onMedia()
                    AppTab.SETTINGS -> onProfile()
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 18.dp, bottom = 90.dp)
                .size(58.dp)
                .shadow(8.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(AccentFill)
                .clickable(onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AddComment, contentDescription = t("Yeni sohbet"), tint = Color.White, modifier = Modifier.size(25.dp))
        }
    }

    if (showNewGroup) {
        NewGroupDialog(
            members = groupCandidates(phonebook, members),
            onDismiss = { showNewGroup = false },
            onCreate = { name, ids ->
                showNewGroup = false
                scope.launch {
                    val roomId = Bayx.createGroup(name, ids)
                    if (roomId != null) onOpenChat(roomId) else toast(t("Grup oluşturulamadı. Tekrar dene."))
                }
            },
        )
    }

    deleteRoom?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteRoom = null },
            title = { Text(tf("%s sohbeti silinsin mi?", chat.name)) },
            text = { Text(t("Sohbet bu listeden kaldırılır. Karşı taraftaki mesajlar silinmez."), color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    deleteRoom = null
                    scope.launch { if (!Bayx.deleteChat(chat.id)) toast(t("İşlem yapılamadı. Tekrar dene.")) }
                }) { Text(t("Sil"), color = Color(0xFFB4232A)) }
            },
            dismissButton = { TextButton(onClick = { deleteRoom = null }) { Text(t("Vazgeç")) } },
        )
    }

    if (scanQr) {
        ScanQrDialog(onDismiss = { scanQr = false }) { text ->
            scanQr = false
            if (!handleScannedText(text)) toast(t("Bu bir Tayfa kişi kodu değil."))
        }
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
            onLock = {
                if (!f.locked && !com.bayx.chat.data.Gate.lockAvailable()) toast(t("Önce telefonuna parmak izi ya da ekran kilidi ekle."))
                else ChatPrefs.toggleLock(chat.id)
                actionRoom = null
            },
            onInfo = chat.dmUserId?.takeIf { chat.isDirect }?.let { uid -> { actionRoom = null; onPerson(uid) } },
            unread = chat.unread > 0,
            onToggleUnread = {
                actionRoom = null
                scope.launch { Bayx.setMarkedUnread(chat.id, chat.unread == 0) }
            },
            onDelete = { actionRoom = null; deleteRoom = chat },
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
    onLock: () -> Unit = {},
    onInfo: (() -> Unit)? = null,
    unread: Boolean = false,
    onToggleUnread: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
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
            SheetAction(
                if (flags.locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                if (flags.locked) t("Sohbet kilidini kaldır") else t("Sohbeti kilitle"), onLock,
            )
            if (onInfo != null) SheetAction(Icons.Outlined.Info, t("Kişi bilgisi"), onInfo)
            if (onToggleUnread != null) SheetAction(Icons.Outlined.MarkChatUnread, if (unread) t("Okundu olarak işaretle") else t("Okunmadı olarak işaretle"), onToggleUnread)
            if (onDelete != null) SheetAction(Icons.Outlined.Delete, t("Sohbeti sil"), onDelete)
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
            Text(t("Arşivlenenler"), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        if (rooms.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(t("Arşivlenen sohbet yok."), color = Muted, fontSize = 15.sp)
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
internal fun NewGroupDialog(members: List<Member>, onDismiss: () -> Unit, onCreate: (String, List<String>) -> Unit) {
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
                    label = { Text(t("Grup adı")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                if (members.isEmpty()) {
                    Text(t("Önce kişi ekle; gruba yalnızca kişilerini davet edebilirsin."), color = Muted)
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
            ) { Text(t("Oluştur")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Vazgeç")) } },
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Muted, textAlign = TextAlign.Center, lineHeight = 22.sp)
    }
}

/** Ana sekmeler: alt menü her ana sayfada aynı. */
enum class AppTab { CHATS, CALLS, STATUS, MEDIA, SETTINGS }

@Composable
internal fun BottomNav(current: AppTab, onSelect: (AppTab) -> Unit) {
    // WhatsApp düzeni: düz alt çubuk, üstte ince çizgi, seçili sekmede simgenin arkasında hap.
    val rooms by com.bayx.chat.data.Bayx.rooms.collectAsState()
    val unreadChats = rooms.count { it.unread > 0 && !it.request }
    Column(Modifier.fillMaxWidth().background(GlassBar)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 6.dp)) {
            NavItem(t("Sohbetler"), Icons.AutoMirrored.Outlined.Chat, current == AppTab.CHATS, unreadChats) { onSelect(AppTab.CHATS) }
            NavItem(t("Durum"), Icons.Outlined.RadioButtonChecked, current == AppTab.STATUS) { onSelect(AppTab.STATUS) }
            NavItem(t("Aramalar"), Icons.Outlined.Call, current == AppTab.CALLS) { onSelect(AppTab.CALLS) }
            NavItem(t("Ayarlar"), Icons.Outlined.Settings, current == AppTab.SETTINGS) { onSelect(AppTab.SETTINGS) }
        }
    }
}

/** Ana ekrandaki kısayol kartı: renkli simge rozeti, başlık ve kısa açıklama. */
@Composable
private fun QuickCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .shadow(3.dp, shape, ambientColor = SoftShadow, spotColor = SoftShadow)
            .clip(shape)
            .background(CardBg)
            .border(1.dp, Line, shape)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Brush.linearGradient(colors)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RowScope.NavItem(label: String, icon: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.width(64.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (selected) SoftAccent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = if (selected) SoftAccentInk else Muted, modifier = Modifier.size(23.dp))
            }
            if (badge > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-4).dp)
                        .defaultMinSize(minWidth = 18.dp).height(18.dp).clip(CircleShape).background(AccentFill).padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (badge > 99) "99+" else "$badge", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(4.dp))
        val labelSize = with(androidx.compose.ui.platform.LocalDensity.current) { 12f.dp.toSp() }
        Text(
            label, color = if (selected) Ink else Muted, fontSize = labelSize,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        )
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
        color = CardBg,
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
                "self" -> Text(t("Bu sensin"), color = Muted, fontSize = 13.sp)
                else -> Button(onClick = { onOpen(r.userId) }, modifier = Modifier.height(40.dp)) { Text(t("Mesaj")) }
            }
        }
    }
}

/** Arkadaş istekleri ekranı: gelen istekleri kabul et/yok de, gönderilenleri geri çek. */
@Composable
fun RequestsScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val contacts by ContactsRepo.contacts.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    LaunchedEffect(Unit) { ContactsRepo.refreshContacts() }

    fun handle(username: String, accept: Boolean, name: String) {
        scope.launch {
            if (accept) {
                if (ContactsRepo.acceptContact(username)) toast(tf("%s artık kişilerinde.", name))
            } else {
                ContactsRepo.rejectContact(username)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text(t("Arkadaş istekleri"), color = Ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
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
                    item { SectionLabel(tf("Gönderilen istekler (%s)", contacts.outgoing.size)) }
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
                                scope.launch { if (ContactsRepo.unblockContact(m.userId.substringBefore(":").removePrefix("@"))) toast(t("Engel kaldırıldı.")) }
                            }) { Text(t("Engeli kaldır")) }
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
            TextButton(onClick = onReject) { Text(t("Geri çek"), color = Muted) }
        }
    }
}

@Composable
private fun FoundUserRow(hit: Api.ContactFind, byPhone: Boolean, onMessage: () -> Unit, onRequest: () -> Unit, onPerson: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Text(t("Tayfa'da bulundu"), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 4.dp))
        Row(
            // Satıra dokununca sohbet, fotoğrafa dokununca kişi bilgisi açılır.
            Modifier.fillMaxWidth().clickable(enabled = hit.relation != "blocked", onClick = onMessage).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onPerson)) { Avatar(hit.displayName, 52.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(hit.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("@" + hit.username, color = Muted, fontSize = 13.sp, maxLines = 1)
            }
            when (hit.relation) {
                "blocked" -> Text(t("Engelli"), color = Muted, fontSize = 13.sp)
                else -> TextButton(onClick = onMessage) { Text(t("Mesaj"), fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun SearchLookingRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(t("Tayfa'da aranıyor…"), color = Muted, fontSize = 14.sp)
    }
}

/** Yazılan numara Tayfa'da yok: tek dokunuşla SMS daveti. */
@Composable
private fun SearchMissRow(phone: String) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(PhonebookRepo.pretty(phone), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(t("Bu numara Tayfa'da kayıtlı değil."), color = Muted, fontSize = 13.sp)
        }
        TextButton(onClick = {
            val sms = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + phone))
                .putExtra("sms_body", tf("Tayfa'da benimle konuş: %s", inviteLink(AccountCache.load(context).username.ifBlank { Bayx.myLocalpart() })))
            runCatching { context.startActivity(sms) }
        }) { Text(t("Davet et"), fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun PersonRow(name: String, handle: String, mode: String? = null, url: String? = null, onAvatar: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clip(CircleShape).then(if (onAvatar != null) Modifier.clickable(onClick = onAvatar) else Modifier)) {
            Avatar(name, 56.dp, url = url, mode = mode)
        }
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
    onAvatar: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 16.dp, top = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fotoğrafa dokununca kişi bilgisi açılır (WhatsApp gibi).
        Box(Modifier.padding(bottom = 9.dp).clip(CircleShape).then(if (onAvatar != null) Modifier.clickable(onClick = onAvatar) else Modifier)) {
            Avatar(chat.name, 52.dp, url = chat.avatarUrl, mode = mode)
        }
        Spacer(Modifier.width(14.dp))
        val divider = Line
        Column(
            Modifier.weight(1f)
                .drawBehind { drawLine(divider, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5f), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5f), strokeWidth = 1f) }
                .padding(top = 2.dp, bottom = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chat.name,
                    color = Ink,
                    fontSize = 16.5.sp,
                    fontWeight = FontWeight.SemiBold,
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
                Text(
                    formatTime(chat.time), color = if (chat.unread > 0) Blue else Muted, fontSize = 12.5.sp,
                    fontWeight = if (chat.unread > 0) FontWeight.Bold else FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val drafts by com.bayx.chat.data.Drafts.map.collectAsState()
                val typingNow by com.bayx.chat.data.Bayx.typingRooms.collectAsState()
                val draftText = drafts[chat.id]
                when {
                    typingNow.contains(chat.id) -> Text(
                        t("yazıyor…"), modifier = Modifier.weight(1f), color = Blue,
                        fontSize = 14.5.sp, maxLines = 1, fontWeight = FontWeight.SemiBold,
                    )
                    !draftText.isNullOrBlank() -> Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFF1E8E4E), fontWeight = FontWeight.SemiBold)) { append(t("Taslak") + ": ") }
                            append(draftText)
                        },
                        modifier = Modifier.weight(1f), color = Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    else -> Text(
                        chat.lastMessage.ifEmpty { t("Uçtan uca şifreli sohbet") },
                        modifier = Modifier.weight(1f),
                        color = if (chat.unread > 0) Ink else Muted,
                        fontWeight = if (chat.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (chat.unread > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.defaultMinSize(minWidth = 22.dp).height(22.dp).clip(CircleShape).background(AccentFill).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (chat.unread > 99) "99+" else "${chat.unread}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}


/** Filtre çipi (Tümü / Okunmamış / Kişiler / Gruplar): kaydırılabilir, küçük ekranda taşmaz. */
@Composable
private fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) SoftAccent else ChipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            label, color = if (selected) SoftAccentInk else Ink, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, softWrap = false,
        )
    }
}

/** Grup için seçilebilecek kişiler: rehberindeki Tayfa kullanıcıları ve konuştuğun kişiler. */
internal fun groupCandidates(book: List<PhonebookRepo.Entry>, others: List<Member>): List<Member> {
    val out = LinkedHashMap<String, Member>()
    book.forEach { e -> out[e.userId] = Member(userId = e.userId, displayName = e.name, admin = false, pending = false, avatarUrl = e.avatarUrl) }
    others.forEach { m -> if (m.userId !in out) out[m.userId] = m }
    return out.values.sortedBy { it.displayName.lowercase() }
}
