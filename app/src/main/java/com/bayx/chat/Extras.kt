package com.bayx.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.bayx.chat.data.Api
import com.bayx.chat.data.ApiException
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.ChatPrefs
import com.bayx.chat.data.ChatSummary
import com.bayx.chat.data.Comfort
import com.bayx.chat.data.ContactsRepo
import com.bayx.chat.data.Gate
import com.bayx.chat.data.MediaRef
import com.bayx.chat.data.ReachRepo
import com.bayx.chat.data.RecoveryRepo
import com.bayx.chat.data.Timed
import com.bayx.chat.data.Transfer
import kotlinx.coroutines.launch
import java.util.Calendar

// ====================================================================== davet linki / QR ile kişi

/** Davet linkinden ya da QR'dan gelen kullanıcı: kim olduğunu göster, tek dokunuşla sohbet aç. */
@Composable
fun InviteOpenDialog(name: String, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var hit by remember { mutableStateOf<Api.ContactFind?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(name) {
        hit = ContactsRepo.findUser(name)
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Tayfa daveti")) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val h = hit
                when {
                    loading -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    h == null || !h.found -> Text(t("Bu davet linki geçerli değil. Kişi kullanıcı adını değiştirmiş olabilir."), color = Muted, textAlign = TextAlign.Center)
                    else -> {
                        Avatar(h.displayName, 76.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(h.displayName, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("@" + h.username, color = Muted, fontSize = 14.sp)
                        if (h.relation == "self") Text(t("Bu senin linkin."), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            val h = hit
            if (h != null && h.found && h.relation != "self") {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        // WhatsApp gibi: istek yok, sohbet doğrudan açılır.
                        val roomId = Bayx.startDirectChat(h.userId)
                        busy = false
                        if (roomId != null) onOpen(roomId) else onDismiss()
                    }
                }) { Text(if (busy) t("Bekle…") else t("Sohbet başlat")) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Kapat")) } },
    )
}

/** Kendi QR kodum ve davet linkim; başkasının QR'ını okutma. */
@Composable
fun MyQrDialog(username: String, customUsername: Boolean, name: String, onChooseUsername: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    val link = inviteLink(username)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("QR kodum")) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    t("Arkadaşın bu kodu Tayfa'da okutunca seni hemen ekler. Linki WhatsApp ya da SMS ile de gönderebilirsin."),
                    color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(12.dp)) { QrImage(link, 210.dp) }
                Spacer(Modifier.height(8.dp))
                Text(name, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("@$username", color = Muted, fontSize = 14.sp)
                if (!customUsername) {
                    TextButton(onClick = onChooseUsername) { Text(t("Daha kolay bir kullanıcı adı seç")) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { shareText(context, tf("Tayfa'da benimle konuş: %s", link)) }) {
                        Icon(Icons.Outlined.Share, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Linki paylaş"))
                    }
                    TextButton(onClick = { scanning = true }) {
                        Icon(Icons.Outlined.QrCodeScanner, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Kod okut"))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Tamam")) } },
    )
    if (scanning) {
        ScanQrDialog(onDismiss = { scanning = false }) { text ->
            scanning = false
            handleScannedText(text)
            onDismiss()
        }
    }
}

/** Okutulan kodu yönlendirir: kişi linki -> kişi daveti. Başka bir şeyse false. */
internal fun handleScannedText(text: String): Boolean {
    val name = Regex("""(?:tayfa://t/|/t/)([A-Za-z0-9._-]{3,20})""").find(text.trim())?.groupValues?.get(1)
    if (name != null) {
        Bayx.pendingInvite.value = name
        return true
    }
    return false
}

/** Tam ekran QR okuyucu (kamera izni dahil). */
@Composable
fun ScanQrDialog(onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        if (!ok) onDismiss()
    }
    LaunchedEffect(Unit) { if (!allowed) permission.launch(Manifest.permission.CAMERA) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (allowed) QrScanner(onResult = onResult, onCancel = onDismiss)
            IconButton(onClick = onDismiss, modifier = Modifier.systemBarsPadding().padding(8.dp)) {
                Icon(Icons.Outlined.Close, t("Kapat"), tint = Color.White)
            }
        }
    }
}

// ====================================================================== sohbet kilidi

/** Kilitli sohbet açılırken parmak izi / ekran kilidi ister; bu açılışta bir kez sorulur. */
@Composable
fun ChatLockGate(roomId: String, onCancel: () -> Unit, content: @Composable () -> Unit) {
    val flags by ChatPrefs.state.collectAsState()
    val locked = flags[roomId]?.locked == true
    var open by remember(roomId) { mutableStateOf(!locked || !Gate.lockAvailable()) }
    if (open) {
        content()
        return
    }
    val activity = LocalContext.current as? FragmentActivity
    var tries by remember { mutableStateOf(0) }
    LaunchedEffect(tries) {
        val host = activity ?: run { open = true; return@LaunchedEffect }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(t("Kilitli sohbet"))
            .setSubtitle(t("Açmak için parmak izini ya da ekran kilidini kullan"))
            .setAllowedAuthenticators(Gate.AUTHENTICATORS)
            .build()
        BiometricPrompt(host, ContextCompat.getMainExecutor(host), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                open = true
            }
        }).authenticate(info)
    }
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().background(PageBrush), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Fingerprint, null, tint = Blue, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(14.dp))
            Text(t("Bu sohbet kilitli"), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(22.dp))
            SandButton(t("Kilidi aç"), icon = Icons.Outlined.Fingerprint, modifier = Modifier.width(220.dp)) { tries++ }
            TextButton(onClick = onCancel) { Text(t("Geri dön")) }
        }
    }
}

