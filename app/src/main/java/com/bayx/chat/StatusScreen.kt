package com.bayx.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.bayx.chat.data.Api
import com.bayx.chat.data.MediaTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.StatusRepo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import com.bayx.chat.data.Bayx


// ====================================================================== görünüm

/** Arka planlar: geçişli (gradyan) renkler. Kayıt biçimi: "g<numara>:f<yazı stili>". */
private val StatusGradients = listOf(
    listOf(0xFF0F9B8E, 0xFF3BD18F), listOf(0xFF6D4FC2, 0xFFB06AB3), listOf(0xFFFF7E5F, 0xFFFEB47B),
    listOf(0xFF2563A8, 0xFF5EC8F2), listOf(0xFFB4232A, 0xFFF0544F), listOf(0xFF141E30, 0xFF243B55),
    listOf(0xFFC2185B, 0xFFFF6CAB), listOf(0xFF11998E, 0xFF38EF7D), listOf(0xFF8E2DE2, 0xFF4A00E0),
    listOf(0xFFF7971E, 0xFFFFD200), listOf(0xFF373B44, 0xFF4286F4), listOf(0xFF834D9B, 0xFFD04ED6),
).map { pair -> pair.map { Color(it) } }

private val StatusFonts = listOf(FontFamily.Default, FontFamily.Serif, FontFamily.Monospace, FontFamily.Cursive)

private data class StatusStyle(val brush: Brush, val font: FontFamily)

private fun styleOf(bg: String): StatusStyle {
    Regex("""^g(\d+):f(\d+)$""").find(bg)?.let { m ->
        val g = StatusGradients[m.groupValues[1].toInt().coerceIn(0, StatusGradients.lastIndex)]
        val f = StatusFonts[m.groupValues[2].toInt().coerceIn(0, StatusFonts.lastIndex)]
        return StatusStyle(Brush.linearGradient(g), f)
    }
    val c = runCatching { Color(android.graphics.Color.parseColor(bg)) }.getOrDefault(Color(0xFF14110F))
    return StatusStyle(Brush.linearGradient(listOf(c, c)), FontFamily.Default)
}

/** Sessize alınan kişilerin durumları ayrı bölümde, halkasız durur (cihazda tutulur). */
private object StatusMute {
    fun all(context: android.content.Context): Set<String> =
        context.getSharedPreferences("tayfa_durum_sessiz", android.content.Context.MODE_PRIVATE).getStringSet("k", emptySet()).orEmpty()

    fun toggle(context: android.content.Context, userId: String) {
        val p = context.getSharedPreferences("tayfa_durum_sessiz", android.content.Context.MODE_PRIVATE)
        val cur = p.getStringSet("k", emptySet()).orEmpty()
        p.edit().putStringSet("k", if (userId in cur) cur - userId else cur + userId).apply()
    }
}

// ====================================================================== liste

