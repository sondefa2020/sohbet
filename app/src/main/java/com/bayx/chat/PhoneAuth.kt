package com.bayx.chat

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Api
import com.bayx.chat.data.ApiException
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.PhonebookRepo
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Yeni hesap açıldıysa ilk girişte ad sorulur (WhatsApp'taki "Profil bilgisi" adımı). */
object NamePrompt {
    private const val PREFS = "tayfa_isim"
    fun request(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("sor", true).apply()
    fun pending(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("sor", false)
    fun done(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("sor", false).apply()
}

/**
 * Telefon numarasıyla giriş/kayıt (WhatsApp mantığı): numara → SMS kodu → içeride.
 * Şifre yoktur; numara kayıtlıysa aynı hesaba girilir, değilse yeni hesap açılır.
 */
@Composable
fun PhoneAuthScreen(onUsernameLogin: () -> Unit = {}, linkToken: String? = null, onLinked: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf("") }
    var region by remember { mutableStateOf(PhonebookRepo.region(context)) }
    val check = remember(region, number) { Phones.check(region, number) }
    var phone by remember { mutableStateOf<String?>(null) }
    var masked by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var resendIn by remember { mutableIntStateOf(0) }
    var smsRound by remember { mutableIntStateOf(0) }
    var hintAsked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(resendIn) {
        if (resendIn > 0) {
            delay(1000)
            resendIn -= 1
        }
    }

    fun sendCode(target: String) {
        busy = true
        scope.launch {
            try {
                val r = Api.phoneStart(target)
                phone = r.phone
                masked = r.masked
                code = ""
                resendIn = r.resendSeconds
                smsRound += 1
            } catch (e: ApiException) {
                info = t(e.message ?: "SMS gönderilemedi.")
            } finally {
                busy = false
            }
        }
    }

    fun verify() {
        val p = phone ?: return
        if (code.length != 6 || busy) return
        busy = true
        scope.launch {
            try {
                if (linkToken != null) {
                    // Oturum açık: numarayı bu hesaba bağla.
                    Api.phoneVerify(p, code, linkToken)
                    PhonebookRepo.sync(context)
                    onLinked()
                } else {
                    val r = Api.phoneVerify(p, code)
                    if (r.isNew) NamePrompt.request(context)
                    val error = Bayx.login(r.username, r.otp)
                    if (error != null) info = t(error)
                }
            } catch (e: ApiException) {
                info = t(e.message ?: "Doğrulanamadı.")
                code = ""
            } finally {
                busy = false
            }
        }
    }

    // 1) Cihazın numarasını otomatik öner (Google'ın numara seçme penceresi; izin gerekmez).
    val hintLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        runCatching {
            val raw = Identity.getSignInClient(context).getPhoneNumberFromIntent(result.data)
            val parsed = Phones.util.parse(raw, region)
            Phones.util.getRegionCodeForNumber(parsed)?.let { r ->
                region = r
                PhonebookRepo.setRegion(context, r)
            }
            number = Phones.util.getNationalSignificantNumber(parsed)
        }
    }
    LaunchedEffect(Unit) {
        if (hintAsked || number.isNotEmpty()) return@LaunchedEffect
        hintAsked = true
        runCatching {
            Identity.getSignInClient(context)
                .getPhoneNumberHintIntent(GetPhoneNumberHintIntentRequest.builder().build())
                .addOnSuccessListener { pending ->
                    runCatching { hintLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build()) }
                }
        }
    }

    // 2) Doğrulama SMS'i gelince kodu otomatik doldur (tek dokunuşla onay; SMS okuma izni gerekmez).
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val message = result.data?.getStringExtra(SmsRetriever.EXTRA_SMS_MESSAGE).orEmpty()
        Regex("(?<!\\d)(\\d{6})(?!\\d)").find(message)?.let {
            code = it.groupValues[1]
            verify()
        }
    }
    DisposableEffect(smsRound) {
        if (smsRound == 0) return@DisposableEffect onDispose {}
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
                val extras = intent.extras ?: return
                @Suppress("DEPRECATION")
                val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
                if (status.statusCode != CommonStatusCodes.SUCCESS) return
                val consent: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") extras.getParcelable(SmsRetriever.EXTRA_CONSENT_INTENT)
                }
                runCatching { consent?.let { consentLauncher.launch(it) } }
            }
        }
        runCatching { SmsRetriever.getClient(context).startSmsUserConsent(null) }
        runCatching {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                SmsRetriever.SEND_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED,
            )
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    AuthPageBox {
        Spacer(Modifier.height(60.dp))
        LogoMark(110.dp)
        Spacer(Modifier.height(14.dp))
        Text("Tayfa", color = Ink, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        if (phone == null) {
            Text(
                t("Telefon numaranı doğrula. Sana SMS ile 6 haneli bir kod göndereceğiz."),
                color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(32.dp))
            CountryPhoneField(
                region = region,
                onRegion = { region = it; PhonebookRepo.setRegion(context, it) },
                digits = number,
                onDigits = { number = it },
                check = check,
            )
            Spacer(Modifier.height(22.dp))
            SandButton(t("Kod gönder"), busy = busy) {
                when (val c = check) {
                    is Phones.Check.Valid -> sendCode(c.e164)
                    Phones.Check.NotMobile -> info = t("Bu bir cep numarası değil. SMS alabilen bir numara yaz.")
                    else -> info = t("Telefon numarası geçersiz. Ülkeyi ve numarayı kontrol et.")
                }
            }
            Spacer(Modifier.height(12.dp))
            if (linkToken == null) {
                TextButton(onClick = onUsernameLogin) { Text(t("Kullanıcı adıyla giriş yap"), fontSize = 14.sp) }
            } else {
                TextButton(onClick = onLinked) { Text(t("Şimdi değil"), fontSize = 14.sp) }
            }
        } else {
            Text(
                tf("%s numarasına gönderilen 6 haneli kodu yaz.", masked),
                color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(32.dp))
            PillField(code, { v ->
                code = v.filter { it.isDigit() }.take(6)
                if (code.length == 6) verify()
            }, t("Doğrulama kodu"), Icons.Outlined.Pin, keyboardType = KeyboardType.NumberPassword)
            Spacer(Modifier.height(26.dp))
            SandButton(t("Doğrula"), busy = busy) { verify() }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { phone = null; code = "" }) { Text(t("Numarayı değiştir"), fontSize = 14.sp) }
                Text("·", color = Muted)
                TextButton(onClick = { phone?.let { sendCode("+$it") } }, enabled = resendIn == 0 && !busy) {
                    Text(if (resendIn > 0) tf("Kodu tekrar gönder (%s)", resendIn) else t("Kodu tekrar gönder"), fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            t("Numaran yalnızca rehberinde seni kayıtlı tutan kişilerin seni bulması için kullanılır."),
            color = Muted, fontSize = 12.5.sp, textAlign = TextAlign.Center, lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
    info?.let { message -> InfoDialog(message) { info = null } }
}