// ====================================================================== kolaylıklar

@Composable
fun ComfortScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val reach by ReachRepo.reach.collectAsState()
    val quietOn by Comfort.quietEnabled.collectAsState()
    val quietFrom by Comfort.quietFrom.collectAsState()
    val quietTo by Comfort.quietTo.collectAsState()
    val scale by Comfort.textScale.collectAsState()
    val replies by Comfort.quickReplies.collectAsState()
    val jobs by Timed.jobs.collectAsState()
    var editReply by remember { mutableStateOf<Int?>(null) }
    var callReport by remember { mutableStateOf<String?>(null) }
    val readReceipts by Comfort.readReceipts.collectAsState()
    val undoSend by Comfort.undoSend.collectAsState()
    var callTesting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ReachRepo.refresh(force = true) }

    fun pickTime(initial: Int, onPick: (Int) -> Unit) {
        android.app.TimePickerDialog(context, { _, h, m -> onPick(h * 60 + m) }, initial / 60, initial % 60, true).show()
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Ink) }
            Text(t("Kolaylıklar"), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // ---- Gelen arama tam ekran izinleri
            ComfortTitle(t("Aramalar"))
            CallPermissionsCard()

            // ---- Arama bağlantı testi
            ComfortTitle(t("Arama bağlantı testi"))
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        t("Aramalar bağlanmıyorsa bu testi çalıştır: telefonun arama sunucusuna ulaşabiliyor mu, hemen görürsün."),
                        color = Muted, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    callReport?.let { Text(it, color = Ink, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 10.dp)) }
                    TextButton(enabled = !callTesting, onClick = {
                        callTesting = true
                        callReport = null
                        scope.launch {
                            callReport = com.bayx.chat.data.Calls.connectivityTest()
                            callTesting = false
                        }
                    }) { Text(if (callTesting) t("Test ediliyor…") else t("Testi başlat")) }
                }
            }

            // ---- Kimler ulaşabilir
            ComfortTitle(t("Kimler bana ulaşabilir"))
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    listOf(
                        ReachRepo.EVERYONE to (t("Herkes") to t("Herkes seni arayabilir ve sana yazabilir.")),
                        ReachRepo.CONTACTS to (t("Sadece kişilerim") to t("Yalnızca kişilerin ve rehberinde kayıtlı olanlar arar. Diğerlerinin mesajları sessizce \"Mesaj istekleri\"ne düşer.")),
                    ).forEach { (value, texts) ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch { if (!ReachRepo.set(value)) toast(t("Ayar kaydedilemedi. İnternet bağlantını kontrol et.")) }
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reach == value, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(texts.first, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(texts.second, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
            }

            // ---- Gizlilik ve mesajlaşma
            ComfortTitle(t("Mesajlaşma"))
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    ToggleLine(
                        t("Okundu bilgisi"),
                        t("Kapatırsan karşı taraf mesajını okuduğunu görmez (mavi tik gitmez)."),
                        readReceipts,
                    ) { com.bayx.chat.data.Comfort.setReadReceipts(it) }
                    HorizontalDivider(color = Line)
                    ToggleLine(
                        t("Gönderimi geri al"),
                        t("Gönder'e bastıktan sonra 4 saniye içinde \"Geri al\" diyebilirsin."),
                        undoSend,
                    ) { com.bayx.chat.data.Comfort.setUndoSend(it) }
                }
            }

            // ---- Sessiz saatler
            ComfortTitle(t("Sessiz saatler"))
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("Gece rahatsız etme"), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                t("Bu saatlerde mesaj bildirimleri sessiz gelir. Sabitlediğin sohbetler ve aramalar yine çalar."),
                                color = Muted, fontSize = 13.sp, lineHeight = 18.sp,
                            )
                        }
                        Switch(checked = quietOn, onCheckedChange = { Comfort.setQuiet(it, quietFrom, quietTo) })
                    }
                    if (quietOn) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TimeChip(t("Başlangıç"), Comfort.label(quietFrom)) { pickTime(quietFrom) { Comfort.setQuiet(true, it, quietTo) } }
                            TimeChip(t("Bitiş"), Comfort.label(quietTo)) { pickTime(quietTo) { Comfort.setQuiet(true, quietFrom, it) } }
                        }
                    }
                }
            }

            // ---- Yazı boyutu
            ComfortTitle(t("Yazı boyutu"))
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    listOf(1f to t("Normal"), 1.15f to t("Büyük"), 1.3f to t("Çok büyük")).forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { Comfort.setTextScale(value) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = kotlin.math.abs(scale - value) < 0.01f, onClick = null)
                            Spacer(Modifier.width(10.dp))
                            Text(label, color = Ink, fontSize = (16 * value / scale).sp)
                        }
                    }
                }
            }

            // ---- Hazır cevaplar
            ComfortTitle(t("Hazır cevaplar"))
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(t("Bildirimin altında tek dokunuşla gönderilir."), color = Muted, fontSize = 13.sp)
                    replies.forEachIndexed { i, r ->
                        Row(Modifier.fillMaxWidth().clickable { editReply = i }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t(r), color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(t("Değiştir"), color = SoftBlue, fontSize = 13.sp)
                        }
                        HorizontalDivider(color = Line)
                    }
                    TextButton(onClick = { Comfort.setQuickReplies(Comfort.DEFAULT_REPLIES) }) { Text(t("Varsayılana dön")) }
                }
            }

            // ---- Zamanlanmış mesajlar ve hatırlatmalar
            ComfortTitle(t("Zamanlanmış mesajlar ve hatırlatmalar"))
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    if (jobs.isEmpty()) {
                        Text(
                            t("Henüz yok. Gönder düğmesine basılı tutarak mesaj zamanlayabilir, bir mesaja basılı tutup \"Hatırlat\" diyebilirsin."),
                            color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                    jobs.sortedBy { it.at }.forEach { job ->
                        TimedRow(job) { Timed.cancel(job.id) }
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
        }
    }

    editReply?.let { i ->
        TextInputDialog(title = t("Hazır cevap"), initial = t(replies[i]), maxLength = 60, onDismiss = { editReply = null }) { value ->
            editReply = null
            Comfort.setQuickReplies(replies.toMutableList().also { it[i] = value })
        }
    }
}

