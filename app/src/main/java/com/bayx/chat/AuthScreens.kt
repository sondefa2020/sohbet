package com.bayx.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Api
import com.bayx.chat.data.ApiException
import com.bayx.chat.data.Gate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Şifresiz kayıt ve giriş akışı. Hiçbir ekranda şifre yoktur. */
@Composable
fun AuthFlow() {
    var page by remember { mutableStateOf("hos") }
    var inviteCode by remember { mutableStateOf("") }
    var loginCode by remember { mutableStateOf("") }
    val pendingUser by Gate.pendingUser.collectAsState()
    val link by Gate.link.collectAsState()

    // Tarayıcıdan gelen davet / giriş bağlantısı.
    LaunchedEffect(link) {
        when (val l = link) {
            is Gate.Link.Invite -> { inviteCode = formatCode(l.code, 8); page = "katil" }
            is Gate.Link.Login -> { loginCode = formatCode(l.code, 8); page = "giris" }
            null -> Unit
        }
        Gate.link.value = null
    }

    if (pendingUser != null && Gate.pendingTicket != null) {
        WaitingScreen(pendingUser.orEmpty())
        return
    }
    when (page) {
        "katil" -> JoinScreen(inviteCode, requestOnly = false, onBack = { page = "hos" })
        "istek" -> JoinScreen("", requestOnly = true, onBack = { page = "hos" })
        "giris" -> LoginScreen(loginCode, onBack = { page = "hos"; loginCode = "" })
        else -> WelcomeScreen(
            onJoin = { page = "katil" },
            onLogin = { page = "giris" },
            onRequest = { page = "istek" },
        )
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
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
    }
}

@Composable
private fun WelcomeScreen(onJoin: () -> Unit, onLogin: () -> Unit, onRequest: () -> Unit) {
    AuthPage {
        Spacer(Modifier.height(72.dp))
        LogoMark(132.dp)
        Spacer(Modifier.height(18.dp))
        Text("Tayfa", color = Ink, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
        Text("Şifresiz, özel ve uçtan uca şifreli sohbet", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(48.dp))
        SandButton("Davetle katıl", icon = Icons.Outlined.CardGiftcard, onClick = onJoin)
        Spacer(Modifier.height(14.dp))
        GhostButton("Hesabıma giriş yap", icon = Icons.Outlined.Login, onClick = onLogin)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onRequest) { Text("Davetim yok · Katılma isteği gönder", color = Muted, fontSize = 14.sp) }
        Spacer(Modifier.height(36.dp))
        Text(
            "Şifre yok, SMS yok, Google hesabı yok.\nGirişin bu telefonda şifreli saklanır.",
            color = SoftBlue,
            fontSize = 12.5.sp,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = Muted) },
        supportingText = supporting?.let { { Text(it, fontSize = 12.sp) } },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboardType),
        shape = RoundedCornerShape(20.dp),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

private val USERNAME = Regex("^[a-z0-9][a-z0-9._-]{2,19}$")

