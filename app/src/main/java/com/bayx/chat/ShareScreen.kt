package com.bayx.chat

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.PhonebookRepo
import com.bayx.chat.data.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Paylaşım hedefi: bir sohbet ya da (henüz sohbet açılmamış) rehberdeki bir Tayfa kişisi. */
private data class Target(val key: String, val name: String, val subtitle: String, val roomId: String?, val userId: String?, val avatarUrl: String?)

private const val MAX_TARGETS = 5

/**
 * "Paylaş → Tayfa" ekranı (WhatsApp gibi): gönderilecekler üstte, altta son sohbetler ve
 * Tayfa kullanan rehber kişileri; en fazla 5 kişi/grup seçilip tek seferde gönderilir.
 */
@Composable
fun ShareScreen(onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val share by Share.pending.collectAsState()
    val rooms by Bayx.rooms.collectAsState()
    val book by PhonebookRepo.entries.collectAsState()
    var query by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Target>() }

    fun cancel() {
        Share.clear(context)
        onDone(null)
    }
    BackHandler(enabled = !sending) { cancel() }

    val current = share
    if (current == null) {
        LaunchedEffect(Unit) { onDone(null) }
        return
    }

    val targets = remember(rooms, book) {
        val chats = rooms.filter { !it.request && !it.secret }.sortedByDescending { it.time }.map {
            Target("r:" + it.id, it.name, if (it.isDirect) t("Sohbet") else t("Grup"), it.id, it.dmUserId, it.avatarUrl)
        }
        val dmUsers = rooms.mapNotNull { it.dmUserId }.toSet()
        val people = book.filter { it.userId !in dmUsers }.map {
            Target("u:" + it.userId, it.name, PhonebookRepo.pretty(it.phone), null, it.userId, it.avatarUrl)
        }
        chats to people
    }
    // Android paylaşım ekranında doğrudan bir sohbet seçildiyse o hazır işaretli gelir.
    LaunchedEffect(current.roomId, targets) {
        val pre = current.roomId ?: return@LaunchedEffect
        if (selected.none { it.roomId == pre }) targets.first.firstOrNull { it.roomId == pre }?.let { selected.add(it) }
    }
    val q = query.trim().lowercase()
    val chats = targets.first.filter { q.isEmpty() || it.name.lowercase().contains(q) }
    val people = targets.second.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.subtitle.filter(Char::isDigit).contains(q.filter(Char::isDigit).ifEmpty { "\u0000" }) }

    fun toggle(target: Target) {
        if (selected.any { it.key == target.key }) {
            selected.removeAll { it.key == target.key }
        } else if (selected.size >= MAX_TARGETS) {
            toast(tf("En fazla %s kişi ya da gruba aynı anda gönderebilirsin.", MAX_TARGETS))
        } else {
            selected.add(target)
        }
    }

    fun send() {
        if (sending || selected.isEmpty()) return
        sending = true
        scope.launch {
            val roomIds = selected.mapNotNull { target ->
                target.roomId ?: target.userId?.let { runCatching { Bayx.startDirectChat(it) }.getOrNull() }
            }
            val failed = if (roomIds.isEmpty()) 1 else Share.send(context, current, roomIds, caption.trim())
            sending = false
            if (failed > 0) toast(tf("%s öğe gönderilemedi. Dosyalar en fazla 50 MB olabilir.", failed)) else toast(t("Gönderildi ✓"))
            Share.clear(context)
            onDone(roomIds.singleOrNull())
        }
    }

    Box(Modifier.fillMaxSize().background(PageBrush)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (!sending) cancel() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Ink) }
                Column(Modifier.weight(1f)) {
                    Text(t("Gönder…"), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (selected.isEmpty()) t("Kişi ya da grup seç") else tf("%s seçildi", "${selected.size}/$MAX_TARGETS"),
                        color = Muted, fontSize = 13.sp,
                    )
                }
            }
            // Gönderilecekler
            if (current.items.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(current.items, key = { it.file.name }) { item -> SharePreview(item) }
                }
                Spacer(Modifier.height(8.dp))
            }
            if (current.text.isNotBlank()) {
                Text(
                    current.text, color = Ink, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(GlassPill).padding(12.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            // Arama
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(GlassPill)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(t("Ad ya da numara ara"), color = Muted, fontSize = 15.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Ink, fontSize = 15.sp), cursorBrush = SolidColor(Blue), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) Icon(Icons.Outlined.Close, t("Temizle"), tint = Muted, modifier = Modifier.size(20.dp).clickable { query = "" })
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (chats.isNotEmpty()) {
                    item { ShareLabel(t("Son sohbetler")) }
                    items(chats, key = { it.key }) { tgt -> TargetRow(tgt, selected.any { it.key == tgt.key }) { toggle(tgt) } }
                }
                if (people.isNotEmpty()) {
                    item { ShareLabel(t("Tayfa'daki kişilerin")) }
                    items(people, key = { it.key }) { tgt -> TargetRow(tgt, selected.any { it.key == tgt.key }) { toggle(tgt) } }
                }
                if (chats.isEmpty() && people.isEmpty()) {
                    item { Text(t("Sonuç bulunamadı"), color = Muted, fontSize = 14.sp, modifier = Modifier.padding(24.dp)) }
                }
            }
            // Alt çubuk: seçilenler + açıklama + gönder
            if (selected.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(CardBg).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        selected.joinToString(", ") { it.name }, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(FieldBg).padding(horizontal = 14.dp, vertical = 11.dp)) {
                            if (caption.isEmpty()) Text(t("Açıklama ekle…"), color = Muted, fontSize = 15.sp)
                            BasicTextField(caption, { caption = it.take(1000) }, maxLines = 4, textStyle = TextStyle(color = Ink, fontSize = 15.sp), cursorBrush = SolidColor(Blue), modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier.size(52.dp).clip(CircleShape).background(SandBrush).clickable(enabled = !sending) { send() },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sending) CircularProgressIndicator(color = SandInk, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                            else Icon(Icons.AutoMirrored.Filled.Send, t("Gönder"), tint = SandInk)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareLabel(text: String) {
    Text(text, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun TargetRow(target: Target, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Avatar(target.name, 48.dp, url = target.avatarUrl)
            if (checked) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(Blue),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(target.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(target.subtitle, color = Muted, fontSize = 13.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SharePreview(item: Share.Item) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, item.file) {
        if (item.mime.startsWith("image/")) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                    BitmapFactory.decodeFile(item.file.path, opts)
                }.getOrNull()
            }
        }
    }
    Box(
        Modifier.size(84.dp).clip(RoundedCornerShape(14.dp)).background(GlassPill),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Icon(
                    when {
                        item.mime.startsWith("video/") -> Icons.Outlined.Movie
                        item.mime.startsWith("audio/") -> Icons.Outlined.AudioFile
                        else -> Icons.Outlined.Description
                    },
                    null, tint = Blue, modifier = Modifier.size(28.dp),
                )
                Text(item.name, color = Ink, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