@Composable
private fun ComfortTitle(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
private fun TimeChip(label: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Line.copy(alpha = 0.6f)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun TimedRow(job: Timed.Job, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Schedule, null, tint = Blue, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (if (job.kind == Timed.SEND) t("Gönderilecek") else t("Hatırlatma")) + " · " + whenLabel(job.at) +
                    if (job.roomName.isNotBlank()) " · " + job.roomName else "",
                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(job.text, color = Ink, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onCancel) { Text(t("İptal"), color = Color(0xFFB4232A)) }
    }
}

/** "Bugün 20:00", "Yarın 09:00", "18.11 14:30" */
internal fun whenLabel(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    val now = Calendar.getInstance()
    val time = "%02d:%02d".format(java.util.Locale.US, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    val dayDiff = ((startOfDay(c) - startOfDay(now)) / 86_400_000L).toInt()
    return when (dayDiff) {
        0 -> t("Bugün") + " " + time
        1 -> t("Yarın") + " " + time
        else -> "%02d.%02d %s".format(java.util.Locale.US, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, time)
    }
}

private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** Zaman seçici: hazır seçenekler + tarih/saat. */
@Composable
fun WhenPicker(title: String, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val context = LocalContext.current
    fun at(daysAhead: Int, hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, daysAhead); set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val now = System.currentTimeMillis()
    val options = buildList {
        add(t("1 saat sonra") to now + 3_600_000L)
        add(t("3 saat sonra") to now + 3 * 3_600_000L)
        if (at(0, 20) > now + 600_000L) add(t("Bu akşam 20:00") to at(0, 20))
        add(t("Yarın sabah 09:00") to at(1, 9))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, time) ->
                    Text(
                        label, color = Ink, fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(time) }.padding(vertical = 12.dp),
                    )
                }
                Text(
                    t("Tarih ve saat seç…"), color = Blue, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable {
                        val c = Calendar.getInstance()
                        android.app.DatePickerDialog(context, { _, y, m, d ->
                            android.app.TimePickerDialog(context, { _, h, min ->
                                val picked = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                                if (picked > System.currentTimeMillis() + 30_000) onPick(picked)
                            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
                        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                            datePicker.minDate = System.currentTimeMillis() - 1000
                        }.show()
                    }.padding(vertical = 12.dp),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Vazgeç")) } },
    )
}

// ====================================================================== mesaj istekleri

/** Sohbet listesinin üstünde: tanımadığın kişilerden gelen ve bekleyen sohbetler. */
@Composable
fun MessageRequestsBar(requests: List<ChatSummary>, onOpened: (String) -> Unit) {
    if (requests.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tf("Mesaj istekleri (%s)", requests.size), color = Blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(t("Gör"), color = SoftBlue, fontSize = 14.sp)
    }
    if (open) {
        val scope = rememberCoroutineScope()
        val toast = rememberToast()
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(t("Mesaj istekleri")) },
            text = {
                Column {
                    Text(
                        t("Bu kişiler kişilerinde değil. Kabul edersen sohbet açılır; reddedersen haberi olmaz."),
                        color = Muted, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                    requests.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(r.name, 40.dp, url = r.avatarUrl)
                            Spacer(Modifier.width(10.dp))
                            Text(r.name, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch {
                                    if (Bayx.acceptRequest(r.id)) { open = false; onOpened(r.id) } else toast(t("İşlem yapılamadı. Tekrar dene."))
                                }
                            }) { Text(t("Kabul et")) }
                            TextButton(onClick = { scope.launch { Bayx.declineRequest(r.id) } }) { Text(t("Reddet"), color = Color(0xFFB4232A)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(t("Kapat")) } },
        )
    }
}

// ====================================================================== yeni telefona taşıma

/** Eski telefonda: yeni telefondaki QR'ı okut, mesaj yedeğinin anahtarını şifreli gönder. */
@Composable
fun TransferSendDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(t("Yeni telefona aktar")) },
        text = {
            Column {
                if (success) {
                    Text(t("Aktarıldı ✓ Yeni telefonunda eski mesajların birkaç dakika içinde görünür."), color = Color(0xFF1E8E4E), fontSize = 15.sp, lineHeight = 21.sp)
                } else {
                    Text(
                        t("1. Yeni telefona Tayfa'yı kur ve aynı numarayla giriş yap.\n2. Yeni telefonda \"Eski telefonumdan aktar\"a dokun; bir QR kodu çıkar.\n3. Aşağıdaki düğmeyle o kodu okut."),
                        color = Ink, fontSize = 14.sp, lineHeight = 21.sp,
                    )
                    result?.let { Text(it, color = Color(0xFFB4232A), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                    if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(top = 12.dp).size(24.dp))
                }
            }
        },
        confirmButton = {
            if (success) {
                TextButton(onClick = onDismiss) { Text(t("Tamam")) }
            } else {
                TextButton(enabled = !busy, onClick = { scanning = true }) { Text(t("QR kodu okut")) }
            }
        },
        dismissButton = { if (!success) TextButton(enabled = !busy, onClick = onDismiss) { Text(t("Vazgeç")) } },
    )
    if (scanning) {
        ScanQrDialog(onDismiss = { scanning = false }) { text ->
            scanning = false
            if (!text.startsWith(Transfer.QR_PREFIX)) {
                result = t("Bu bir aktarım kodu değil. Yeni telefondaki \"Eski telefonumdan aktar\" kodunu okut.")
                return@ScanQrDialog
            }
            busy = true
            result = null
            scope.launch {
                val (key, _) = RecoveryRepo.keyForTransfer()
                val error = if (key == null) "Mesaj yedeği hazırlanamadı. İnternet bağlantını kontrol et." else Transfer.send(text, key)
                busy = false
                if (error == null) success = true else result = t(error)
            }
        }
    }
}

/** Yeni telefonda: QR göster, eski telefon okutunca eski mesajları geri getir. */
@Composable
fun TransferReceiveDialog(onDone: (Boolean) -> Unit) {
    var receiver by remember { mutableStateOf<Transfer.Receiver?>(null) }
    var status by remember { mutableStateOf(t("Hazırlanıyor…")) }
    var failed by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round) {
        failed = false
        status = t("Hazırlanıyor…")
        val r = Transfer.startReceiving()
        if (r == null) { failed = true; status = t("Kod alınamadı. İnternet bağlantını kontrol et."); return@LaunchedEffect }
        receiver = r
        status = t("Eski telefonunda Tayfa > Ayarlar > \"Yeni telefona aktar\"a dokun ve bu kodu okut.")
        val key = r.await()
        if (key == null) { failed = true; status = t("Süre doldu ya da aktarım yapılamadı. Yeni kod al."); receiver = null; return@LaunchedEffect }
        status = t("Eski mesajların geri getiriliyor…")
        val error = RecoveryRepo.recoverWithKey(key)
        if (error == null) onDone(true) else { failed = true; status = t(error) }
    }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(t("Eski telefonumdan aktar")) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                receiver?.let {
                    Box(Modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).padding(12.dp)) { QrImage(it.qr, 220.dp) }
                    Spacer(Modifier.height(10.dp))
                }
                Text(status, color = if (failed) Color(0xFFB4232A) else Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
            }
        },
        confirmButton = { if (failed) TextButton(onClick = { round++ }) { Text(t("Yeni kod al")) } },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text(t("Vazgeç")) } },
    )
}

