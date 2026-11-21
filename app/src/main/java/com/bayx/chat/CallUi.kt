package com.bayx.chat

import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch
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
import androidx.compose.animation.core.animateFloat
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
        toast(t("Arama için mikrofon izni gerekli."))
        Calls.reject()
    }

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

    // Arayanın duyduğu ton: karşı tarafın telefonu çalıyorsa "çalıyor" tonu, henüz
    // ulaşılamadıysa kısa "aranıyor" bipleri. (Arananın zil sesini Calls çalar.)
    LaunchedEffect(current.phase, current.ringing) {
        if (current.phase == CallPhase.OUTGOING) CallTones.ringback(current.ringing) else CallTones.stop()
    }
    DisposableEffect(Unit) { onDispose { CallTones.stop() } }

    // Bildirimdeki "Cevapla" ile gelindiyse izinleri alıp hemen cevapla.
    LaunchedEffect(current.callId, current.phase) {
        val auto = Calls.autoAcceptCallId
        if (current.phase == CallPhase.INCOMING && auto != null && (auto == "*" || auto == current.callId)) {
            Calls.autoAcceptCallId = null
            askPermissions(current.video) { Calls.accept() }
        }
    }

    // Arkadaki ekranlara dokunuş geçmesin.
    val blocker = remember { MutableInteractionSource() }
    val live = current.phase != CallPhase.INCOMING && current.phase != CallPhase.ENDED
    // Yeni gelen aramada ve arama bitince ekran tam boy açılır.
    LaunchedEffect(current.callId, current.phase) {
        if (current.phase == CallPhase.INCOMING || current.phase == CallPhase.ENDED) CallUiState.minimized = false
    }
    if (CallUiState.minimized && live) {
        MiniCallBar(current) { CallUiState.minimized = false }
        return
    }
    // Geri tuşu: görüşme sürerken ekranı küçültür (WhatsApp gibi), uygulamada gezinebilirsin.
    BackHandler(enabled = true) { if (live) CallUiState.minimized = true }

    val videoUp = showVideo && remote != null
    // Görüntülü aramada dokununca kontroller gizlenir/gösterilir; 5 sn dokunulmazsa kendiliğinden gizlenir.
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(videoUp, lastTouch, controlsVisible) {
        if (videoUp && controlsVisible && current.phase == CallPhase.ACTIVE) {
            delay(5_000)
            controlsVisible = false
        }
    }
    val showControls = !videoUp || controlsVisible
    // Büyük ekranda kimin görüntüsü: karşı taraf ya da (değiştirildiyse) ben.
    var swapped by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(CallBg).clickable(interactionSource = blocker, indication = null) {
            if (videoUp) { controlsVisible = !controlsVisible; lastTouch = System.currentTimeMillis() }
        },
    ) {
        if (!videoUp) ModernCallBackdrop(current.peerName)
        if (videoUp) {
            val big = if (swapped && local != null && current.cameraOn) local!! else remote!!
            key(big) { VideoView(big, mirror = big == local, overlay = false, modifier = Modifier.fillMaxSize()) }
            // Üstte ve altta hafif karartma: yazılar ve tuşlar her görüntüde okunur.
            Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))))
        }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp)) {
            // ---- Üst çubuk: küçült · şifreli · bağlantı kalitesi
            androidx.compose.animation.AnimatedVisibility(showControls || !videoUp) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        GlassIcon(Icons.Filled.KeyboardArrowDown, t("Küçült")) { CallUiState.minimized = true }
                    } else {
                        Spacer(Modifier.size(42.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0x26FFFFFF)).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Lock, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(t("Uçtan uca şifreli"), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (current.phase == CallPhase.ACTIVE) SignalBars(current.quality) else Spacer(Modifier.size(42.dp))
                }
            }

            // ---- Kişi ve durum
            if (videoUp) {
                androidx.compose.animation.AnimatedVisibility(showControls) {
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(current.peerName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(statusText(current), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                    }
                }
            } else {
                val small = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 680
                Spacer(Modifier.height(if (small) 18.dp else 44.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PulsingAvatar(current.peerName, ringing = current.phase == CallPhase.OUTGOING || current.phase == CallPhase.INCOMING || current.phase == CallPhase.CONNECTING)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        current.peerName, color = Color.White, fontSize = if (small) 24.sp else 28.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (current.phase == CallPhase.ENDED && current.endedDuration > 0) statusText(current) + " · " + clock(current.endedDuration) else statusText(current),
                        color = Color(0xFFD5DEEE), fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (current.video) t("Tayfa görüntülü arama") else t("Tayfa sesli arama"),
                        color = Color.White.copy(alpha = 0.5f), fontSize = 12.5.sp,
                    )
                }
            }

            // ---- Uyarılar (zayıf bağlantı, karşı taraf beklemede vb.)
            Spacer(Modifier.height(12.dp))
            val notice = when {
                current.reconnecting -> t("Bağlantı koptu, yeniden bağlanılıyor…")
                current.phase == CallPhase.ACTIVE && current.quality == 1 -> t("Bağlantı zayıf. Ses kesik gelebilir.")
                current.held -> t("Görüşme beklemede")
                current.muted && current.phase == CallPhase.ACTIVE -> t("Mikrofonun kapalı")
                else -> null
            }
            androidx.compose.animation.AnimatedVisibility(notice != null, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(
                    notice.orEmpty(), color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x40000000)).padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            Spacer(Modifier.weight(1f))

            // ---- Alt kısım
            when (current.phase) {
                CallPhase.INCOMING -> IncomingControls(current, onAccept = { askPermissions(current.video) { Calls.accept() } })
                CallPhase.ENDED -> Row(
                    Modifier.fillMaxWidth().padding(bottom = 36.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    LabeledAction(Icons.Filled.Close, t("Kapat"), Color(0x33FFFFFF)) { Calls.dismissEnded() }
                    LabeledAction(if (current.video) Icons.Filled.Videocam else Icons.Filled.Call, t("Tekrar ara"), Accept) {
                        val c = current
                        Calls.dismissEnded()
                        askPermissions(c.video) { Calls.startCall(c.peerId, c.peerName, c.video) }
                    }
                }
                else -> androidx.compose.animation.AnimatedVisibility(
                    showControls,
                    enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
                ) {
                    // Buzlu cam kontrol paneli: etiketli tuşlar (WhatsApp gibi), ortada kırmızı kapat.
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(30.dp)).background(Color(0x2EFFFFFF))
                            .padding(vertical = 16.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            if (current.video) {
                                LabeledAction(
                                    if (current.cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                                    if (current.cameraOn) t("Kamera") else t("Kamera kapalı"),
                                    if (current.cameraOn) Color(0x26FFFFFF) else Color.White,
                                    tint = if (current.cameraOn) Color.White else Color(0xFF0A1A33),
                                ) { Calls.toggleCamera(); lastTouch = System.currentTimeMillis() }
                                LabeledAction(Icons.Filled.Cameraswitch, t("Çevir"), Color(0x26FFFFFF)) { Calls.switchCamera(); lastTouch = System.currentTimeMillis() }
                            } else {
                                LabeledAction(
                                    if (current.speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                    t("Hoparlör"),
                                    if (current.speaker) Color.White else Color(0x26FFFFFF),
                                    tint = if (current.speaker) Color(0xFF0A1A33) else Color.White,
                                ) { Calls.toggleSpeaker() }
                                LabeledAction(Icons.Filled.Dialpad, t("Tuşlar"), Color(0x26FFFFFF)) { dialpad = true }
                            }
                            LabeledAction(
                                if (current.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                                if (current.muted) t("Sessizde") else t("Sessiz"),
                                if (current.muted) Color.White else Color(0x26FFFFFF),
                                tint = if (current.muted) Color(0xFF0A1A33) else Color.White,
                            ) { Calls.toggleMute(); lastTouch = System.currentTimeMillis() }
                            LabeledAction(Icons.Filled.CallEnd, t("Kapat"), Danger) { Calls.hangup() }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            LabeledAction(
                                Icons.Filled.PauseCircle,
                                if (current.held) t("Devam et") else t("Beklet"),
                                if (current.held) Color.White else Color(0x1AFFFFFF),
                                tint = if (current.held) Color(0xFF0A1A33) else Color.White, size = 48,
                            ) { Calls.toggleHold() }
                            if (current.video) {
                                LabeledAction(
                                    if (current.speaker) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                    t("Hoparlör"),
                                    if (current.speaker) Color.White else Color(0x1AFFFFFF),
                                    tint = if (current.speaker) Color(0xFF0A1A33) else Color.White, size = 48,
                                ) { Calls.toggleSpeaker() }
                                LabeledAction(
                                    Icons.Filled.ScreenShare,
                                    if (current.screenSharing) t("Durdur") else t("Ekran"),
                                    if (current.screenSharing) Color.White else Color(0x1AFFFFFF),
                                    tint = if (current.screenSharing) Color(0xFF0A1A33) else Color.White, size = 48,
                                ) { Calls.toggleScreenShare() }
                            } else {
                                LabeledAction(Icons.Filled.ChatBubbleOutline, t("Mesaj"), Color(0x1AFFFFFF), size = 48) {
                                    CallUiState.minimized = true
                                    CallUiState.openChatWith.value = current.peerId
                                }
                            }
                            LabeledAction(Icons.Filled.KeyboardArrowDown, t("Küçült"), Color(0x1AFFFFFF), size = 48) { CallUiState.minimized = true }
                        }
                    }
                }
            }
        }
        if (dialpad) DialpadOverlay(onDismiss = { dialpad = false })
        // Küçük kendi görüntüm: sürüklenebilir, dokununca büyük ekranla yer değiştirir.
        if (showVideo && local != null && current.cameraOn) {
            val small = if (swapped && remote != null) remote!! else local!!
            DraggablePreview(small, mirror = small == local) { if (remote != null) swapped = !swapped }
        }
    }
}

