package com.bayx.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bayx.chat.data.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Otomatik güncelleme: açılışta ve belirli aralıklarla sunucuya sorar, hazır olunca kurmayı önerir. */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    // "Sonra" denilen sürüm bu açılışta tekrar sorulmaz.
    var skipped by remember { mutableIntStateOf(0) }
    var waitingPermission by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            Updater.check(context)
            delay(3 * 60 * 60 * 1000L)
        }
    }
    // Kurulum izni ayarından dönünce kurulumu kendiliğinden başlat.
    LifecycleResumeEffect(waitingPermission) {
        if (waitingPermission && Updater.canInstall(context)) {
            waitingPermission = false
            Updater.install(context)
        }
        onPauseOrDispose { }
    }

    fun installNow() {
        if (Updater.canInstall(context)) {
            Updater.install(context)
        } else {
            waitingPermission = true
            Updater.openInstallPermission(context)
        }
    }

    when (val s = state) {
        Updater.State.Idle -> Unit
        is Updater.State.Available -> if (skipped != s.info.versionCode) {
            AlertDialog(
                onDismissRequest = { skipped = s.info.versionCode },
                title = { Text(tf("Yeni sürüm var (%s)", s.info.versionName)) },
                text = {
                    Column {
                        if (s.info.notes.isNotBlank()) {
                            Text(s.info.notes, color = Ink, fontSize = 14.sp, lineHeight = 20.sp)
                            Spacer(Modifier.height(10.dp))
                        }
                        Text(
                            tf("Wi-Fi'ye bağlı değilsin. İndirme yaklaşık %s MB veri kullanır. Oturumun ve sohbetlerin yerinde kalır.", (s.info.size / 1_000_000).coerceAtLeast(1)),
                            color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { scope.launch { Updater.download(context, s.info) } }) { Text(t("İndir ve kur")) } },
                dismissButton = { TextButton(onClick = { skipped = s.info.versionCode }) { Text(t("Sonra")) } },
            )
        }
        is Updater.State.Downloading -> if (skipped != s.info.versionCode) {
            AlertDialog(
                onDismissRequest = { skipped = s.info.versionCode },
                title = { Text(t("Güncelleme indiriliyor…")) },
                text = {
                    Column {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("%" + (s.progress * 100).toInt(), color = Muted, fontSize = 13.sp)
                    }
                },
                confirmButton = { TextButton(onClick = { skipped = s.info.versionCode }) { Text(t("Arka planda sürsün")) } },
            )
        }
        is Updater.State.Ready -> if (skipped != s.info.versionCode) {
            AlertDialog(
                onDismissRequest = { skipped = s.info.versionCode },
                title = { Text(tf("Güncelleme hazır (%s)", s.info.versionName)) },
                text = {
                    Column {
                        if (s.info.notes.isNotBlank()) {
                            Text(s.info.notes, color = Ink, fontSize = 14.sp, lineHeight = 20.sp)
                            Spacer(Modifier.height(10.dp))
                        }
                        Text(
                            if (Updater.canInstall(context)) t("Kur'a dokun; oturumun ve sohbetlerin yerinde kalır.")
                            else t("İlk seferde Android bir izin ister: açılan ekranda \"Bu kaynaktan izin ver\"i aç ve geri dön. Oturumun ve sohbetlerin yerinde kalır."),
                            color = Muted, fontSize = 13.sp, lineHeight = 19.sp,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { installNow() }) { Text(t("Kur")) } },
                dismissButton = { TextButton(onClick = { skipped = s.info.versionCode }) { Text(t("Sonra")) } },
            )
        }
        is Updater.State.Failed -> if (skipped != s.info.versionCode) {
            AlertDialog(
                onDismissRequest = { skipped = s.info.versionCode },
                title = { Text(t("Güncelleme indirilemedi")) },
                text = { Text(t(s.message), color = Muted, fontSize = 14.sp) },
                confirmButton = { TextButton(onClick = { scope.launch { Updater.download(context, s.info) } }) { Text(t("Tekrar dene")) } },
                dismissButton = { TextButton(onClick = { skipped = s.info.versionCode }) { Text(t("Sonra")) } },
            )
        }
    }
}
