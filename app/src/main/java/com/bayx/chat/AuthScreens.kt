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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Api
import com.bayx.chat.data.ApiException
import com.bayx.chat.data.Bayx
import kotlinx.coroutines.launch

private val USERNAME = Regex("^[a-z0-9][a-z0-9._-]{2,19}$")

/** Şifreli kayıt ve giriş: kullanıcı adı + şifre, e-posta isteğe bağlı. */
@Composable
fun AuthFlow() {
    var page by remember { mutableStateOf("login") }
    when (page) {
        "register" -> RegisterScreen(onBack = { page = "login" })
        "forgot" -> ForgotScreen(onBack = { page = "login" })
        else -> LoginScreen(onRegister = { page = "register" }, onForgot = { page = "forgot" })
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
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun LoginScreen(onRegister: () -> Unit, onForgot: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuthPage {
        Spacer(Modifier.height(70.dp))
        LogoMark(126.dp)
        Spacer(Modifier.height(14.dp))
        Text("Tayfa", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
        Text("Özel ve uçtan uca şifreli sohbet", color = Muted, fontSize = 16.sp)
        Spacer(Modifier.height(40.dp))
        PillField(user, { user = it.lowercase().trim() }, t("Kullanıcı adı"), Icons.Outlined.Person)
        Spacer(Modifier.height(16.dp))
        PillField(pass, { pass = it }, t("Şifre"), Icons.Outlined.Lock, isPassword = true)
        Spacer(Modifier.height(30.dp))
        SandButton(t("Giriş yap"), busy = busy) {
            if (user.isBlank() || pass.isEmpty()) {
                info = t("Kullanıcı adını ve şifreni yaz.")
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
            TextButton(onClick = onRegister) { Text("Hesap oluştur", fontSize = 15.sp) }
            Text("·", color = Muted)
            TextButton(onClick = onForgot) { Text("Şifremi unuttum", fontSize = 15.sp) }
        }
        Spacer(Modifier.height(24.dp))
    }
    info?.let { message -> InfoDialog(message) { info = null } }
}

@Composable
private fun RegisterScreen(onBack: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AuthPage {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
        }
        LogoMark(80.dp)
        Spacer(Modifier.height(10.dp))
        Text("Hesap oluştur", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text("Hemen giriş yaparsın. E-posta isteğe bağlı; şifreni unutursan sıfırlaman için işine yarar.", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(26.dp))
        PillField(user, { user = it.lowercase().trim().take(20) }, t("Kullanıcı adı"), Icons.Outlined.Person)
        Spacer(Modifier.height(14.dp))
        PillField(pass, { pass = it }, t("Şifre (en az 8 karakter)"), Icons.Outlined.Lock, isPassword = true)
        Spacer(Modifier.height(14.dp))
        PillField(pass2, { pass2 = it }, t("Şifre (tekrar)"), Icons.Outlined.Lock, isPassword = true)
        Spacer(Modifier.height(14.dp))
        PillField(email, { email = it.trim() }, t("E-posta (isteğe bağlı)"), Icons.Outlined.AlternateEmail, keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(28.dp))
        SandButton(t("Kayıt ol ve gir"), busy = busy) {
            when {
                !USERNAME.matches(user) ->
                    info = t("Kullanıcı adı 3-20 karakter olmalı; küçük harf, rakam, nokta, tire ve alt çizgi kullanılabilir.")
                pass.length < 8 -> info = t("Şifre en az 8 karakter olmalı.")
                pass != pass2 -> info = t("Şifreler aynı değil.")
                else -> {
                    busy = true
                    scope.launch {
                        try {
                            Api.registerSimple(user, pass, email.ifBlank { null })
                            val error = Bayx.login(user, pass)
                            if (error != null) info = error
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
}

@Composable
private fun ForgotScreen(onBack: () -> Unit) {
    var step by remember { mutableStateOf(1) }
    var login by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AuthPage {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
        }
        LogoMark(80.dp)
        Spacer(Modifier.height(10.dp))
        Text("Şifremi unuttum", color = Ink, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(6.dp))
        Text(
            t("Hesabına doğrulanmış e-posta bağlıysa 6 haneli kod gönderilir. E-postası olmayan hesapların şifresini yönetici sıfırlar."),
            color = Muted,
            fontSize = 14.5.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (step == 1) {
            PillField(login, { login = it.trim() }, t("Kullanıcı adı ya da e-posta"), Icons.Outlined.Person)
            Spacer(Modifier.height(24.dp))
            SandButton(t("Kod gönder"), busy = busy) {
                if (login.isBlank()) {
                    info = t("Kullanıcı adını ya da e-postanı yaz.")
                } else {
                    busy = true
                    scope.launch {
                        try {
                            info = Api.resetRequest(login)
                            step = 2
                        } catch (e: ApiException) {
                            info = e.message
                        }
                        busy = false
                    }
                }
            }
        } else {
            PillField(code, { code = it.filter { c -> c.isDigit() }.take(6) }, "6 haneli kod", Icons.Outlined.Password, keyboardType = KeyboardType.Number)
            Spacer(Modifier.height(14.dp))
            PillField(pass, { pass = it }, t("Yeni şifre (en az 8 karakter)"), Icons.Outlined.Lock, isPassword = true)
            Spacer(Modifier.height(14.dp))
            PillField(pass2, { pass2 = it }, t("Yeni şifre (tekrar)"), Icons.Outlined.Lock, isPassword = true)
            Spacer(Modifier.height(24.dp))
            SandButton(t("Şifreyi değiştir"), busy = busy) {
                when {
                    code.length != 6 -> info = "6 haneli kodu yaz."
                    pass.length < 8 -> info = t("Şifre en az 8 karakter olmalı.")
                    pass != pass2 -> info = t("Şifreler aynı değil.")
                    else -> {
                        busy = true
                        scope.launch {
                            try {
                                Api.resetConfirm(login, code, pass)
                                done = true
                            } catch (e: ApiException) {
                                info = e.message
                            }
                            busy = false
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    info?.let { message -> InfoDialog(message) { info = null } }
    if (done) InfoDialog(t("Şifren değiştirildi. Yeni şifrenle giriş yapabilirsin.")) { onBack() }
}