/** Arama ekranı küçültüldüğünde uygulamanın üstünde duran ince şerit. */
object CallUiState {
    var minimized by mutableStateOf(false)
    /** "Mesaj" tuşu: bu kişiyle sohbeti aç (MainActivity dinler). */
    val openChatWith = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

@Composable
private fun MiniCallBar(call: CallState, onExpand: () -> Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF16A34A), Color(0xFF0E8A3E))))
                .clickable(onClick = onExpand)
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(call.peerName, 30.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(call.peerName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(statusText(call) + "  ·  " + t("dönmek için dokun"), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1)
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (call.muted) Color.White else Color(0x33FFFFFF)).clickable { Calls.toggleMute() },
                contentAlignment = Alignment.Center,
            ) { Icon(if (call.muted) Icons.Filled.MicOff else Icons.Filled.Mic, t("Sessiz"), tint = if (call.muted) Color(0xFF0E8A3E) else Color.White, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Danger).clickable { Calls.hangup() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.CallEnd, t("Kapat"), tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** Gelen arama: büyük Reddet/Cevapla, araya "Mesajla reddet" hazır yanıtları. */
@Composable
private fun IncomingControls(call: CallState, onAccept: () -> Unit) {
    val scope = rememberCoroutineScope()
    val replies by com.bayx.chat.data.Comfort.quickReplies.collectAsState()
    var showReplies by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.animation.AnimatedVisibility(showReplies) {
            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (replies + t("Şu an konuşamıyorum, birazdan arayacağım.")).distinct().take(5).forEach { text ->
                    Text(
                        text, color = Color.White, fontSize = 15.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x2EFFFFFF))
                            .clickable {
                                val peer = call.peerId
                                Calls.reject()
                                scope.launch { com.bayx.chat.data.Bayx.sendTextToUser(peer, text) }
                            }
                            .padding(horizontal = 18.dp, vertical = 13.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            LabeledAction(Icons.Filled.CallEnd, t("Reddet"), Danger, size = 70) { Calls.reject() }
            LabeledAction(Icons.Filled.ChatBubbleOutline, t("Mesajla"), if (showReplies) Color.White else Color(0x33FFFFFF),
                tint = if (showReplies) Color(0xFF0A1A33) else Color.White, size = 52) { showReplies = !showReplies }
            LabeledAction(if (call.video) Icons.Filled.Videocam else Icons.Filled.Call, t("Cevapla"), Accept, size = 70, pulse = true, onClick = onAccept)
        }
    }
}

@Composable
private fun LabeledAction(
    icon: ImageVector,
    label: String,
    background: Color,
    tint: Color = Color.White,
    size: Int = 58,
    pulse: Boolean = false,
    onClick: () -> Unit,
) {
    val scale = if (pulse) {
        val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "nabiz")
        tr.animateFloat(1f, 1.08f, androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "s").value
    } else 1f
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width((size + 26).dp)) {
        Box(
            Modifier.size(size.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape).background(background).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size((size * 0.42f).dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun GlassIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(Color(0x26FFFFFF)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp)) }
}

/** Bağlantı kalitesi: üç çubuk (yeşil iyi, sarı orta, kırmızı zayıf). */
@Composable
private fun SignalBars(quality: Int) {
    val color = when (quality) { 3 -> Color(0xFF4ADE80); 2 -> Color(0xFFFACC15); else -> Color(0xFFF87171) }
    val label = when (quality) { 3 -> t("Bağlantı iyi"); 2 -> t("Bağlantı orta"); else -> t("Bağlantı zayıf") }
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(Color(0x26FFFFFF)).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            for (i in 1..3) {
                Box(Modifier.width(4.dp).height((5 + i * 4).dp).clip(RoundedCornerShape(2.dp))
                    .background(if (i <= quality) color else Color.White.copy(alpha = 0.3f)))
            }
        }
    }
}

