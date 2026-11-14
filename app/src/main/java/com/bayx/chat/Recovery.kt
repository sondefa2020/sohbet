package com.bayx.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Bayx
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.RecoveryState

/** Mesaj yedeği pencereleri: yeni anahtarı bir kez göster, yeni kurulumda anahtarı iste. */
@Composable
fun RecoveryDialogs() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val newKey by Bayx.newRecoveryKey.collectAsState()
    val state by Bayx.recoveryState.collectAsState()
    val prompt by Bayx.recoveryPrompt.collectAsState()
    val prefs = remember { context.getSharedPreferences("tayfa_yedek", Context.MODE_PRIVATE) }
    // t("Şimdi değil") bir kez seçilince kalıcı olarak hatırlanır; sonra yalnızca Ayarlar'dan açılır.
    var dismissed by remember { mutableStateOf(prefs.getBoolean("sorma", false)) }

    // 1) Yeni anahtar: kullanıcı kaydettiğini onaylayana kadar kapanmaz.
    newKey?.let { key ->
        var copied by remember(key) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(t("Mesaj yedek anahtarın")) },
            text = {
                Column {
                    Text(
                        t("Telefonunu değiştirirsen ya da uygulamayı silip yeniden kurarsan eski mesajlarını bu anahtarla geri getirirsin. Bir kağıda yaz ya da güvendiğin bir yere kaydet. Bir daha gösterilmez."),
                        color = Muted, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    SelectionContainer {
                        Text(
                            key,
                            color = Ink,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth()
                                .background(ChipBg, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Tayfa", key))
                        copied = true
                    }) { Text(if (copied) t("Kopyalandı") else t("Kopyala")) }
                }
            },
            confirmButton = {
                TextButton(onClick = { Bayx.newRecoveryKey.value = null }) { Text(t("Kaydettim")) }
            },
        )
        return
    }

    // 2) Yeni kurulum: hesapta yedek var ama bu cihaz henüz geri yüklenmedi.
    val ask = prompt || (state == RecoveryState.INCOMPLETE && !dismissed)
    if (ask) {
        var key by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var noKey by remember { mutableStateOf(false) }
        fun close() {
            dismissed = true
            prefs.edit().putBoolean("sorma", true).apply()
            Bayx.recoveryPrompt.value = false
        }
        if (noKey) {
            AlertDialog(
                onDismissRequest = { if (!busy) noKey = false },
                title = { Text(t("Yeni yedek başlatılsın mı?")) },
                text = {
                    Text(
                        t("Eski şifreli mesajlar bu cihazda açılamayacak. Bundan sonraki mesajların yeni bir anahtarla yedeklenir ve bu uyarı bir daha çıkmaz."),
                        color = Muted, fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val err = Bayx.createRecoveryKey()
                            busy = false
                            if (err == null) { noKey = false; close() } else { error = t(err); noKey = false }
                        }
                    }) { Text(if (busy) t("Bekle…") else t("Yeni yedek başlat")) }
                },
                dismissButton = { TextButton(enabled = !busy, onClick = { noKey = false }) { Text(t("Vazgeç")) } },
            )
            return
        }
        AlertDialog(
            onDismissRequest = { if (!busy) close() },
            title = { Text(t("Eski mesajlarını geri getir")) },
            text = {
                Column {
                    Text(
                        t("İlk kurulumda sana gösterilen mesaj yedek anahtarını yaz. Anahtarın yoksa eski şifreli mesajlar bu cihazda açılamaz; yeni mesajlar normal çalışır."),
                        color = Muted, fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        placeholder = { Text("EsTd 1234 …") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = androidx.compose.ui.graphics.Color(0xFFB4232A), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(enabled = !busy, onClick = { noKey = true }) {
                        Text(t("Anahtarım yok, yeni yedek başlat"), color = Blue)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && key.isNotBlank(), onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val err = Bayx.recoverWithKey(key)
                        busy = false
                        if (err == null) close() else error = t(err)
                    }
                }) { Text(if (busy) t("Bekle…") else t("Geri getir")) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { close() }) { Text(t("Şimdi değil")) }
            },
        )
    }
}

/** Ayarlardaki t("Mesaj yedeği") satırının durum yazısı. */
fun recoveryLabel(state: RecoveryState): String = when (state) {
    RecoveryState.ENABLED -> t("Açık")
    RecoveryState.INCOMPLETE -> t("Anahtar bekleniyor")
    RecoveryState.DISABLED -> t("Kapalı")
    RecoveryState.UNKNOWN -> "…"
}