// ====================================================================== sesli mesajı yazıya çevirme

/** Çevrilen metinler bu açılış boyunca hatırlanır (mesaj anahtarına göre). */
internal val transcripts = mutableStateMapOf<String, String>()

@Composable
internal fun TranscribeLine(key: String, media: MediaRef, tint: Color) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val text = transcripts[key]
    when {
        text != null -> Text(
            text.ifBlank { t("(konuşma algılanamadı)") },
            color = tint, fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.padding(top = 6.dp).width(220.dp),
        )
        busy -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(t("Yazıya çevriliyor…"), color = tint.copy(alpha = 0.8f), fontSize = 13.sp)
        }
        else -> Text(
            error ?: t("Yazıya çevir"),
            color = tint.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable {
                val source = media.source ?: return@clickable
                val token = Bayx.accessToken ?: return@clickable
                busy = true
                error = null
                scope.launch {
                    try {
                        val bytes = Bayx.loadMedia(source) ?: throw ApiException("SES", "Ses dosyası açılamadı.")
                        transcripts[key] = Api.transcribe(token, bytes, media.mime)
                    } catch (e: ApiException) {
                        error = t(e.message ?: "Yazıya çevrilemedi.")
                    } finally {
                        busy = false
                    }
                }
            }.padding(vertical = 2.dp),
        )
    }
}


