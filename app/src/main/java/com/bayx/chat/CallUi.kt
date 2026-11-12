package com.bayx.chat

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bayx.chat.data.CallPhase
import com.bayx.chat.data.CallState
import com.bayx.chat.data.Calls
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val CallBg = Brush.verticalGradient(listOf(Color(0xFF0B2A5C), Color(0xFF0A1A33)))
private val Danger = Color(0xFFE5484D)
private val Accept = Color(0xFF22C55E)

/** Arama için gereken izinleri isteyip verilirse [onGranted]'ı çağıran yardımcı. */
@Composable
fun rememberCallPermissions(onDenied: () -> Unit): (video: Boolean, onGranted: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val action = pending
        pending = null
        if (result.values.all { it }) action?.invoke() else onDenied()
    }
    return remember(context) {
        { video: Boolean, onGranted: () -> Unit ->
            val needed = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (video) add(Manifest.permission.CAMERA)
            }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) {
                onGranted()
            } else {
                pending = onGranted
                launcher.launch(needed.toTypedArray())
            }
        }
    }
}

/** Uygulamanın en üstünde gösterilen arama ekranı. */
@Composable
fun CallOverlay() {
    val call by Calls.state.collectAsState()
    val current = call ?: return
    val context = LocalContext.current
    val toast = rememberToast()
    val askPermissions = rememberCallPermissions {
        toast("Arama için mikrofon izni gerekli.")
        Calls.reject()
    }

    BackHandler(enabled = true) { }

    // Arama sürerken ekran açık kalsın ve kilit ekranının üstünde görünsün.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 27) {
            activity?.setShowWhenLocked(true)
            activity?.setTurnScreenOn(true)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 27) {
                activity?.setShowWhenLocked(false)
                activity?.setTurnScreenOn(false)
            }
        }
    }

    val remote by Calls.remoteVideo.collectAsState()
    val local by Calls.localVideo.collectAsState()
    val showVideo = current.video && current.phase in setOf(CallPhase.CONNECTING, CallPhase.ACTIVE)

    // Arkadaki ekranlara dokunuş geçmesin.
    val blocker = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(CallBg).clickable(interactionSource = blocker, indication = null) { }) {
        if (showVideo && remote != null) {
            VideoView(remote!!, mirror = false, overlay = false, modifier = Modifier.fillMaxSize())
        }
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (showVideo && remote != null) 8.dp else 72.dp))
            if (!(showVideo && remote != null)) {
                Avatar(current.peerName, 128.dp)
                Spacer(Modifier.height(20.dp))
            }
            Text(current.peerName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(statusText(current), color = Color(0xFFC9D6EA), fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            when (current.phase) {
                CallPhase.INCOMING -> Row(
                    Modifier.fillMaxWidth().padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundAction(Icons.Filled.CallEnd, "Reddet", Danger, size = 72) { Calls.reject() }
                    RoundAction(
                        if (current.video) Icons.Filled.Videocam else Icons.Filled.Call,
                        "Cevapla",
                        Accept,
                        size = 72,
                    ) { askPermissions(current.video) { Calls.accept() } }
                }
                CallPhase.ENDED -> Text(
                    "Kapat",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .padding(bottom = 40.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0x33FFFFFF))
                        .clickable { Calls.dismissEnded() }
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                )
                else -> Row(
                    Modifier.fillMaxWidth().padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundAction(
                        if (current.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        if (current.muted) "Mikrofonu aç" else "Sessize al",
                        if (current.muted) Color.White else Color(0x33FFFFFF),
                        tint = if (current.muted) Color(0xFF0A1A33) else Color.White,
                    ) { Calls.toggleMute() }
                    RoundAction(
                        if (current.speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        if (current.speaker) "Hoparlörü kapat" else "Hoparlörü aç",
                        if (current.speaker) Color.White else Color(0x33FFFFFF),
                        tint = if (current.speaker) Color(0xFF0A1A33) else Color.White,
                    ) { Calls.toggleSpeaker() }
                    if (current.video) {
                        RoundAction(
                            if (current.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            if (current.cameraOn) "Kamerayı kapat" else "Kamerayı aç",
                            Color(0x33FFFFFF),
                        ) { Calls.toggleCamera() }
                        RoundAction(Icons.Filled.Cameraswitch, "Kamerayı çevir", Color(0x33FFFFFF)) { Calls.switchCamera() }
                    }
                    RoundAction(Icons.Filled.CallEnd, "Kapat", Danger, size = 68) { Calls.hangup() }
                }
            }
        }
        if (showVideo && local != null && current.cameraOn) {
            VideoView(
                local!!,
                mirror = true,
                overlay = true,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(16.dp)
                    .size(width = 110.dp, height = 160.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
    }
}

@Composable
private fun statusText(call: CallState): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.phase) {
        while (call.phase == CallPhase.ACTIVE) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    return when (call.phase) {
        CallPhase.OUTGOING -> if (call.video) "Görüntülü arama · aranıyor…" else "Aranıyor…"
        CallPhase.INCOMING -> if (call.video) "Görüntülü arama geliyor" else "Sesli arama geliyor"
        CallPhase.CONNECTING -> "Bağlanıyor…"
        CallPhase.ACTIVE -> {
            val seconds = ((now - call.startedAt) / 1000).coerceAtLeast(0)
            "%02d:%02d".format(seconds / 60, seconds % 60)
        }
        CallPhase.ENDED -> call.endReason ?: "Arama sona erdi"
    }
}

@Composable
private fun RoundAction(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color = Color.White,
    size: Int = 60,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size((size * 0.42f).dp))
    }
}

@Composable
private fun VideoView(track: VideoTrack, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(Calls.eglContext(), null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                if (overlay) setZOrderMediaOverlay(true)
                renderer = this
            }
        },
        onRelease = { view ->
            runCatching { view.release() }
            renderer = null
        },
    )
    val target = renderer
    DisposableEffect(track, target) {
        if (target != null) runCatching { track.addSink(target) }
        onDispose { if (target != null) runCatching { track.removeSink(target) } }
    }
}
