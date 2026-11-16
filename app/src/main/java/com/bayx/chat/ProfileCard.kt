package com.bayx.chat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Api
import com.bayx.chat.data.AuthState
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.Calls
import com.bayx.chat.data.ChatPrefs
import com.bayx.chat.data.ContactsRepo
import com.bayx.chat.data.PhonebookRepo
import com.bayx.chat.data.PresenceRepo
import com.bayx.chat.data.ProfileRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kişi kartı (WhatsApp'taki "kişi bilgisi"): fotoğraf, ad, hakkında, kullanıcı adı, numara, işlemler. */
@Composable
fun PersonCardScreen(userId: String, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var card by remember { mutableStateOf<com.bayx.chat.data.ProfileCard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    val flags by ChatPrefs.state.collectAsState()
    val presences by PresenceRepo.presences.collectAsState()
    val askCallPermissions = rememberCallPermissions { toast(t("Arama için mikrofon (ve görüntülü aramada kamera) izni gerekli.")) }

    LaunchedEffect(userId) {
        val token = Bayx.accessToken ?: return@LaunchedEffect
        try {
            card = Api.profileCard(token, userId)
        } catch (e: com.bayx.chat.data.ApiException) {
            error = t(e.message ?: "Profil açılamadı.")
        }
    }
    val c = card
    // Rehberindeki adı öncelikli (WhatsApp gibi); yoksa kişinin kendi adı.
    val bookName = PhonebookRepo.nameFor(userId)
    val name = bookName ?: c?.displayName ?: userId.substringBefore(":").removePrefix("@")
    val mode = presences[userId]?.mode ?: c?.mode
    val about = (presences[userId]?.status ?: c?.about).orEmpty()
    val roomId = remember(userId) { Bayx.rooms.value.firstOrNull { it.dmUserId == userId && it.isDirect }?.id }
    val roomFlags = roomId?.let { flags[it] }

    fun openChat(then: (String) -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            val id = Bayx.startDirectChat(userId)
            busy = false
            if (id != null) then(id) else toast(t("Sohbet açılamadı. Tekrar dene."))
        }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Ink) }
            Text(t("Kişi bilgisi"), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Avatar(name, 132.dp, url = c?.avatarUrl?.ifBlank { null }, mode = mode)
            Spacer(Modifier.height(14.dp))
            Text(name, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (c != null) {
                if (bookName != null && bookName != c.displayName) {
                    Text("~" + c.displayName, color = Muted, fontSize = 15.sp)
                }
                Text(
                    if (c.phone.isNotBlank()) PhonebookRepo.pretty(c.phone) else "@" + c.username,
                    color = Muted, fontSize = 16.sp,
                )
                if (!c.blockedMe && mode != null && mode != "offline") {
                    Text(modeLabel(mode), color = modeColor(mode) ?: Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                }
            } else if (error == null) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            }
            error?.let { Text(it, color = Color(0xFFB4232A), fontSize = 14.sp, modifier = Modifier.padding(8.dp)) }

            Spacer(Modifier.height(18.dp))
            // Hızlı işlemler
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CardAction(Icons.AutoMirrored.Outlined.Chat, t("Mesaj"), Modifier.weight(1f)) { openChat(onOpenChat) }
                CardAction(Icons.Outlined.Call, t("Sesli"), Modifier.weight(1f)) {
                    askCallPermissions(false) { Calls.startCall(userId, name, false) }
                }
                CardAction(Icons.Outlined.Videocam, t("Görüntülü"), Modifier.weight(1f)) {
                    askCallPermissions(true) { Calls.startCall(userId, name, true) }
                }
                CardAction(Icons.Outlined.Share, t("Paylaş"), Modifier.weight(1f)) {
                    c?.let { shareText(context, tf("Tayfa'da %s: %s", name, inviteLink(it.username))) }
                }
            }

            Spacer(Modifier.height(14.dp))
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                    Text(t("Hakkında"), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (c?.blockedMe == true) "—" else about.ifBlank { t("Merhaba! Ben de Tayfa kullanıyorum.") },
                        color = Ink, fontSize = 16.sp, lineHeight = 22.sp,
                    )
                    if (c != null) {
                        HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 12.dp))
                        InfoLine(Icons.Outlined.AlternateEmail, "@" + c.username, t("Kullanıcı adı"))
                        if (c.phone.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            InfoLine(Icons.Outlined.Smartphone, PhonebookRepo.pretty(c.phone), t("Cep telefonu"))
                        }
                        Spacer(Modifier.height(10.dp))
                        InfoLine(
                            Icons.Outlined.Lock,
                            t("Mesajlar ve aramalar uçtan uca şifreli"),
                            t("Bu sohbeti senden ve kişiden başka kimse okuyamaz."),
                        )
                    }
                }
            }

            if (roomId != null) {
                Spacer(Modifier.height(12.dp))
                SoftCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                        SettingRow(
                            Icons.Outlined.NotificationsOff,
                            if (roomFlags?.muted == true) t("Sesi aç") else t("Sessize al"),
                        ) { ChatPrefs.toggleMute(roomId) }
                        HorizontalDivider(color = Line)
                        SettingRow(
                            if (roomFlags?.locked == true) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                            if (roomFlags?.locked == true) t("Sohbet kilidini kaldır") else t("Sohbeti kilitle"),
                        ) { ChatPrefs.toggleLock(roomId) }
                    }
                }
            }

            if (c != null && c.relation != "self") {
                Spacer(Modifier.height(12.dp))
                SoftCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                        SettingRow(
                            Icons.Outlined.Block,
                            if (c.relation == "blocked") tf("%s engelini kaldır", name) else tf("%s adlı kişiyi engelle", name),
                            tint = Color(0xFFB4232A),
                        ) {
                            if (c.relation == "blocked") {
                                scope.launch {
                                    if (ContactsRepo.unblockContact(c.username)) {
                                        card = c.copy(relation = "none")
                                        toast(t("Engel kaldırıldı."))
                                    }
                                }
                            } else {
                                confirmBlock = true
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (confirmBlock && c != null) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text(tf("%s engellensin mi?", name)) },
            text = { Text(t("Engellenen kişi seni arayamaz ve sana mesaj gönderemez. Bundan haberi olmaz."), color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    scope.launch {
                        if (ContactsRepo.blockContact(c.username)) {
                            card = c.copy(relation = "blocked")
                            toast(t("Engellendi."))
                        } else {
                            toast(t("İşlem yapılamadı. Tekrar dene."))
                        }
                    }
                }) { Text(t("Engelle"), color = Color(0xFFB4232A)) }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(t("Vazgeç")) } },
        )
    }
}

