package com.bayx.chat

import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Smartphone
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

@Composable
fun ProfileScreen(
    me: AuthState.LoggedIn,
    onBack: () -> Unit,
    onAdmin: () -> Unit,
    onWebLink: () -> Unit,
    onPhone: () -> Unit = {},
    onComfort: () -> Unit = {},
    onEditProfile: () -> Unit = {},
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var langPick by remember { mutableStateOf(false) }
    var confirmNewKey by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val context = LocalContext.current
    val name = me.displayName.ifEmpty { me.userId.substringBefore(":").removePrefix("@") }
    val myMode by PresenceRepo.myMode.collectAsState()
    val myStatus by PresenceRepo.myStatus.collectAsState()
    val myAvatar by ProfileRepo.myAvatarUrl.collectAsState()
    var editName by remember { mutableStateOf(false) }
    var editStatus by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ProfileRepo.refreshMyAvatar() }
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

    var isAdmin by remember { mutableStateOf(false) }
    var lockOn by remember { mutableStateOf(Gate.lockEnabled && Gate.lockAvailable()) }
    var shareDialog by remember { mutableStateOf<Triple<String, String, Api.CodeReply>?>(null) }
    var working by remember { mutableStateOf(false) }
    var emailState by remember { mutableStateOf<Api.EmailStatus?>(null) }
    var showEmail by remember { mutableStateOf(false) }
    // Kullanıcının seçtiği ad (seçmediyse sunucudaki adı) ve bağlı telefon numarası.
    // Cihazdaki kopya hemen gösterilir (bir kez seçilen kullanıcı adı bir daha "Seç" olarak çıkmaz),
    // sonra sunucudan tazelenir.
    val cached = remember { AccountCache.load(context) }
    var username by remember { mutableStateOf(cached.username.ifBlank { me.userId.substringBefore(":").removePrefix("@") }) }
    var customUsername by remember { mutableStateOf(cached.customUsername) }
    var myPhone by remember { mutableStateOf<String?>(cached.masked.ifBlank { null }) }
    var editUsername by remember { mutableStateOf(false) }
    var showMyQr by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val token = Bayx.accessToken ?: return@LaunchedEffect
        AccountCache.refresh(context)?.let {
            if (it.username.isNotBlank()) username = it.username
            customUsername = it.customUsername
            myPhone = it.masked
        }
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
                        Icon(Icons.Outlined.PhotoCamera, t("Fotoğrafı değiştir"), tint = Color.White, modifier = Modifier.size(19.dp))
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
                            Text(t("Yönetici"), color = Color(0xFFF2C14E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    myStatus.ifBlank { t("Hakkında ekle…") },
                    color = if (myStatus.isBlank()) SoftBlue else Muted,
                    fontSize = 16.sp,
                    fontStyle = if (myStatus.isBlank()) FontStyle.Italic else FontStyle.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { editStatus = true }.padding(vertical = 3.dp),
                )
                Text(
                    if (customUsername) "@$username" else t("Kullanıcı adı seç…"),
                    color = if (customUsername) SoftBlue else Blue,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { editUsername = true }.padding(vertical = 2.dp),
                )
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
                SettingRow(Icons.Outlined.Edit, t("Profili düzenle"), onClick = onEditProfile)
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.AlternateEmail, t("Kullanıcı adı"), trailing = {
                    Text(if (customUsername) "@$username ✓" else t("Seç"), color = if (customUsername) Color(0xFF1E8E4E) else SoftBlue, fontSize = 15.sp, maxLines = 1)
                }) { editUsername = true }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Smartphone, t("Telefon numarası"), trailing = {
                    Text(myPhone?.let { if (it.isBlank()) t("Ekle") else "$it ✓" } ?: "…", color = if (!myPhone.isNullOrBlank()) Color(0xFF1E8E4E) else SoftBlue, fontSize = 15.sp, maxLines = 1)
                }) { onPhone() }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.QrCode2, t("QR kodum ve davet linkim")) { showMyQr = true }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.PhonelinkSetup, t("Yeni telefona aktar")) { showTransfer = true }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Accessibility, t("Kolaylıklar")) { onComfort() }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Lock, t("Gizlilik ve Güvenlik")) {
                    info = t("Mesajların uçtan uca şifrelidir. Bu cihazdaki oturum ve sohbet verileri şifreli saklanır; gelen fotoğraf, video ve sesler galeriye kaydedilmez. Çıkış yaptığında bu cihazdaki her şey silinir.")
                }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Notifications, "Bildirimler") { openNotificationSettings(context) }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Fingerprint, "Uygulama kilidi", trailing = {
                    Switch(
                        checked = lockOn,
                        onCheckedChange = { on ->
                            if (on && !Gate.lockAvailable()) {
                                info = t("Uygulama kilidi için önce telefonuna ekran kilidi ya da parmak izi tanımla.")
                            } else {
                                Gate.lockEnabled = on
                                lockOn = on
                            }
                        },
                    )
                }) {
                    info = t("Açıksa, Tayfa'ya 30 saniyeden uzun süre sonra döndüğünde parmak izi ya da ekran kilidi istenir.")
                }
                SettingRow(Icons.Outlined.DarkMode, t("Karanlık mod"), trailing = {
                    Switch(checked = ThemeState.dark, onCheckedChange = { ThemeState.set(it) })
                }) {
                    ThemeState.toggle()
                }
                val recState by RecoveryRepo.recoveryState.collectAsState()
                SettingRow(Icons.Outlined.Backup, t("Mesaj yedeği"), trailing = {
                    Text(recoveryLabel(recState), color = Muted, fontSize = 14.sp)
                }) {
                    when (recState) {
                        org.matrix.rustcomponents.sdk.RecoveryState.INCOMPLETE -> RecoveryRepo.recoveryPrompt.value = true
                        org.matrix.rustcomponents.sdk.RecoveryState.UNKNOWN -> info = t("Bağlantı kuruluyor, birazdan tekrar dene.")
                        else -> confirmNewKey = true
                    }
                }
                SettingRow(Icons.Outlined.Language, t("Dil"), trailing = {
                    Text(L.lang.native, color = Muted, fontSize = 14.sp)
                }) {
                    langPick = true
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
                            st.verified -> Text(t("Doğrulandı ✓"), color = Color(0xFF2E9E5B), fontSize = 13.sp)
                            else -> Text(t("Doğrula"), color = Color(0xFFE5872A), fontSize = 13.sp)
                        }
                    },
                ) { showEmail = true }
                HorizontalDivider(color = Line)
                SettingRow(Icons.Outlined.Computer, t("Web'e bağlan"), onClick = onWebLink)
                if (isAdmin) {
                    HorizontalDivider(color = Line)
                    SettingRow(Icons.Outlined.HowToReg, t("Yönetim ve davetler"), onClick = onAdmin)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                SettingRow(Icons.Outlined.Info, t("Uygulama Hakkında")) {
                    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
                    info = "Tayfa ${version.orEmpty()}\nÖzel, şifresiz ve uçtan uca şifreli sohbet."
                }
                HorizontalDivider(color = Line)
                SettingRow(Icons.AutoMirrored.Outlined.Logout, t("Çıkış Yap"), tint = Color(0xFFB4232A)) { confirmLogout = true }
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
            onResend = { withToken { token -> emailState = Api.emailResend(token); toast(t("Doğrulama e-postası yeniden gönderildi.")) } },
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
    if (showMyQr) {
        MyQrDialog(username = username, customUsername = customUsername, name = name, onChooseUsername = { showMyQr = false; editUsername = true }) { showMyQr = false }
    }
    if (showTransfer) {
        TransferSendDialog { showTransfer = false }
    }
    if (editUsername) {
        UsernameDialog(
            current = if (customUsername) username else "",
            onDismiss = { editUsername = false },
            onSaved = { saved ->
                username = saved
                customUsername = true
                AccountCache.save(context, AccountCache.Info(saved, true, myPhone.orEmpty()))
                editUsername = false
                toast(tf("Kullanıcı adın artık @%s", saved))
            },
        )
    }
    if (editName) {
        TextInputDialog(
            title = t("Görünen ad"),
            initial = name,
            maxLength = 40,
            onDismiss = { editName = false },
        ) { value ->
            editName = false
            if (value.isNotBlank() && value != name) {
                scope.launch { toast(if (ProfileRepo.setDisplayName(value.trim())) t("Adın güncellendi.") else t("Ad değiştirilemedi.")) }
            }
        }
    }
    if (editStatus) {
        TextInputDialog(
            title = t("Hakkında"),
            initial = myStatus,
            maxLength = 80,
            onDismiss = { editStatus = false },
        ) { value ->
            editStatus = false
            PresenceRepo.setStatusMessage(value.trim())
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
                                PresenceRepo.setMode(mode)
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

    if (confirmNewKey) {
        val enabled = RecoveryRepo.recoveryState.collectAsState().value == org.matrix.rustcomponents.sdk.RecoveryState.ENABLED
        AlertDialog(
            onDismissRequest = { confirmNewKey = false },
            title = { Text(t("Mesaj yedeği")) },
            text = {
                Text(
                    if (enabled) t("Yedeğin açık. Anahtarını kaybettiysen yeni bir anahtar oluşturabilirsin; eski anahtar geçersiz olur.")
                    else t("Yedeği açınca şifreli mesajlarının anahtarları sunucuda, yalnızca senin anahtarınla açılabilecek şekilde saklanır.")
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewKey = false
                    scope.launch { RecoveryRepo.createRecoveryKey()?.let { info = t(it) } }
                }) { Text(if (enabled) t("Yeni anahtar oluştur") else t("Yedeği aç")) }
            },
            dismissButton = { TextButton(onClick = { confirmNewKey = false }) { Text(t("Vazgeç")) } },
        )
    }
    if (langPick) {
        AlertDialog(
            onDismissRequest = { langPick = false },
            title = { Text(t("Dil")) },
            text = {
                Column {
                    AppLang.entries.forEach { lang ->
                        Row(
                            Modifier.fillMaxWidth().clickable { L.set(lang); langPick = false }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = L.lang == lang, onClick = { L.set(lang); langPick = false })
                            Spacer(Modifier.width(8.dp))
                            Text(lang.native, color = Ink, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { langPick = false }) { Text(t("Vazgeç")) } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { if (!loggingOut) confirmLogout = false },
            title = { Text(t("Çıkış yap")) },
            text = {
                Text(t("Oturumun kapatılacak ve bu cihazdaki tüm mesajlar, anahtarlar ve önbellek silinecek. Devam edilsin mi?"))
            },
            confirmButton = {
                TextButton(enabled = !loggingOut, onClick = {
                    loggingOut = true
                    scope.launch { Bayx.logout() }
                }) { Text(if (loggingOut) t("Çıkış yapılıyor…") else t("Çıkış yap")) }
            },
            dismissButton = {
                TextButton(enabled = !loggingOut, onClick = { confirmLogout = false }) { Text(t("Vazgeç")) }
            },
        )
    }
}

@Composable
internal fun SettingRow(
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
        Text(label, modifier = Modifier.weight(1f), color = tint, fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.widthIn(max = 150.dp)) { trailing() }
        } else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SoftBlue)
    }
}

// ---------- Yönetim ----------

@Composable
internal fun TextInputDialog(
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
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Vazgeç")) } },
    )
}

