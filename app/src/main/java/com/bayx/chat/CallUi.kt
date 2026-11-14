package com.bayx.chat

import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.PauseCircle
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
import com.bayx.chat.data.CallTones
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
    var dialpad by remember { mutableStateOf(false) }

    // Arama sesleri: arayanda geri çalma tonu, arananda zil sesi.
    LaunchedEffect(current.phase, current.peerOnline) {
        when (current.phase) {
            CallPhase.OUTGOING -> CallTones.ringback(current.peerOnline)
            CallPhase.INCOMING -> CallTones.incomingRing(context)
            else -> CallTones.stop()
        }
    }
    DisposableEffect(Unit) { onDispose { CallTones.stop() } }

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
                else -> Column(
                    Modifier.fillMaxWidth().padding(bottom = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoundAction(
                            if (current.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            if (current.muted) t("Mikrofonu aç") else "Sessize al",
                            if (current.muted) Color.White else Color(0x22FFFFFF),
                            tint = if (current.muted) Color(0xFF0A1A33) else Color.White,
                            size = 56,
                        ) { Calls.toggleMute() }
                        RoundAction(
                            if (current.speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            if (current.speaker) t("Hoparlörü kapat") else t("Hoparlörü aç"),
                            if (current.speaker) Color.White else Color(0x22FFFFFF),
                            tint = if (current.speaker) Color(0xFF0A1A33) else Color.White,
                            size = 56,
                        ) { Calls.toggleSpeaker() }
                        RoundAction(
                            Icons.Filled.PauseCircle,
                            if (current.held) t("Beklemeden çıkar") else "Beklet",
                            if (current.held) Color.White else Color(0x22FFFFFF),
                            tint = if (current.held) Color(0xFF0A1A33) else Color.White,
                            size = 56,
                        ) { Calls.toggleHold() }
                        RoundAction(Icons.Filled.Dialpad, t("Tuş takımı"), Color(0x22FFFFFF), size = 56) { dialpad = true }
                    }
                    if (current.video) {
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RoundAction(
                                if (current.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                if (current.cameraOn) t("Kamerayı kapat") else t("Kamerayı aç"),
                                Color(0x22FFFFFF), size = 56,
                            ) { Calls.toggleCamera() }
                            RoundAction(Icons.Filled.Cameraswitch, t("Kamerayı çevir"), Color(0x22FFFFFF), size = 56) { Calls.switchCamera() }
                            RoundAction(
                                Icons.Filled.ScreenShare,
                                if (current.screenSharing) t("Paylaşımı durdur") else t("Ekranı paylaş"),
                                if (current.screenSharing) Color.White else Color(0x22FFFFFF),
                                tint = if (current.screenSharing) Color(0xFF0A1A33) else Color.White,
                                size = 56,
                            ) { Calls.toggleScreenShare() }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    RoundAction(Icons.Filled.CallEnd, "Kapat", Danger, size = 74) { Calls.hangup() }
                }
            }
        }
        if (dialpad) DialpadOverlay(onDismiss = { dialpad = false })
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
        CallPhase.OUTGOING -> when {
            !call.peerOnline -> t("Çevrimdışı · aranıyor…")
            call.video -> t("Görüntülü arama · çalıyor…")
            else -> t("Çalıyor…")
        }
        CallPhase.INCOMING -> if (call.video) t("Görüntülü arama geliyor") else "Sesli arama geliyor"
        CallPhase.CONNECTING -> t("Bağlanıyor…")
        CallPhase.ACTIVE -> {
            val seconds = ((now - call.startedAt) / 1000).coerceAtLeast(0)
            val time = "%02d:%02d".format(seconds / 60, seconds % 60)
            if (call.held) "Beklemede · $time" else time
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

/** Profesyonel arama tuş takımı: basınca DTMF tonu çalar. */
@Composable
private fun DialpadOverlay(onDismiss: () -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    val tones = mapOf(
        "1" to android.media.ToneGenerator.TONE_DTMF_1, "2" to android.media.ToneGenerator.TONE_DTMF_2,
        "3" to android.media.ToneGenerator.TONE_DTMF_3, "4" to android.media.ToneGenerator.TONE_DTMF_4,
        "5" to android.media.ToneGenerator.TONE_DTMF_5, "6" to android.media.ToneGenerator.TONE_DTMF_6,
        "7" to android.media.ToneGenerator.TONE_DTMF_7, "8" to android.media.ToneGenerator.TONE_DTMF_8,
        "9" to android.media.ToneGenerator.TONE_DTMF_9, "0" to android.media.ToneGenerator.TONE_DTMF_0,
        "*" to android.media.ToneGenerator.TONE_DTMF_S, "#" to android.media.ToneGenerator.TONE_DTMF_P,
    )
    val gen = remember { runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 80) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { runCatching { gen?.release() } } }
    var typed by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Color(0xE6071023)).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
        ) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.systemBarsPadding().padding(24.dp)) {
            Text(typed.ifEmpty { t("Tuş takımı") }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { key ->
                        Box(
                            Modifier.size(74.dp).clip(CircleShape).background(Color(0x22FFFFFF)).clickable {
                                typed += key
                                runCatching { tones[key]?.let { gen?.startTone(it, 160) } }
                            },
                            contentAlignment = Alignment.Center,
                        ) { Text(key, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Medium) }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.size(62.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, "Kapat", tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
    }
}