@Composable
private fun CardAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = Blue, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Ink, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun InfoLine(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Ink, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}

/** Kişinin davet / profil linki. */
internal fun inviteLink(username: String) = Api.HOMESERVER.trimEnd('/') + "/t/" + username

// ---------------------------------------------------------------- Profili düzenle

private val ABOUT_PRESETS = listOf(
    "Müsait", "Meşgul", "Toplantıdayım", "İşteyim", "Okuldayım", "Yoldayım",
    "Sadece acil aramalar", "Uyuyorum 😴", "Pilim azalıyor 🔋",
)

/** Kendi profilini düzenleme: fotoğraf, ad, hakkında, kullanıcı adı, telefon numarası. */
@Composable
fun EditProfileScreen(me: AuthState.LoggedIn, onBack: () -> Unit, onPhone: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val myAvatar by ProfileRepo.myAvatarUrl.collectAsState()
    val myStatus by PresenceRepo.myStatus.collectAsState()
    var name by remember { mutableStateOf(me.displayName.ifEmpty { me.userId.substringBefore(":").removePrefix("@") }) }
    var account by remember { mutableStateOf(AccountCache.load(context)) }
    var editName by remember { mutableStateOf(false) }
    var editAbout by remember { mutableStateOf(false) }
    var editUsername by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ProfileRepo.refreshMyAvatar()
        AccountCache.refresh(context)?.let { account = it }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            uploading = true
            scope.launch {
                val jpeg = withContext(Dispatchers.IO) { avatarJpeg(context, uri) }
                val ok = jpeg != null && ProfileRepo.setAvatar(jpeg)
                uploading = false
                toast(if (ok) t("Profil fotoğrafı güncellendi.") else t("Fotoğraf yüklenemedi."))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Ink) }
            Text(t("Profili düzenle"), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.clickable(enabled = !uploading) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Avatar(name, 132.dp, url = myAvatar)
                Box(
                    Modifier.align(Alignment.BottomEnd).size(40.dp).clip(CircleShape).background(BlueBrush),
                    contentAlignment = Alignment.Center,
                ) {
                    if (uploading) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Outlined.PhotoCamera, t("Fotoğrafı değiştir"), tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text(t("Fotoğrafı değiştir"))
            }
            Spacer(Modifier.height(8.dp))
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                    EditRow(Icons.Outlined.Person, t("Ad"), name, t("Kişilerin seni bu adla görür.")) { editName = true }
                    HorizontalDivider(color = Line)
                    EditRow(Icons.Outlined.Info, t("Hakkında"), myStatus.ifBlank { t("Bir şeyler yaz…") }, null) { editAbout = true }
                    HorizontalDivider(color = Line)
                    EditRow(
                        Icons.Outlined.AlternateEmail, t("Kullanıcı adı"),
                        if (account.customUsername) "@" + account.username else t("Seç"),
                        if (account.customUsername) t("Ayarlandı ✓ · değiştirmek için dokun") else t("Numaranı vermeden seni bulabilsinler."),
                        done = account.customUsername,
                    ) { editUsername = true }
                    HorizontalDivider(color = Line)
                    EditRow(
                        Icons.Outlined.Smartphone, t("Telefon"),
                        account.masked.ifBlank { t("Ekle") },
                        if (account.masked.isNotBlank()) t("Doğrulandı ✓") else t("Numaranı ekle, rehberindeki kişiler seni bulsun."),
                        done = account.masked.isNotBlank(),
                    ) { onPhone() }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    if (editName) {
        TextInputDialog(title = t("Ad"), initial = name, maxLength = 40, onDismiss = { editName = false }) { value ->
            editName = false
            val v = value.trim()
            if (v.isNotBlank() && v != name) {
                scope.launch {
                    if (ProfileRepo.setDisplayName(v)) { name = v; toast(t("Adın güncellendi.")) } else toast(t("Ad değiştirilemedi."))
                }
            }
        }
    }
    if (editAbout) {
        AboutDialog(initial = myStatus, onDismiss = { editAbout = false }) { value ->
            editAbout = false
            PresenceRepo.setStatusMessage(value.trim())
        }
    }
    if (editUsername) {
        UsernameDialog(
            current = if (account.customUsername) account.username else "",
            onDismiss = { editUsername = false },
            onSaved = { saved ->
                editUsername = false
                account = account.copy(username = saved, customUsername = true)
                AccountCache.save(context, account)
                toast(tf("Kullanıcı adın artık @%s", saved))
            },
        )
    }
}