/** Seçilen resmi ortadan kare kırpar, 512 piksele indirger ve JPEG yapar. */
internal fun avatarJpeg(context: android.content.Context, uri: android.net.Uri): ByteArray? = runCatching {
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
internal fun EmailDialog(
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
                    status == null -> Text(t("E-posta durumu yükleniyor…"), color = Muted)
                    !status.mailReady -> Text(t("Sunucuda e-posta gönderimi henüz ayarlı değil. Yönetici ayarladığında kullanabilirsin."), color = Muted, fontSize = 13.5.sp)
                    status.email.isNotBlank() && status.verified -> Text(t("E-postan doğrulandı. Şifreni unutursan bu adrese sıfırlama kodu gönderilir."), color = Muted, fontSize = 13.5.sp)
                    status.email.isNotBlank() -> Text(tf("Doğrulama bağlantısı %s adresine gönderildi. Gelen kutunu ve istenmeyen klasörünü kontrol et. Kalan süre: %s gün.", status.email, status.daysLeft), color = Color(0xFFB07A2E), fontSize = 13.5.sp)
                    else -> Text(t("Şifreni unutursan e-postayla sıfırlayabilmen için bir adres ekle. İsteğe bağlıdır."), color = Muted, fontSize = 13.5.sp)
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
                    TextButton(onClick = onResend) { Text(t("Doğrulama e-postasını yeniden gönder")) }
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
                TextButton(onClick = { onRemove(); onDismiss() }) { Text(t("Kaldır"), color = Muted) }
            } else {
                TextButton(onClick = onDismiss) { Text("Kapat") }
            }
        },
    )
}


