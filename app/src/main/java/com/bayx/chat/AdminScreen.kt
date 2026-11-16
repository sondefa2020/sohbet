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
fun AdminScreen(me: AuthState.LoggedIn, onBack: () -> Unit) {
    var data by remember { mutableStateOf<AdminMembers?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
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
                toast(e.message ?: t("İşlem tamamlanamadı."))
            }
            busy = false
            reload++
        }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text(t("Yönetim"), color = Ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
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
                val shown = current.members.filter {
                    query.isBlank() || it.displayName.contains(query, true) || it.userId.contains(query, true)
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(t("Üye sayısı"), color = Muted, fontSize = 13.sp)
                                Text(tf("%s kişi", current.count), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                                Text(t("Üye sınırı yok"), color = Muted, fontSize = 12.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    t("Yönetici olarak her üyenin şifresini sıfırlayabilir ya da hesabını kapatabilirsin."),
                                    color = Muted,
                                    fontSize = 12.5.sp,
                                )
                            }
                        }
                    }
                    item { AppConfigCard() }
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text(t("Üye ara"), color = Muted) },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Muted) },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item { SectionTitle(tf("Üyeler (%s)", shown.size)) }
                    items(shown, key = { it.userId }) { member ->
                        Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                MemberHeader(member)
                                if (member.userId != me.userId) {
                                    Row {
                                        TextButton(enabled = !busy, onClick = { resetFor = member }) { Text(t("Şifre sıfırla")) }
                                        TextButton(enabled = !busy, onClick = { removeFor = member }) {
                                            Text(t("Hesabı kapat"), color = Color(0xFFA32117))
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
            title = { Text(t("Şifre sıfırla")) },
            text = {
                Column {
                    Text(tf("%s için yeni bir şifre belirle. Açık oturumları kapatılır; bu şifreyi kendisine ilet.", member.displayName))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        singleLine = true,
                        label = { Text(t("Yeni şifre (en az 8 karakter)")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = newPassword.length >= 8, onClick = {
                    act("reset-password", member, newPassword, done = t("Şifre değiştirildi."))
                    resetFor = null
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { resetFor = null }) { Text(t("Vazgeç")) } },
        )
    }

    removeFor?.let { member ->
        AlertDialog(
            onDismissRequest = { removeFor = null },
            title = { Text(t("Hesabı kapat")) },
            text = { Text(tf("%s üyelikten çıkarılacak ve hesabı kapatılacak. Bu işlem geri alınamaz.", member.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    act("remove", member, done = t("Hesap kapatıldı."))
                    removeFor = null
                }) { Text("Kapat", color = Color(0xFFA32117)) }
            },
            dismissButton = { TextButton(onClick = { removeFor = null }) { Text(t("Vazgeç")) } },
        )
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
}

@Composable
internal fun MemberHeader(member: Member) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(member.displayName, 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(member.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(member.userId.substringBefore(":").removePrefix("@"), color = Muted, fontSize = 13.sp)
        }
        if (member.admin) Text(t("Yönetici"), color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}


/** Admin paneli: duyuru ve güncelleme bilgisini sunucudan yönet (modüler). */
@Composable
internal fun AppConfigCard() {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var announcement by remember { mutableStateOf("") }
    var apkUrl by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val token = Bayx.accessToken ?: return@LaunchedEffect
        runCatching { Api.getConfig(token) }.getOrNull()?.let {
            announcement = it.announcement
            apkUrl = it.apkUrl
            version = if (it.latestVersion > 0) it.latestVersion.toString() else ""
            loaded = true
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(t("Duyuru ve güncelleme"), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                t("Buradan yazdığın duyuru tüm üyelere anında gösterilir. Yeni APK yayınlayınca bağlantısını ve sürüm ") +
                    t("numarasını gir; eski sürümdeki üyeler \"Yeni sürüm var\" uyarısı alır."),
                color = Muted, fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = announcement,
                onValueChange = { announcement = it.take(500) },
                label = { Text(t("Duyuru (boş bırakılırsa gösterilmez)")) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = apkUrl,
                onValueChange = { apkUrl = it.trim() },
                singleLine = true,
                label = { Text(t("APK bağlantısı (https://)")) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = version,
                onValueChange = { v -> version = v.filter { it.isDigit() }.take(6) },
                singleLine = true,
                label = { Text(t("En son sürüm numarası (versionCode)")) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            SandButton("Kaydet", busy = busy) {
                val token = Bayx.accessToken ?: return@SandButton
                busy = true
                scope.launch {
                    try {
                        Api.setConfig(token, announcement.trim(), version.toIntOrNull() ?: 0, apkUrl.trim())
                        toast("Kaydedildi.")
                    } catch (e: ApiException) {
                        toast(e.message ?: "Kaydedilemedi.")
                    }
                    busy = false
                }
            }
        }
    }
}