@Composable
fun StatusScreen(onBack: () -> Unit) {
    val vm = viewModel { com.bayx.chat.ui.StatusViewModel() }
    val data by vm.statuses.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toast = rememberToast()
    var composing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<List<Api.StatusUser>, Int>?>(null) }
    var photoDraft by remember { mutableStateOf<ByteArray?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var muteTick by remember { mutableStateOf(0) }
    val sending by vm.uploading.collectAsStateWithLifecycle()
    val uploading = preparing || sending
    LaunchedEffect(vm) { vm.messages.collect { toast(t(it)) } }
    LaunchedEffect(Unit) { while (true) { vm.refresh(); delay(60_000) } }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !uploading) {
            preparing = true
            scope.launch {
                val image = withContext(Dispatchers.IO) { MediaTools.prepareImage(context, uri) }
                preparing = false
                if (image == null) toast(t("Fotoğraf yüklenemedi.")) else photoDraft = image.bytes
            }
        }
    }
    fun addPhoto() = pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    val mine = data?.mine
    val muted = remember(muteTick, data) { StatusMute.all(context) }
    val friends = data?.friends.orEmpty()
    val fresh = friends.filter { !it.allSeen && it.userId !in muted }
    val seen = friends.filter { it.allSeen && it.userId !in muted }
    val mutedList = friends.filter { it.userId in muted }
    val order = fresh + seen + mutedList

    Box(Modifier.fillMaxSize().background(PageBrush)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Ink) }
                Text(t("Durum"), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (uploading) CircularProgressIndicator(color = Blue, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
            val statusError by vm.error.collectAsStateWithLifecycle()
            statusError?.let { err ->
                Text(
                    t(err), color = Color(0xFFB4232A), fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
                        .background(Color(0x1AB4232A), RoundedCornerShape(12.dp)).padding(12.dp),
                )
            }

            // Hikâye kartları (yatay): önce benim durumum, sonra yeni güncellemeler.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StoryCard(
                    title = if (mine != null) t("Durumum") else t("Durum ekle"),
                    item = mine?.items?.lastOrNull(),
                    name = t("Ben"),
                    ring = false,
                    plus = true,
                ) { if (mine != null) viewing = listOf(mine) to 0 else composing = true }
                order.forEachIndexed { i, u ->
                    StoryCard(u.name, u.items.lastOrNull(), u.name, ring = !u.allSeen && u.userId !in muted, plus = false) {
                        viewing = order to i
                    }
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (fresh.isNotEmpty()) {
                    item { StatusSection(t("Son güncellemeler")) }
                    items(fresh, key = { "y" + it.userId }) { u -> StatusRow(u, ring = true) { viewing = order to order.indexOf(u) } }
                }
                if (seen.isNotEmpty()) {
                    item { StatusSection(t("Görülen güncellemeler")) }
                    items(seen, key = { "g" + it.userId }) { u -> StatusRow(u, ring = false) { viewing = order to order.indexOf(u) } }
                }
                if (mutedList.isNotEmpty()) {
                    item { StatusSection(t("Sessize alınanlar")) }
                    items(mutedList, key = { "s" + it.userId }) { u -> StatusRow(u, ring = false, dim = true) { viewing = order to order.indexOf(u) } }
                }
                if (friends.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(t("Henüz güncelleme yok"), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                t("Rehberindeki kişilerin durumları burada 24 saat görünür. İlk durumu sen paylaş!"),
                                color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(140.dp)) }
            }
        }
        // Yazı ve kamera düğmeleri (WhatsApp gibi üst üste)
        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(16.dp)).background(CardBg).border(1.dp, Line, RoundedCornerShape(16.dp))
                    .clickable { composing = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Edit, t("Yazı durumu"), tint = Ink) }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(BlueBrush).clickable { addPhoto() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.PhotoCamera, t("Fotoğraf durumu"), tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
    }

    if (composing) {
        StatusComposer(onDismiss = { composing = false }) { text, bg ->
            composing = false
            vm.addText(text, bg)
        }
    }
    photoDraft?.let { bytes ->
        PhotoStatusComposer(bytes, onDismiss = { photoDraft = null }) { caption ->
            photoDraft = null
            vm.addImage(bytes, "Fotoğraf yüklenemedi.", caption)
        }
    }
    viewing?.let { (list, start) ->
        StatusViewer(
            users = list,
            start = start,
            myId = mine?.userId,
            mutedIds = muted,
            onClose = { viewing = null; vm.refresh() },
            onSeen = { owner, item -> if (owner != mine?.userId) vm.markSeen(owner, item.id) },
            onDelete = { item -> vm.delete(item.id); viewing = null },
            onToggleMute = { uid -> StatusMute.toggle(context, uid); muteTick++ },
        )
    }
}

@Composable
private fun StatusSection(text: String) {
    Text(text, color = SoftBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 22.dp, top = 12.dp, bottom = 4.dp))
}

/** Gradyan halka: görülmemişse canlı renkler, görülmüşse soluk. */
@Composable
private fun StatusRing(fresh: Boolean, count: Int, size: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    val brush = if (fresh) Brush.sweepGradient(listOf(Color(0xFF0F9B8E), Color(0xFF3BD18F), Color(0xFFFEB47B), Color(0xFF0F9B8E)))
    else Brush.linearGradient(listOf(Color(0xFFCFC6BA), Color(0xFFCFC6BA)))
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.6.dp.toPx()
            val n = count.coerceIn(1, 12)
            val gap = if (n > 1) 8f else 0f
            val sweep = 360f / n
            for (i in 0 until n) {
                drawArc(
                    brush = brush, startAngle = -90f + i * sweep + gap / 2, sweepAngle = sweep - gap, useCenter = false,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                )
            }
        }
        Box(Modifier.padding(5.dp)) { content() }
    }
}

