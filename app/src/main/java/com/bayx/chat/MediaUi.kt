package com.bayx.chat

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.ByteArrayDataSource
import com.bayx.chat.data.ChatMessage
import com.bayx.chat.data.MediaRef
import com.bayx.chat.data.MediaTools
import com.bayx.chat.data.MsgKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.MediaSource
import java.util.Locale

internal fun formatDuration(millis: Long): String {
    val total = (millis / 1000).toInt()
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

/** Sesli mesaj oynatıcı: aynı anda tek mesaj çalar, ses bellekteki çözülmüş veriden okunur. */
internal object VoicePlayer {
    var playingKey by mutableStateOf<String?>(null)
        private set
    private var player: MediaPlayer? = null

    fun stop() {
        runCatching { player?.release() }
        player = null
        playingKey = null
    }

    fun play(key: String, bytes: ByteArray): Boolean {
        stop()
        return try {
            val p = MediaPlayer()
            p.setDataSource(ByteArrayDataSource(bytes))
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            p.setOnPreparedListener { it.start() }
            p.prepareAsync()
            player = p
            playingKey = key
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }
}

@Composable
private fun rememberMediaBitmap(source: MediaSource?, maxSide: Int): State<Bitmap?> =
    produceState<Bitmap?>(initialValue = null, source, maxSide) {
        value = if (source == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                Bayx.loadMedia(source)?.let { MediaTools.decodeSampled(it, maxSide) }
            }
        }
    }

/** Galeri için kare küçük resim. */
@Composable
internal fun MediaThumb(msg: ChatMessage, modifier: Modifier) {
    val media = msg.media
    val preview = if (msg.kind == MsgKind.IMAGE) media?.thumbnail ?: media?.source else media?.thumbnail
    val bitmap by rememberMediaBitmap(preview, 360)
    Box(modifier.background(Color(0xFFDCE8F2)), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(current.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (msg.kind == MsgKind.VIDEO) {
            Icon(Icons.Filled.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

private fun aspectOf(media: MediaRef): Float =
    if (media.width > 0 && media.height > 0) (media.width.toFloat() / media.height).coerceIn(0.6f, 1.8f) else 1.33f

/** Sohbet balonunun içindeki medya içeriği. */
@Composable
internal fun MediaContent(msg: ChatMessage, tint: Color, onOpen: (ChatMessage) -> Unit) {
    val media = msg.media ?: return
    val toast = rememberToast()
    val context = LocalContext.current
    when (msg.kind) {
        MsgKind.IMAGE, MsgKind.VIDEO -> {
            val preview = if (msg.kind == MsgKind.IMAGE) media.thumbnail ?: media.source else media.thumbnail
            val bitmap by rememberMediaBitmap(preview, 720)
            Box(
                Modifier
                    .width(240.dp)
                    .aspectRatio(aspectOf(media))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFFDCE8F2))
                    .clickable { onOpen(msg) },
                contentAlignment = Alignment.Center,
            ) {
                val current = bitmap
                if (current != null) {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = if (msg.kind == MsgKind.IMAGE) "Fotoğraf" else "Video",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else if (msg.kind == MsgKind.IMAGE) {
                    CircularProgressIndicator(color = Blue, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                }
                if (msg.kind == MsgKind.VIDEO) {
                    Box(
                        Modifier.size(54.dp).clip(CircleShape).background(Color(0x9E0F1B2D)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.PlayArrow, "Videoyu oynat", tint = Color.White, modifier = Modifier.size(30.dp)) }
                    if (media.durationMs > 0) {
                        Text(
                            formatDuration(media.durationMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xB80F1B2D))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
        MsgKind.VOICE -> {
            val playing = VoicePlayer.playingKey == msg.key
            var loading by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(BlueBrush)
                        .clickable(enabled = !loading) {
                            val source = media.source
                            if (playing) {
                                VoicePlayer.stop()
                            } else if (source != null) {
                                loading = true
                                scope.launch {
                                    val bytes = Bayx.loadMedia(source)
                                    loading = false
                                    if (bytes == null || !VoicePlayer.play(msg.key, bytes)) toast("Sesli mesaj oynatılamadı.")
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val iconTint = Color.White
                    if (loading) {
                        CircularProgressIndicator(color = iconTint, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "Durdur" else "Sesli mesajı oynat",
                            tint = iconTint,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Waveform(seed = msg.key.hashCode(), playing = playing, modifier = Modifier.width(150.dp).height(30.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (media.durationMs > 0) formatDuration(media.durationMs) else "Sesli mesaj",
                        color = tint.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
        }
        MsgKind.LOCATION -> {
            Row(
                Modifier.clickable {
                    val coordinates = media.geoUri?.removePrefix("geo:")?.substringBefore(";").orEmpty()
                    val opened = runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates")))
                    }.isSuccess
                    if (!opened) toast("Harita uygulaması bulunamadı.")
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(Satin.Mauve)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = Color.White) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Konum", color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Haritada aç", color = tint, fontSize = 13.sp)
                }
                Spacer(Modifier.width(16.dp))
            }
        }
        else -> Unit
    }
}

/** Fotoğrafı ya da videoyu tam ekranda gösterir. Çözülmüş veri yalnızca bellekte tutulur. */
@Composable
internal fun MediaViewerDialog(msg: ChatMessage, onDismiss: () -> Unit) {
    val media = msg.media ?: return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (msg.kind == MsgKind.IMAGE) {
                val bitmap by rememberMediaBitmap(media.source, 2560)
                val current = bitmap
                if (current != null) {
                    Image(
                        bitmap = current.asImageBitmap(),
                        contentDescription = "Fotoğraf",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                val bytes by produceState<ByteArray?>(initialValue = null, media.source) {
                    value = media.source?.let { Bayx.loadMedia(it) }
                }
                val current = bytes
                if (current != null) {
                    val aspect = if (media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 16f / 9f
                    VideoPlayerView(current, aspect)
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(8.dp),
            ) { Icon(Icons.Filled.Close, "Kapat", tint = Color.White) }
        }
    }
}

@Composable
private fun VideoPlayerView(bytes: ByteArray, aspect: Float) {
    val player = remember { MediaPlayer() }
    var paused by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.4f, 2.5f))
            .clickable {
                runCatching {
                    if (player.isPlaying) {
                        player.pause()
                        paused = true
                    } else {
                        player.start()
                        paused = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        private var prepared = false

                        override fun surfaceCreated(holder: SurfaceHolder) {
                            runCatching {
                                player.setDisplay(holder)
                                if (!prepared) {
                                    prepared = true
                                    player.setDataSource(ByteArrayDataSource(bytes))
                                    player.setOnPreparedListener { it.start() }
                                    player.setOnCompletionListener { paused = true }
                                    player.prepareAsync()
                                }
                            }
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            runCatching { player.setDisplay(null) }
                        }
                    })
                }
            },
        )
        if (paused) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color(0x9E0F1B2D)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, "Oynat", tint = Color.White, modifier = Modifier.size(36.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachSheet(
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onCamera: () -> Unit,
    onVideo: () -> Unit,
    onLocation: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color(0xFFFBF8F4)) {
        Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 30.dp, top = 4.dp)) {
            Text("Paylaş", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AttachCircle(Icons.Outlined.Image, "Galeri", listOf(Color(0xFF7C4DFF), Color(0xFF5E35B1)), onPhoto)
                AttachCircle(Icons.Outlined.PhotoCamera, "Kamera", listOf(Color(0xFFEC407A), Color(0xFFC2185B)), onCamera)
                AttachCircle(Icons.Outlined.Videocam, "Video", listOf(Color(0xFFEF6C00), Color(0xFFE65100)), onVideo)
                AttachCircle(Icons.Outlined.LocationOn, "Konum", listOf(Color(0xFF26A69A), Color(0xFF00897B)), onLocation)
            }
        }
    }
}

/** WhatsApp tarzı: renkli yuvarlak ikon ve altında etiket. */
@Composable
private fun AttachCircle(icon: ImageVector, label: String, colors: List<Color>, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.size(62.dp).clip(CircleShape).background(Brush.verticalGradient(colors)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Ink, fontSize = 13.sp)
    }
}

/** Sesli mesaj dalga formu: mavi → mor → pembe → turuncu degrade çubuklar. */
@Composable
internal fun Waveform(seed: Int, playing: Boolean, modifier: Modifier) {
    val bars = remember(seed) {
        val random = java.util.Random(seed.toLong())
        List(34) { i ->
            val envelope = 0.35f + 0.65f * kotlin.math.sin(Math.PI * (i + 1) / 35.0).toFloat()
            (0.18f + random.nextFloat() * 0.82f) * envelope
        }
    }
    val pulse = if (playing) {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "dalga")
        transition.animateFloat(
            0.75f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(420),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "dalga",
        ).value
    } else 1f
    val colors = listOf(Color(0xFF6EC6FF), Color(0xFF8B7BFF), Color(0xFFE77BC4), Color(0xFFF6A86B))
    androidx.compose.foundation.Canvas(modifier) {
        val step = size.width / bars.size
        val brush = Brush.horizontalGradient(colors, startX = 0f, endX = size.width)
        bars.forEachIndexed { i, value ->
            val h = size.height * value * (if (i % 2 == 0) pulse else 1f)
            drawRoundRect(
                brush,
                topLeft = androidx.compose.ui.geometry.Offset(i * step + step * 0.2f, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(step * 0.6f, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(step * 0.3f),
            )
        }
    }
}

@Composable
internal fun RecordingBar(seconds: Int, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            shadowElevation = 4.dp,
            modifier = Modifier.weight(1f).height(56.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFD93025)))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Kaydediliyor  " + formatDuration(seconds * 1000L),
                    color = Ink,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) { Text("İptal", color = Color(0xFFA32117)) }
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(BlueBrush).clickable(onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                "Sesli mesajı gönder",
                tint = Color.White,
            )
        }
    }
}