@Composable
private fun ToggleLine(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** Yıldızlı mesajlar: işaretlediğin önemli mesajlar; dokununca sohbet açılır. */
@Composable
fun StarredScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val items by com.bayx.chat.data.Starred.items.collectAsState()
    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Ink) }
            Text(t("Yıldızlı mesajlar"), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (items.isEmpty()) {
            Text(
                t("Henüz yıldızlı mesaj yok. Bir mesaja basılı tutup \"Yıldızla\" diyerek önemli mesajlarını burada toplayabilirsin."),
                color = Muted, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(32.dp),
            )
        }
        androidx.compose.foundation.lazy.LazyColumn {
            items(items.size) { i ->
                val it = items[i]
                Column(
                    Modifier.fillMaxWidth().clickable { onOpenChat(it.roomId) }.padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(it.sender + " · " + it.roomName + " · " + whenLabel(it.time), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(it.text, color = Ink, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = Line, modifier = Modifier.padding(horizontal = 18.dp))
            }
        }
    }
}

// ====================================================================== gelen arama tam ekran izinleri

private fun canOverlay(context: android.content.Context) =
    android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(context)

private fun openFullScreenSetting(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < 34) return
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, android.net.Uri.parse("package:" + context.packageName))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openOverlaySetting(context: android.content.Context) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Gelen aramanın her durumda tam ekran açılması için iki Android izni gerekir:
 * kilit ekranında tam ekran (Android 14+) ve telefon açıkken öne gelebilmek (diğer uygulamaların üzerinde).
 * Eksikse uygulama açılışında bir kez sorulur; Kolaylıklar'dan her zaman açılabilir.
 */
