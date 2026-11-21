package com.bayx.chat.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Başka uygulamalardan (Galeri, Dosyalar, tarayıcı…) "Paylaş → Tayfa" ile gelen içerik.
 * Gelen dosyalar hemen uygulamanın kendi önbelleğine kopyalanır; böylece paylaşan uygulamanın
 * verdiği geçici izin bitse de gönderim yarım kalmaz.
 */
object Share {
    data class Item(val file: File, val mime: String, val name: String)

    data class Pending(
        val items: List<Item>,
        val text: String,
        /** Android paylaşım ekranında doğrudan bir kişi/sohbet seçildiyse onun oda kimliği. */
        val roomId: String?,
    )

    val pending = MutableStateFlow<Pending?>(null)

    const val CATEGORY = "com.bayx.chat.PAYLASIM"
    private const val MAX_ITEMS = 10

    private fun dir(context: Context) = File(context.cacheDir, "paylasim").apply { mkdirs() }

    fun isShare(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE

    /** Paylaşım niyetini okur, dosyaları kopyalar ve [pending]'e koyar. */
    suspend fun accept(context: Context, intent: Intent) = withContext(Dispatchers.IO) {
        val uris = buildList {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { addAll(it) }
            } else {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { add(it) }
            }
        }.take(MAX_ITEMS)
        val text = listOfNotNull(
            intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { uris.isEmpty() },
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
        ).joinToString("\n").trim()
        // Önceki paylaşımın dosyalarını temizle.
        dir(context).listFiles()?.forEach { it.delete() }
        val items = uris.mapIndexedNotNull { i, uri -> copy(context, uri, i, intent.type) }
        val room = intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID)
        if (items.isEmpty() && text.isBlank()) return@withContext
        pending.value = Pending(items, text, room)
    }

    private fun copy(context: Context, uri: Uri, index: Int, fallbackMime: String?): Item? = runCatching {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: fallbackMime?.takeIf { !it.contains('*') } ?: "application/octet-stream"
        var name = "dosya$index"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val out = File(dir(context), "$index-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60))
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
        Item(out, mime, name)
    }.getOrNull()

    fun clear(context: Context) {
        pending.value = null
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    /**
     * Seçilen sohbetlere gönderir. Her sohbet için başarısız öğe sayısını toplar.
     * Fotoğraf → fotoğraf, video → video, ses → müzik, diğerleri → belge olarak gider.
     */
    suspend fun send(context: Context, share: Pending, roomIds: List<String>, caption: String): Int {
        var failed = 0
        for (roomId in roomIds) {
            val session = runCatching { Bayx.openChat(roomId) }.getOrNull()
            if (session == null) {
                failed += share.items.size + if (caption.isNotBlank() || share.text.isNotBlank()) 1 else 0
                continue
            }
            try {
                for (item in share.items) {
                    val uri = Uri.fromFile(item.file)
                    val ok = runCatching {
                        when {
                            item.mime.startsWith("image/") && !item.mime.contains("gif") -> {
                                val image = withContext(Dispatchers.IO) { MediaTools.prepareImage(context, uri) }
                                image != null && Bayx.sendImage(session, image.bytes, image.width, image.height)
                            }
                            item.mime.startsWith("video/") -> {
                                val v = withContext(Dispatchers.IO) { MediaTools.prepareVideo(context, uri) }
                                v != null && Bayx.sendVideo(session, v.bytes, v.mime, v.width, v.height, v.durationMs, v.thumbnail, v.thumbWidth, v.thumbHeight)
                            }
                            item.mime.startsWith("audio/") -> {
                                val a = withContext(Dispatchers.IO) { MediaTools.prepareAudio(context, uri) }
                                a != null && Bayx.sendAudioFile(session, a.bytes, a.mime, a.name.ifBlank { item.name }, a.durationMs)
                            }
                            else -> {
                                val bytes = withContext(Dispatchers.IO) { item.file.takeIf { it.length() <= 50L * 1024 * 1024 }?.readBytes() }
                                bytes != null && Bayx.sendDocument(session, bytes, item.mime, item.name)
                            }
                        }
                    }.getOrDefault(false)
                    if (!ok) failed++
                }
                val message = listOf(share.text, caption).filter { it.isNotBlank() }.joinToString("\n")
                if (message.isNotBlank() && !Bayx.sendText(session, message)) failed++
            } finally {
                runCatching { Bayx.closeChat(session) }
            }
        }
        return failed
    }

    /**
     * Android paylaşım ekranının üst sırasındaki kişiler (Doğrudan Paylaşım): son konuştuğun
     * sohbetler kısayol olarak yayınlanır; birine dokununca paylaşım doğrudan o sohbete gelir.
     */
    fun publishTargets(context: Context, chats: List<ChatSummary>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(8)
            val list = chats.filter { !it.secret && !it.request }.sortedByDescending { it.time }.take(max)
            val shortcuts = list.map { chat ->
                ShortcutInfoCompat.Builder(context, chat.id)
                    .setShortLabel(chat.name.ifBlank { "Tayfa" }.take(24))
                    .setLongLabel(chat.name.ifBlank { "Tayfa" })
                    .setIcon(IconCompat.createWithAdaptiveBitmap(letterIcon(chat.name)))
                    .setIntent(
                        Intent(context, com.bayx.chat.MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW)
                            .putExtra(Notifier.EXTRA_ROOM_ID, chat.id),
                    )
                    .setLongLived(true)
                    .setCategories(setOf(CATEGORY))
                    .setIsConversation()
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    private fun letterIcon(name: String): Bitmap {
        val size = 108
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val palette = intArrayOf(0xFFB07A4E.toInt(), 0xFF7C5CBF.toInt(), 0xFF2E8B6E.toInt(), 0xFFC0504D.toInt(), 0xFF3A78B5.toInt())
        canvas.drawColor(palette[(name.hashCode() and 0x7fffffff) % palette.size])
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val letter = name.trim().firstOrNull()?.uppercase() ?: "T"
        canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
        return bmp
    }
}