/** Kullanıcı adı seçme: yazarken uygun mu diye sunucuya sorulur; alınmışsa öneriler gösterilir. */
@Composable
internal fun UsernameDialog(current: String, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(current) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Api.UsernameCheck?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val wellFormed = Regex("^[a-z0-9][a-z0-9._-]{2,19}$").matches(value)

    // Yazmayı bırakınca denetle.
    LaunchedEffect(value) {
        result = null
        error = null
        if (!wellFormed || value == current) { checking = false; return@LaunchedEffect }
        checking = true
        kotlinx.coroutines.delay(450)
        val token = Bayx.accessToken
        result = if (token == null) null else runCatching { Api.usernameCheck(token, value) }.getOrNull()
        checking = false
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(t("Kullanıcı adı")) },
        text = {
            Column {
                Text(
                    t("Arkadaşların seni bu adla bulabilir. Numaranı vermek istemediğin kişilere kullanıcı adını verebilirsin."),
                    color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { v -> value = v.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in "._-" }.take(20) },
                    singleLine = true,
                    prefix = { Text("@") },
                    placeholder = { Text(t("ornek.ad")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val r = result
                val (label, color) = when {
                    error != null -> error!! to Color(0xFFB4232A)
                    value.isEmpty() -> t("3-20 karakter: küçük harf, rakam, nokta, tire, alt çizgi.") to Muted
                    !wellFormed -> t("En az 3 karakter olmalı ve harf ya da rakamla başlamalı.") to Color(0xFFB4232A)
                    value == current -> t("Şu anki kullanıcı adın.") to Muted
                    checking || r == null -> t("Denetleniyor…") to Muted
                    r.available -> tf("@%s kullanılabilir ✓", value) to Color(0xFF1E8E4E)
                    else -> t(r.message) to Color(0xFFB4232A)
                }
                Text(label, color = color, fontSize = 13.sp, lineHeight = 18.sp)
                if (r != null && !r.available && r.suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(t("Bunlar boşta:"), color = Muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        r.suggestions.forEach { s ->
                            TextButton(onClick = { value = s }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                                Text("@$s", fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && wellFormed && value != current && result?.available == true,
                onClick = {
                    val token = Bayx.accessToken ?: return@TextButton
                    saving = true
                    scope.launch {
                        try {
                            onSaved(Api.usernameSet(token, value).ifBlank { value })
                        } catch (e: ApiException) {
                            error = t(e.message ?: "Kaydedilemedi.")
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text(if (saving) t("Bekle…") else t("Kaydet")) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(t("Vazgeç")) } },
    )
}
