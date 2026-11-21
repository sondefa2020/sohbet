package com.bayx.chat.data

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Uygulamanın kendini güncellemesi: sürüm bilgisi ve APK Tayfa sunucusundan alınır.
 * Dosya indirilince bütünlüğü (SHA-256) doğrulanır, sonra Android'in kurulum penceresi açılır.
 * Aynı imzayla kurulduğu için oturum, sohbetler ve ayarlar yerinde kalır.
 */
object Updater {
    sealed interface State {
        data object Idle : State
        /** Yeni sürüm var; indirme için kullanıcı onayı bekleniyor (ör. mobil veri). */
        data class Available(val info: Api.AppLatest) : State
        data class Downloading(val info: Api.AppLatest, val progress: Float) : State
        /** İndirildi ve doğrulandı; kurulmaya hazır. */
        data class Ready(val info: Api.AppLatest) : State
        data class Failed(val info: Api.AppLatest, val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun dir(context: Context) = File(context.filesDir, "guncelleme").apply { mkdirs() }
    private fun apk(context: Context) = File(dir(context), "tayfa.apk")

    fun currentVersion(context: Context): Int = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
    }.getOrDefault(0)

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun unmetered(context: Context): Boolean = runCatching {
        !(context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true)
    }.getOrDefault(false)

    /**
     * Uygulama Google Play'den kurulduysa (ya da Play sürümüyse) kendini güncellemez; güncellemeleri
     * Play yapar. Play politikası mağaza dışından güncellemeye izin vermez.
     */
    fun managedByStore(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
        val canInstall = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES) == true
        installer == "com.android.vending" || !canInstall
    }.getOrDefault(false)

    /** Sunucuya yeni sürüm var mı diye sorar. Wi-Fi'deyse kendiliğinden indirir. */
    suspend fun check(context: Context) = withContext(Dispatchers.IO) {
        if (managedByStore(context)) return@withContext
        val current = state.value
        if (current is State.Downloading) return@withContext
        val info = runCatching { Api.appLatest() }.getOrNull()
        if (info == null || info.versionCode <= currentVersion(context)) {
            // Güncel: eski indirmeyi temizle.
            runCatching { apk(context).delete() }
            if (current !is State.Idle) state.value = State.Idle
            return@withContext
        }
        val file = apk(context)
        if (file.exists() && runCatching { sha256(file) }.getOrNull() == info.sha256) {
            state.value = State.Ready(info)
            return@withContext
        }
        if (unmetered(context)) download(context, info) else state.value = State.Available(info)
    }

    /** APK'yı indirir ve doğrular. */
    suspend fun download(context: Context, info: Api.AppLatest) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = apk(context)
            val part = File(dir(context), "tayfa.apk.part")
            state.value = State.Downloading(info, 0f)
            try {
                http.newCall(Request.Builder().url(info.url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    val body = r.body ?: throw IllegalStateException("boş yanıt")
                    val total = body.contentLength().takeIf { it > 0 } ?: info.size
                    var done = 0L
                    var shown = 0L
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0 && done - shown > total / 100) {
                                    shown = done
                                    state.value = State.Downloading(info, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
                if (sha256(part) != info.sha256) throw IllegalStateException("dosya doğrulanamadı")
                file.delete()
                if (!part.renameTo(file)) throw IllegalStateException("dosya kaydedilemedi")
                state.value = State.Ready(info)
            } catch (e: Exception) {
                runCatching { part.delete() }
                state.value = State.Failed(info, "Güncelleme indirilemedi. İnternet bağlantını kontrol edip tekrar dene.")
            }
        }
    }

    /** Bu uygulamanın APK kurmasına izin verilmiş mi (Android 8+ için bir kerelik ayar). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** "Bu kaynaktan yüklemeye izin ver" ayarını açar. */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Android'in kurulum penceresini açar. Başarılıysa true. */
    fun install(context: Context): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".dosya", apk(context))
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
