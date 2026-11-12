package com.bayx.chat

import android.widget.Toast
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
internal val SoftShadow = Color(0x33153E82)
internal val PageBrush = Brush.verticalGradient(listOf(Color.White, Color.White, Color(0xFFE6EEFB)))

// ---------- Ortak parçalar ----------

private val avatarTints = listOf(
    Color(0xFFFFE3EC) to Color(0xFFA3204E),
    Color(0xFFDCE9FF) to Color(0xFF1247A8),
    Color(0xFFDDF5E6) to Color(0xFF136A38),
    Color(0xFFFFEBD2) to Color(0xFF8A4B00),
    Color(0xFFE9E4FF) to Color(0xFF4A2FB0),
)

@Composable
internal fun Avatar(name: String, size: Dp, online: Boolean = false) {
    val (bg, fg) = avatarTints[(name.firstOrNull()?.code ?: 0) % avatarTints.size]
    Box(Modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.trim().take(1).uppercase().ifEmpty { "?" },
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
internal fun Wordmark(nameSize: TextUnit, subSize: TextUnit, centered: Boolean) {
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        Text("Bayx", fontSize = nameSize, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, color = Ink)
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
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (showArrow) {
                    Spacer(Modifier.width(40.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White)
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
        Icon(Icons.Outlined.Sms, contentDescription = null, tint = Blue, modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(8.dp))
        Wordmark(nameSize = 36.sp, subSize = 30.sp, centered = true)
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
        Icon(Icons.Outlined.Sms, contentDescription = null, tint = Blue, modifier = Modifier.size(80.dp))
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

// ---------- Giriş ve kayıt ----------

@Composable
fun AuthFlow() {
    var registering by remember { mutableStateOf(false) }
    if (registering) {
        RegisterScreen(onDone = { registering = false })
    } else {
        LoginScreen(onRegister = { registering = true })
    }
}

@Composable
private fun AuthPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(PageBrush)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun LoginScreen(onRegister: () -> Unit) {
    // Bilerek remember (rememberSaveable değil): giriş bilgileri hiçbir yere kaydedilmez.
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuthPage {
        Spacer(Modifier.height(64.dp))
        Icon(Icons.Outlined.Sms, contentDescription = "Bayx logosu", tint = Blue, modifier = Modifier.size(104.dp))
        Spacer(Modifier.height(10.dp))
        Wordmark(nameSize = 40.sp, subSize = 34.sp, centered = true)
        Spacer(Modifier.height(44.dp))
        PillField(user, { user = it }, "Kullanıcı Adı", Icons.Outlined.Person)
        Spacer(Modifier.height(16.dp))
        PillField(pass, { pass = it }, "Şifre", Icons.Outlined.Lock, isPassword = true)
        Spacer(Modifier.height(30.dp))
        GradientButton("Giriş Yap", busy = busy) {
            if (user.isBlank() || pass.isEmpty()) {
                info = "Kullanıcı adını ve şifreni yaz."
            } else {
                busy = true
                scope.launch {
                    val error = Bayx.login(user, pass)
                    pass = ""
                    busy = false
                    if (error != null) info = error
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRegister) { Text("Hesap Oluştur", fontSize = 15.sp) }
            Box(Modifier.width(1.dp).height(16.dp).background(SoftBlue))
            TextButton(onClick = {
                info = "Şifreni yalnızca yönetici sıfırlayabilir. Lütfen yöneticine başvur."
            }) { Text("Şifremi Unuttum", fontSize = 15.sp) }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Yeni hesaplar yönetici onayıyla açılır.\nBu cihazda kullanıcı adı ve şifre saklanmaz.",
            color = Muted,
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(24.dp))
    }

    info?.let { message -> InfoDialog(message) { info = null } }
}

@Composable
private fun RegisterScreen(onDone: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AuthPage {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Hesap Oluştur", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text("Kaydın yönetici onayından sonra açılır.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))
        PillField(user, { user = it.lowercase().trim() }, "Kullanıcı adı (küçük harf, rakam)", Icons.Outlined.Person)
        Spacer(Modifier.height(14.dp))
        PillField(name, { name = it }, "Görünen ad", Icons.Outlined.Badge, keyboardType = KeyboardType.Text)
        Spacer(Modifier.height(14.dp))
        PillField(pass, { pass = it }, "Şifre (en az 8 karakter)", Icons.Outlined.Lock, isPassword = true)
        Spacer(Modifier.height(14.dp))
        PillField(pass2, { pass2 = it }, "Şifre (tekrar)", Icons.Outlined.Lock, isPassword = true)
        Spacer(Modifier.height(28.dp))
        GradientButton("Kayıt Ol", busy = busy, showArrow = false) {
            when {
                !Regex("^[a-z0-9][a-z0-9._-]{2,19}$").matches(user) ->
                    info = "Kullanıcı adı 3-20 karakter olmalı; küçük harf, rakam, nokta, tire ve alt çizgi kullanılabilir."
                name.isBlank() -> info = "Görünen adını yaz."
                pass.length < 8 -> info = "Şifre en az 8 karakter olmalı."
                pass != pass2 -> info = "Şifreler aynı değil."
                else -> {
                    busy = true
                    scope.launch {
                        try {
                            Api.register(user, pass, name.trim())
                            success = true
                        } catch (e: ApiException) {
                            info = e.message
                        }
                        pass = ""
                        pass2 = ""
                        busy = false
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    info?.let { message -> InfoDialog(message) { info = null } }
    if (success) {
        InfoDialog("Kaydın alındı. Yönetici onayladıktan sonra kullanıcı adın ve şifrenle giriş yapabilirsin.") { onDone() }
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
fun ChatListScreen(me: AuthState.LoggedIn, onOpenChat: (String) -> Unit, onProfile: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val rooms by Bayx.rooms.collectAsState()
    val onlineUsers by Bayx.online.collectAsState()
    var members by remember { mutableStateOf<List<Member>?>(null) }
    var membersError by remember { mutableStateOf<String?>(null) }
    var showNewGroup by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val tabs = listOf(
        "Sohbetler" to Icons.Outlined.ChatBubbleOutline,
        "Kişiler" to Icons.Outlined.Group,
        "Gruplar" to Icons.Outlined.Groups,
    )

    LaunchedEffect(tab, showNewGroup) {
        if ((tab == 1 || showNewGroup) && members == null) {
            val token = Bayx.accessToken
            try {
                members = if (token == null) emptyList() else Api.members(token).filter { it.userId != me.userId }
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

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Sms, contentDescription = null, tint = Blue, modifier = Modifier.size(44.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) { Wordmark(nameSize = 23.sp, subSize = 18.sp, centered = false) }
                IconButton(onClick = { toast("Arama sonraki sürümde eklenecek.") }) {
                    Icon(Icons.Outlined.Search, "Ara", tint = Ink, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = { toast("Bildirimler sonraki sürümde eklenecek.") }) {
                    Icon(Icons.Outlined.Notifications, "Bildirimler", tint = Ink, modifier = Modifier.size(26.dp))
                }
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
                    0 -> if (rooms.isEmpty()) {
                        EmptyHint("Henüz sohbet yok.\nKişiler sekmesinden birini seçerek başla.")
                    } else {
                        LazyColumn {
                            items(rooms, key = { it.id }) { chat -> ChatRow(chat, online = chat.dmUserId != null && chat.dmUserId in onlineUsers) { onOpenChat(chat.id) } }
                        }
                    }
                    1 -> {
                        val list = members
                        when {
                            membersError != null -> EmptyHint(membersError ?: "")
                            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Blue)
                            }
                            list.isEmpty() -> EmptyHint("Henüz başka üye yok.\nArkadaşların kayıt olduğunda burada görünür.")
                            else -> LazyColumn {
                                items(list, key = { it.userId }) { member ->
                                    PersonRow(member.displayName, member.userId.substringBefore(":"), online = member.userId in onlineUsers) { openDirect(member) }
                                }
                            }
                        }
                    }
                    else -> {
                        val groups = rooms.filter { !it.isDirect }
                        Column(Modifier.fillMaxSize()) {
                            TextButton(
                                onClick = { showNewGroup = true },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            ) { Text("+ Yeni grup oluştur", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                            if (groups.isEmpty()) {
                                EmptyHint("Henüz grup yok.")
                            } else {
                                LazyColumn {
                                    items(groups, key = { it.id }) { chat -> ChatRow(chat, online = chat.dmUserId != null && chat.dmUserId in onlineUsers) { onOpenChat(chat.id) } }
                                }
                            }
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth().height(76.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
                shadowElevation = 6.dp,
            ) {
                Row {
                    NavItem("Sohbetler", Icons.Filled.Sms, selected = true) { tab = 0 }
                    NavItem("Aramalar", Icons.Outlined.Call, selected = false) { toast("Aramalar sonraki sürümde eklenecek.") }
                    NavItem("Medya", Icons.Outlined.PermMedia, selected = false) { toast("Medya sonraki sürümde eklenecek.") }
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
private fun PersonRow(name: String, handle: String, online: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name, 56.dp, online = online)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(handle, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, online: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(chat.name, 60.dp, online = online)
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
                Text(formatTime(chat.time), color = Ink, fontSize = 14.sp)
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