@Composable
private fun StatusRow(u: Api.StatusUser, ring: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusRing(fresh = ring, count = u.items.size, size = 62.dp) { Avatar(u.name, 52.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(u.name, color = if (dim) Muted else Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatTime(u.lastTime) + " · " + tf("%s güncelleme", u.items.size), color = SoftBlue, fontSize = 13.sp)
        }
    }
}

/** Yatay hikâye kartı: son durumun küçük önizlemesi + kişi fotoğrafı. */
@Composable
private fun StoryCard(title: String, item: Api.StatusItem?, name: String, ring: Boolean, plus: Boolean, onClick: () -> Unit) {
    val style = item?.let { styleOf(it.bg) }
    val photo by produceState<android.graphics.Bitmap?>(null, item?.id) {
        value = if (item != null && item.type == "image" && item.media.isNotBlank())
            StatusRepo.loadStatusImage(item.media, item.key)?.let { MediaTools.decodeSampled(it, 400) } else null
    }
    Box(
        Modifier.width(96.dp).height(150.dp).clip(RoundedCornerShape(20.dp))
            .background(style?.brush ?: Brush.linearGradient(listOf(CardBg, CardBg)))
            .border(if (ring) 2.dp else 1.dp, if (ring) Color(0xFF3BD18F) else Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        photo?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (item != null && item.type == "text") {
            Text(
                item.text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = style?.font,
                maxLines = 4, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
        Box(Modifier.padding(8.dp).align(Alignment.TopStart)) {
            if (plus) {
                Box(Modifier.size(34.dp)) {
                    Avatar(name, 34.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(18.dp).clip(CircleShape).background(Color(0xFF2E8B6E)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
                }
            } else {
                Box(Modifier.size(38.dp).clip(CircleShape).border(2.dp, if (ring) Color(0xFF3BD18F) else Color.White.copy(alpha = 0.6f), CircleShape).padding(2.dp)) {
                    Avatar(name, 32.dp)
                }
            }
        }
        Text(
            title, color = if (item == null && !plus) Ink else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )
    }
}

// ====================================================================== yazı durumu

@Composable
private fun StatusComposer(onDismiss: () -> Unit, onShare: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var grad by remember { mutableStateOf((0..StatusGradients.lastIndex).random()) }
    var font by remember { mutableStateOf(0) }
    val size = when {
        text.length < 40 -> 34.sp
        text.length < 120 -> 26.sp
        text.length < 300 -> 20.sp
        else -> 16.sp
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(StatusGradients[grad]))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { font = (font + 1) % StatusFonts.size }) { Icon(Icons.Outlined.TextFields, t("Yazı stili"), tint = Color.White) }
                    IconButton(onClick = { grad = (grad + 1) % StatusGradients.size }) { Icon(Icons.Outlined.Palette, t("Renk"), tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= 700) text = it },
                        textStyle = TextStyle(color = Color.White, fontSize = size, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, fontFamily = StatusFonts[font], lineHeight = size * 1.25f),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth().padding(28.dp),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.Center) {
                                if (text.isEmpty()) {
                                    Text(t("Bir durum yaz"), color = Color.White.copy(alpha = 0.65f), fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = StatusFonts[font], textAlign = TextAlign.Center)
                                }
                                inner()
                            }
                        },
                    )
                }
                // Renk paleti
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusGradients.forEachIndexed { i, g ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Brush.linearGradient(g))
                                .border(if (i == grad) 3.dp else 1.dp, Color.White.copy(alpha = if (i == grad) 1f else 0.5f), CircleShape)
                                .clickable { grad = i },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("24 saat sonra kaybolur · uçtan uca kişilerine"), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(54.dp).clip(CircleShape).background(Color.White.copy(alpha = if (text.isBlank()) 0.35f else 1f))
                            .clickable(enabled = text.isNotBlank()) { onShare(text.trim(), "g$grad:f$font") },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, t("Paylaş"), tint = StatusGradients[grad][0]) }
                }
            }
        }
    }
}

