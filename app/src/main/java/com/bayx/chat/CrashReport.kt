package com.bayx.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Uygulama beklenmedik şekilde kapanırsa hatanın teknik özetini bu telefonda saklar; bir sonraki
 * açılışta kullanıcıya sorup isterse paylaştırır. Mesaj içeriği yoktur, yalnızca hata yığını.
 */
object CrashReport {
    private const val PREFS = "tayfa_hata"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = android.util.Log.getStackTraceString(error).lines().take(60).joinToString("\n")
                val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
                val info = "Tayfa $version · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "İş parçacığı: ${thread.name}\n\n" + trace
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("son", info).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun take(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = prefs.getString("son", null) ?: return null
        prefs.edit().remove("son").apply()
        return text
    }
}

@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashReport.take(context)) }
    val text = report ?: return
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text(t("Uygulama beklenmedik şekilde kapandı")) },
        text = {
            androidx.compose.foundation.layout.Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(t("Bu hatayı düzeltebilmemiz için teknik ayrıntıyı paylaşabilirsin. Mesajların ya da kişilerin bu rapora girmez."), color = Muted, fontSize = 14.sp)
                Text(text, color = Muted, fontSize = 10.sp, modifier = Modifier.heightIn(min = 0.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                            t("Hata raporunu paylaş"),
                        ),
                    )
                }
                report = null
            }) { Text(t("Paylaş")) }
        },
        dismissButton = { TextButton(onClick = { report = null }) { Text(t("Kapat")) } },
    )
}