@Composable
private fun EditRow(icon: ImageVector, label: String, value: String, hint: String?, done: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Muted, fontSize = 13.sp)
            Text(value, color = Ink, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (hint != null) Text(hint, color = if (done) Color(0xFF1E8E4E) else Muted, fontSize = 12.sp)
        }
        if (done) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color(0xFF1E8E4E), modifier = Modifier.size(22.dp))
    }
}

/** "Hakkında" yazısı: hazır seçenekler (WhatsApp gibi) ya da kendi yazın. */
@Composable
private fun AboutDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Hakkında")) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= 80) value = it },
                    singleLine = false,
                    maxLines = 3,
                    supportingText = { Text("${value.length}/80") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(t("Hazır seçenekler"), color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                ABOUT_PRESETS.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                        row.forEach { p ->
                            Text(
                                t(p), color = Ink, fontSize = 13.sp, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Line.copy(alpha = 0.6f))
                                    .clickable { value = t(p) }.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(t("Kaydet")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Vazgeç")) } },
    )
}

/**
 * Hesap bilgisinin (kullanıcı adı, numara) cihazdaki kopyası: ekran açılır açılmaz doğru görünsün,
 * "Kullanıcı adı seç" bir kez ayarlandıktan sonra bir daha çıkmasın.
 */
internal object AccountCache {
    data class Info(val username: String = "", val customUsername: Boolean = false, val masked: String = "")

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences("tayfa_hesap", android.content.Context.MODE_PRIVATE)

    fun load(context: android.content.Context): Info {
        val p = prefs(context)
        return Info(p.getString("ad", "").orEmpty(), p.getBoolean("ozel", false), p.getString("numara", "").orEmpty())
    }

    fun save(context: android.content.Context, info: Info) {
        prefs(context).edit().putString("ad", info.username).putBoolean("ozel", info.customUsername)
            .putString("numara", info.masked).apply()
    }

    suspend fun refresh(context: android.content.Context): Info? {
        val token = Bayx.accessToken ?: return null
        val a = runCatching { Api.accountInfo(token) }.getOrNull() ?: return null
        val own = Bayx.myLocalpart()
        // Sunucudaki gerçek ad dışında bir ad görünüyorsa kullanıcı kendi adını seçmiş demektir.
        val info = Info(a.username, a.customUsername || (a.username.isNotBlank() && a.username != own), a.masked)
        save(context, info)
        return info
    }

    fun clear(context: android.content.Context) {
        prefs(context).edit().clear().apply()
    }
}