@Composable
private fun BoxScope.DraggablePreview(track: VideoTrack, mirror: Boolean, onTap: () -> Unit) {
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Box(
        Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(top = 64.dp, end = 14.dp)
            .offset { androidx.compose.ui.unit.IntOffset(offset.x.toInt(), offset.y.toInt()) }
            .size(width = 104.dp, height = 150.dp)
            .shadow(10.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(Unit) {
                detectDragGestures { change, drag -> change.consume(); offset += drag }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
    ) {
        key(track) { VideoView(track, mirror = mirror, overlay = true, modifier = Modifier.fillMaxSize()) }
    }
}

private fun clock(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, (seconds / 60) % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)

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
        // Karşı telefon daveti aldıysa (interneti var) "Çalıyor", almadıysa "Aranıyor".
        CallPhase.OUTGOING -> when {
            call.ringing && call.video -> t("Görüntülü arama · çalıyor…")
            call.ringing -> t("Çalıyor…")
            call.video -> t("Görüntülü arama · aranıyor…")
            else -> t("Aranıyor…")
        }
        CallPhase.INCOMING -> if (call.video) t("Görüntülü arama geliyor") else "Sesli arama geliyor"
        CallPhase.CONNECTING -> t("Bağlanıyor…")
        CallPhase.ACTIVE -> {
            val seconds = ((now - call.startedAt) / 1000).coerceAtLeast(0)
            val time = "%02d:%02d".format(seconds / 60, seconds % 60)
            when {
                call.reconnecting -> t("Yeniden bağlanıyor…") + " · $time"
                call.held -> "Beklemede · $time"
                else -> time
            }
        }
        CallPhase.ENDED -> t(call.endReason ?: "Arama sona erdi")
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


// --- Modern arama arka planı: yavaş akan renkli ışıklar + cam efekti ---
@Composable
private fun ModernCallBackdrop(name: String) {
    // Yeni tasarım: koyu, sıcak zemin; avatarın arkasında iki yumuşak ışık halkası (hafifçe nefes alır).
    val base = if (ThemeState.dark) Color(0xFF120708) else Color(0xFF1C130D)
    val glow = if (ThemeState.dark) Color(0xFF3A0F0C) else Color(0xFF4A2E1A)
    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "bg")
    val p by tr.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(4000, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "p")
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawRect(base)
        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.33f)
        drawCircle(glow.copy(alpha = 0.50f + 0.08f * p), radius = size.width * (0.72f + 0.03f * p), center = c)
        drawCircle(glow.copy(alpha = 0.75f), radius = size.width * 0.46f, center = c)
    }
}

@Composable
private fun PulsingAvatar(name: String, ringing: Boolean) {
    val tr = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val k by tr.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.LinearEasing)), label = "k")
    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        if (ringing) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val base = 64.dp.toPx()
                for (i in 0 until 3) {
                    val f = (k + i / 3f) % 1f
                    drawCircle(Color.White.copy(alpha = 0.28f * (1f - f)), radius = base + (size.minDimension / 2 - base) * f,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
                }
            }
        }
        Box(Modifier.size(138.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
            Avatar(name, 128.dp)
        }
    }
}