// ====================================================================== fotoğraf durumu

@Composable
private fun PhotoStatusComposer(bytes: ByteArray, onDismiss: () -> Unit, onShare: (String) -> Unit) {
    var caption by remember { mutableStateOf("") }
    val bmp = remember(bytes) { MediaTools.decodeSampled(bytes, 1600) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
                IconButton(onClick = onDismiss, modifier = Modifier.padding(8.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Color.White) }
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = caption,
                        onValueChange = { if (it.length <= 300) caption = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 16.dp, vertical = 12.dp),
                        decorationBox = { inner ->
                            if (caption.isEmpty()) Text(t("Açıklama ekle…"), color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                            inner()
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(BlueBrush).clickable { onShare(caption.trim()) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Filled.Send, t("Paylaş"), tint = Color.White) }
                }
            }
        }
    }
}

// ====================================================================== izleyici

private val QuickReactions = listOf("❤️", "😂", "😮", "😢", "🙏", "👏", "🔥")

@Composable
private fun StatusViewer(
    users: List<Api.StatusUser>,
    start: Int,
    myId: String?,
    mutedIds: Set<String>,
    onClose: () -> Unit,
    onSeen: (String, Api.StatusItem) -> Unit,
    onDelete: (Api.StatusItem) -> Unit,
    onToggleMute: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var userIndex by remember { mutableStateOf(start.coerceIn(0, (users.size - 1).coerceAtLeast(0))) }
    val user = users.getOrNull(userIndex) ?: run { onClose(); return }
    val mine = user.userId == myId
    // Kişinin ilk görülmemiş durumundan başla (WhatsApp gibi)
    var index by remember(user.userId) { mutableStateOf(user.items.indexOfFirst { !it.seen }.takeIf { it >= 0 && !mine } ?: 0) }
    val item = user.items.getOrNull(index) ?: run { onClose(); return }
    var paused by remember { mutableStateOf(false) }
    var showViewers by remember { mutableStateOf(false) }
    var replying by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var dragY by remember { mutableStateOf(0f) }
    val progress = remember(user.userId, index) { Animatable(0f) }

    fun next() {
        if (index < user.items.size - 1) index++
        else if (userIndex < users.size - 1) userIndex++
        else onClose()
    }
    fun prev() {
        if (index > 0) index--
        else if (userIndex > 0) userIndex--
    }

    val photo by produceState<android.graphics.Bitmap?>(initialValue = null, item.id) {
        value = if (item.type == "image" && item.media.isNotBlank()) {
            StatusRepo.loadStatusImage(item.media, item.key)?.let { MediaTools.decodeSampled(it, 1600) }
        } else null
    }
    val ready = item.type != "image" || photo != null
    // Süre: yazıda uzunluğa göre, fotoğrafta 6 sn. Basılı tutunca / yanıt yazarken durur.
    val duration = if (item.type == "image") 6000 else (3500 + item.text.length * 45).coerceAtMost(9000)
    LaunchedEffect(user.userId, index, paused, replying, showViewers, ready) {
        onSeen(user.userId, item)
        if (paused || replying || showViewers || !ready) { progress.stop(); return@LaunchedEffect }
        val left = ((1f - progress.value) * duration).toInt().coerceAtLeast(1)
        progress.animateTo(1f, tween(left, easing = LinearEasing))
        next()
    }
    // Fotoğrafta yavaş yakınlaşma (Ken Burns)
    val zoom = remember(item.id) { Animatable(1f) }
    LaunchedEffect(item.id, ready) { if (ready && item.type == "image") zoom.animateTo(1.08f, tween(duration, easing = LinearEasing)) }

    if (showViewers) {
        AlertDialog(
            onDismissRequest = { showViewers = false },
            title = { Text(tf("Görenler (%s)", item.viewers.size)) },
            text = {
                if (item.viewers.isEmpty()) Text(t("Henüz kimse görmedi."), color = Muted)
                else Column {
                    item.viewers.forEach { (_, name) ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name, 34.dp); Spacer(Modifier.width(10.dp)); Text(name, color = Ink, fontSize = 15.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showViewers = false }) { Text(t("Kapat")) } },
        )
    }

    fun sendToOwner(text: String, done: String) {
        scope.launch {
            val ok = Bayx.sendTextToUser(user.userId, text)
            toast(if (ok) done else t("Gönderilemedi. Tekrar dene."))
        }
    }
    val quote = if (item.type == "text") item.text.take(60) else (item.text.ifBlank { t("Fotoğraf") }).take(60)

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val style = styleOf(item.bg)
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { translationY = dragY.coerceAtLeast(0f); alpha = 1f - (dragY / 1600f).coerceIn(0f, 0.6f) }
                .background(if (item.type == "image") Brush.linearGradient(listOf(Color.Black, Color.Black)) else style.brush)
                .pointerInput(user.userId, index) {
                    // Aşağı kaydır: kapat
                    detectVerticalDragGestures(
                        onDragEnd = { if (dragY > 260f) onClose() else dragY = 0f },
                        onDragCancel = { dragY = 0f },
                    ) { _, amount -> dragY += amount }
                }
                .pointerInput(user.userId, index) {
                    detectTapGestures(
                        onPress = { paused = true; tryAwaitRelease(); paused = false },
                        onTap = { pos -> if (pos.x < size.width * 0.3f) prev() else next() },
                    )
                },
        ) {
            photo?.let { bmp ->
                Image(
                    bmp.asImageBitmap(), null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
                )
            }
            if (!ready) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            if (item.type == "text") {
                Text(
                    item.text, color = Color.White, fontFamily = style.font, textAlign = TextAlign.Center,
                    fontSize = when { item.text.length < 40 -> 34.sp; item.text.length < 120 -> 26.sp; item.text.length < 300 -> 20.sp; else -> 16.sp },
                    fontWeight = FontWeight.Bold, lineHeight = 40.sp,
                    modifier = Modifier.align(Alignment.Center).padding(30.dp),
                )
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))) {
                    Column {
                        // Akıcı ilerleme çubukları
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            user.items.forEachIndexed { i, _ ->
                                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.35f))) {
                                    val f = when { i < index -> 1f; i == index -> progress.value; else -> 0f }
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Color.White))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Kapat"), tint = Color.White) }
                            Avatar(user.name, 38.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (mine) t("Durumum") else user.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(formatTime(item.time), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                            }
                            if (mine) {
                                IconButton(onClick = { onDelete(item) }) { Icon(Icons.Outlined.Delete, t("Sil"), tint = Color.White) }
                            } else {
                                IconButton(onClick = { onToggleMute(user.userId); toast(if (user.userId in mutedIds) t("Sessizden çıkarıldı.") else t("Durumları sessize alındı.")) }) {
                                    Icon(Icons.Outlined.NotificationsOff, t("Sessize al"), tint = Color.White)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                // Fotoğraf açıklaması
                if (item.type == "image" && item.text.isNotBlank()) {
                    Text(
                        item.text, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(14.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))) {
                    if (mine) {
                        // Görenler
                        Row(
                            Modifier.align(Alignment.Center).padding(14.dp).clip(RoundedCornerShape(20.dp))
                                .clickable { showViewers = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Visibility, null, tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text(tf("%s kişi gördü", item.viewers.size), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            // Hızlı tepkiler
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                QuickReactions.forEach { e ->
                                    Text(
                                        e, fontSize = 26.sp,
                                        modifier = Modifier.clip(CircleShape).clickable {
                                            sendToOwner(e + " " + tf("Durumuna tepki: \"%s\"", quote), t("Tepkin gönderildi."))
                                        }.padding(6.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicTextField(
                                    value = reply,
                                    onValueChange = { reply = it.take(1000); replying = it.isNotEmpty() },
                                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    decorationBox = { inner ->
                                        if (reply.isEmpty()) Text(t("Yanıtla…"), color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
                                        inner()
                                    },
                                )
                                if (reply.isNotBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        Modifier.size(46.dp).clip(CircleShape).background(BlueBrush).clickable {
                                            val text = reply.trim()
                                            reply = ""
                                            replying = false
                                            sendToOwner(tf("Durumuna yanıt (\"%s\"): ", quote) + text, t("Yanıtın gönderildi."))
                                        },
                                        contentAlignment = Alignment.Center,
                                    ) { Icon(Icons.AutoMirrored.Filled.Send, t("Gönder"), tint = Color.White) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