@Composable
private fun JoinScreen(initialInvite: String, requestOnly: Boolean, onBack: () -> Unit) {
    var invite by remember { mutableStateOf(initialInvite) }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var userEdited by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuthPage {
        BackRow(onBack)
        LogoMark(84.dp)
        Spacer(Modifier.height(12.dp))
        Text(if (requestOnly) "Katılma isteği" else "Tayfa'ya katıl", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (requestOnly) "İsteğin yöneticiye gider. Onaylanınca uygulama seni kendiliğinden içeri alır."
            else "Davet kodun ve adın yeterli. Şifre belirlemen gerekmez.",
            color = Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                if (!requestOnly) {
                    Text("Davet kodu", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                    CodeField(invite, { invite = it }, "XXXX-XXXX", 8)
                    Spacer(Modifier.height(14.dp))
                }
                TextInput(
                    name,
                    {
                        name = it.take(40)
                        if (!userEdited) username = suggestUsername(name)
                    },
                    "Adın",
                    Icons.Outlined.Badge,
                )
                Spacer(Modifier.height(10.dp))
                TextInput(
                    username,
                    { username = it.lowercase().trim().take(20); userEdited = true },
                    "Kullanıcı adı",
                    Icons.Outlined.Person,
                    keyboardType = KeyboardType.Ascii,
                    supporting = "Arkadaşların seni bununla bulur. Küçük harf, rakam ve nokta.",
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        SandButton(if (requestOnly) "İstek gönder" else "Katıl", busy = busy) {
            when {
                !requestOnly && invite.replace("-", "").length != 8 -> info = "Davet kodunu eksiksiz yaz (8 karakter)."
                name.isBlank() -> info = "Adını yaz."
                !USERNAME.matches(username) ->
                    info = "Kullanıcı adı 3-20 karakter olmalı; küçük harf, rakam, nokta, tire ve alt çizgi kullanılabilir."
                else -> {
                    busy = true
                    scope.launch {
                        try {
                            val reply = Api.register(username, name.trim(), invite.ifBlank { null })
                            when (reply.status) {
                                "ok" -> Gate.enter(reply)?.let { info = it }
                                "pending" -> reply.ticket?.let { Gate.savePending(it, reply.username) }
                                else -> info = "Kayıt tamamlanamadı."
                            }
                        } catch (e: ApiException) {
                            info = e.message
                        }
                        busy = false
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    info?.let { message -> InfoDialog(message) { info = null } }
}

@Composable
private fun WaitingScreen(username: String) {
    var info by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    suspend fun check(manual: Boolean) {
        val ticket = Gate.pendingTicket ?: return
        checking = true
        try {
            val reply = Api.ticket(ticket)
            when (reply.status) {
                "ok" -> Gate.enter(reply)?.let { info = it }
                "rejected" -> {
                    Gate.clearPending()
                    info = "Katılma isteğin kabul edilmedi."
                }
                else -> if (manual) info = "Henüz onaylanmadı. Onaylanınca kendiliğinden giriş yapılacak."
            }
        } catch (e: ApiException) {
            if (e.code == "BILET_YOK") Gate.clearPending()
            if (manual || e.code == "BILET_YOK") info = e.message
        }
        checking = false
    }

    LaunchedEffect(tick) {
        while (true) {
            check(manual = false)
            delay(20_000)
        }
    }

    AuthPage {
        Spacer(Modifier.height(90.dp))
        Box(
            Modifier.size(110.dp).clip(RoundedCornerShape(36.dp)).background(Color(0xFFF3E8DB)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.HourglassTop, contentDescription = null, tint = Color(0xFFB07A4E), modifier = Modifier.size(52.dp)) }
        Spacer(Modifier.height(22.dp))
        Text("Onay bekleniyor", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "@$username için katılma isteğin yöneticiye iletildi. Onaylandığında uygulama seni kendiliğinden içeri alır; " +
                "bu ekranı kapatabilirsin.",
            color = Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        SandButton("Şimdi kontrol et", busy = checking) { scope.launch { check(manual = true) } }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { Gate.clearPending() }) { Text("İsteği bu telefondan kaldır", color = Muted) }
    }
    info?.let { message -> InfoDialog(message) { info = null } }
}

@Composable
private fun LoginScreen(initialCode: String, onBack: () -> Unit) {
    var useRecovery by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf(initialCode) }
    var username by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        when {
            !useRecovery && code.replace("-", "").length != 8 -> info = "Giriş kodunu eksiksiz yaz (8 karakter)."
            useRecovery && username.isBlank() -> info = "Kullanıcı adını yaz."
            useRecovery && recovery.replace("-", "").length != 16 -> info = "Kurtarma kodunu eksiksiz yaz (16 karakter)."
            else -> {
                busy = true
                scope.launch {
                    try {
                        val reply = if (useRecovery) Api.recover(username, recovery) else Api.redeemLink(code)
                        Gate.enter(reply)?.let { info = it }
                    } catch (e: ApiException) {
                        info = e.message
                    }
                    busy = false
                }
            }
        }
    }

    // Bağlantıyla gelindiyse kodu hemen kullan.
    LaunchedEffect(initialCode) { if (initialCode.replace("-", "").length == 8) submit() }

    AuthPage {
        BackRow(onBack)
        LogoMark(84.dp)
        Spacer(Modifier.height(12.dp))
        Text("Giriş yap", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ChipBg).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(false to "Giriş kodu", true to "Kurtarma kodu").forEach { (recoveryTab, label) ->
                val selected = useRecovery == recoveryTab
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) Color.White else Color.Transparent)
                        .clickable { useRecovery = recoveryTab }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = if (selected) Ink else Muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
            }
        }
        Spacer(Modifier.height(18.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                if (!useRecovery) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.QrCode2, contentDescription = null, tint = Muted)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Açık olan diğer telefonunda Profil › Başka cihazda giriş'e dokun ve QR kodu bu telefonun " +
                                "kamerasıyla okut ya da kodu buraya yaz. Yöneticinin gönderdiği giriş bağlantısı da olur.",
                            color = Muted,
                            fontSize = 13.5.sp,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    CodeField(code, { code = it }, "XXXX-XXXX", 8)
                } else {
                    TextInput(username, { username = it.lowercase().trim().take(20) }, "Kullanıcı adı", Icons.Outlined.Person, KeyboardType.Ascii)
                    Spacer(Modifier.height(12.dp))
                    Text("Kurtarma kodu", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
                    CodeField(recovery, { recovery = it }, "XXXX-XXXX-XXXX-XXXX", 16)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Giriş yapınca eski kod geçersiz olur, sana yenisi gösterilir.", color = Muted, fontSize = 12.5.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        SandButton("Giriş yap", busy = busy) { submit() }
        Spacer(Modifier.height(14.dp))
        Text(
            "Kodun yoksa yöneticiden giriş bağlantısı iste.",
            color = SoftBlue,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
    if (busy && initialCode.isNotEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) }
    }
    info?.let { message -> InfoDialog(message) { info = null } }
}