@Composable
fun CallPermissionsPrompt() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("tayfa_arama_izin", android.content.Context.MODE_PRIVATE) }
    var show by remember {
        mutableStateOf(!prefs.getBoolean("soruldu", false) && (!com.bayx.chat.data.Notifier.canFullScreen(context) || !canOverlay(context)))
    }
    if (!show) return
    fun done() { prefs.edit().putBoolean("soruldu", true).apply(); show = false }
    AlertDialog(
        onDismissRequest = { done() },
        title = { Text(t("Gelen aramalar tam ekran açılsın")) },
        text = {
            Text(
                t("Biri seni aradığında arama ekranının telefon kilitliyken de, başka bir uygulamadayken de tam ekran açılması için Android'den izin vermen gerekiyor. Açılan ekranda Tayfa'ya izin ver ve geri dön."),
                color = Muted, fontSize = 14.sp, lineHeight = 20.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                done()
                if (!com.bayx.chat.data.Notifier.canFullScreen(context)) openFullScreenSetting(context) else openOverlaySetting(context)
            }) { Text(t("İzin ver")) }
        },
        dismissButton = { TextButton(onClick = { done() }) { Text(t("Sonra")) } },
    )
}

/** Kolaylıklar ekranındaki durum kartı. */
@Composable
internal fun CallPermissionsCard() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val full = remember(tick) { com.bayx.chat.data.Notifier.canFullScreen(context) }
    val over = remember(tick) { canOverlay(context) }
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(t("Gelen arama ekranı"), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (full) "✓ " else "✗ ") + t("Kilit ekranında tam ekran"), color = if (full) Color(0xFF1E8E4E) else Color(0xFFB4232A), fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (!full) TextButton(onClick = { openFullScreenSetting(context) }) { Text(t("Aç")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (over) "✓ " else "✗ ") + t("Telefon açıkken tam ekran"), color = if (over) Color(0xFF1E8E4E) else Color(0xFFB4232A), fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (!over) TextButton(onClick = { openOverlaySetting(context) }) { Text(t("Aç")) }
            }
        }
    }
}
