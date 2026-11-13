package com.bayx.chat.data

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CallPhase { OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED }

data class CallState(
    val callId: String,
    val peerId: String,
    val peerName: String,
    val video: Boolean,
    val phase: CallPhase,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val cameraOn: Boolean = true,
    val startedAt: Long = 0L,
    val endReason: String? = null,
    val peerOnline: Boolean = true,
    val held: Boolean = false,
    val screenSharing: Boolean = false,
    val fromMe: Boolean = false,
)

/**
 * Birebir sesli ve görüntülü arama (WebRTC). Kurulum mesajları Tayfa sunucusu üzerinden
 * iletilir; ses ve görüntü cihazlar arasında DTLS-SRTP ile uçtan uca şifrelidir.
 */
object Calls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** WebRTC işlemleri sırayla, tek bir iş parçacığında yürütülür. */
    private val rtc = Executors.newSingleThreadExecutor { r -> Thread(r, "tayfa-arama") }.asCoroutineDispatcher()

    private lateinit var app: Context

    val state = MutableStateFlow<CallState?>(null)
    val localVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo = MutableStateFlow<VideoTrack?>(null)

    @Volatile private var foreground = false
    private var pollJob: Job? = null
    private var ringJob: Job? = null
    private var disconnectJob: Job? = null

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null
    /** Arama ekranındaki "Ekranı paylaş" düğmesinin Activity'den ekran yakalama izni istemesini sağlar. */
    @Volatile var screenShareRequester: (() -> Unit)? = null

    private var pendingOffer: String? = null
    private val pendingCandidates = ArrayList<IceCandidate>()
    private var remoteDescriptionSet = false
    private var ringtone: Ringtone? = null

    fun init(context: Context) {
        app = context.applicationContext
        CallLog.init(app)
    }

    fun eglContext(): EglBase.Context {
        val existing = egl
        if (existing != null) return existing.eglBaseContext
        return EglBase.create().also { egl = it }.eglBaseContext
    }

    // ------------------------------------------------------------- yoklama

    fun onForeground(active: Boolean) {
        foreground = active
        if (active) ensurePolling()
    }

    fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var failures = 0
            while (foreground || state.value != null) {
                val token = Bayx.accessToken
                if (token == null) {
                    delay(3_000)
                    continue
                }
                val events = try {
                    Api.callPoll(token).also { failures = 0 }
                } catch (e: Exception) {
                    failures++
                    delay((2_000L * failures).coerceAtMost(15_000L))
                    continue
                }
                // Aynı paket içinde kapatılmış aramaların davetini atla.
                val closed = events.filter { it.optString("type") in setOf("hangup", "reject") }
                    .map { it.optString("call_id") }.toSet()
                for (event in events) {
                    if (event.optString("type") == "invite" && event.optString("call_id") in closed) continue
                    withContext(rtc) { runCatching { handle(event) } }
                }
            }
        }
    }

    private fun send(type: String, to: String, callId: String, data: JSONObject = JSONObject()) {
        val token = Bayx.accessToken ?: return
        scope.launch { runCatching { Api.callSend(token, to, callId, type, data) } }
    }

    // ------------------------------------------------------------ olaylar

    private suspend fun handle(event: JSONObject) {
        val type = event.optString("type")
        val from = event.optString("from")
        val callId = event.optString("call_id")
        val data = event.optJSONObject("data") ?: JSONObject()
        val current = state.value

        if (type == "invite") {
            if (current != null && current.phase != CallPhase.ENDED) {
                if (current.callId != callId) send("busy", from, callId)
                return
            }
            val sdp = data.optString("sdp")
            if (sdp.isEmpty()) return
            pendingOffer = sdp
            pendingCandidates.clear()
            remoteDescriptionSet = false
            val name = data.optString("name").ifBlank { from.substringBefore(":").removePrefix("@") }
            val video = data.optBoolean("video")
            state.value = CallState(callId, from, name, video, CallPhase.INCOMING, speaker = video, cameraOn = video, fromMe = false)
            startRinging()
            Notifier.cancelCall(app)
            if (!foreground) Notifier.showIncomingCall(app, name, video)
            ringJob = scope.launch {
                delay(45_000)
                withContext(rtc) {
                    if (state.value?.callId == callId && state.value?.phase == CallPhase.INCOMING) end("Cevapsız arama", notify = false)
                }
            }
            return
        }

        if (current == null || current.callId != callId || current.peerId != from) return
        when (type) {
            "answer" -> {
                if (current.phase != CallPhase.OUTGOING) return
                ringJob?.cancel()
                state.value = current.copy(phase = CallPhase.CONNECTING)
                val peer = pc ?: return
                peer.setRemote(SessionDescription(SessionDescription.Type.ANSWER, data.optString("sdp")))
                remoteDescriptionSet = true
                flushCandidates()
            }
            "candidate" -> {
                val candidate = IceCandidate(data.optString("sdpMid"), data.optInt("sdpMLineIndex"), data.optString("candidate"))
                if (remoteDescriptionSet && pc != null) pc?.addIceCandidate(candidate) else pendingCandidates.add(candidate)
            }
            "hangup" -> end(if (current.phase == CallPhase.INCOMING) "Cevapsız arama" else "Arama sona erdi", notify = false)
            "reject" -> end("Arama reddedildi", notify = false)
            "busy" -> end("Kişi başka bir görüşmede", notify = false)
        }
    }

    private fun flushCandidates() {
        val peer = pc ?: return
        pendingCandidates.forEach { peer.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    // ------------------------------------------------------- arama başlat

    /** Giden arama. Mikrofon (ve görüntülüyse kamera) izni önceden alınmış olmalı. */
    fun startCall(peerId: String, peerName: String, video: Boolean) {
        if (state.value?.let { it.phase != CallPhase.ENDED } == true) return
        val callId = newCallId()
        val online = Bayx.online.value.contains(peerId)
        state.value = CallState(callId, peerId, peerName, video, CallPhase.OUTGOING, speaker = video, cameraOn = video, peerOnline = online, fromMe = true)
        ensurePolling()
        scope.launch {
            withContext(rtc) {
                try {
                    pendingCandidates.clear()
                    remoteDescriptionSet = false
                    val peer = createPeer(callId, peerId, video)
                    val offer = peer.createOfferAwait(constraints(video))
                    peer.setLocal(offer)
                    val me = (Bayx.auth.value as? AuthState.LoggedIn)
                    val name = me?.displayName?.ifBlank { null } ?: me?.userId?.substringBefore(":")?.removePrefix("@") ?: ""
                    send(
                        "invite",
                        peerId,
                        callId,
                        JSONObject().put("sdp", offer.description).put("video", video).put("name", name),
                    )
                    ringJob = scope.launch {
                        delay(45_000)
                        withContext(rtc) {
                            if (state.value?.callId == callId && state.value?.phase == CallPhase.OUTGOING) end("Cevap yok")
                        }
                    }
                } catch (e: Exception) {
                    end("Arama başlatılamadı")
                }
            }
        }
    }

    /** Gelen aramayı kabul eder. Mikrofon (ve görüntülüyse kamera) izni önceden alınmış olmalı. */
    fun accept() {
        val current = state.value ?: return
        if (current.phase != CallPhase.INCOMING) return
        stopRinging()
        ringJob?.cancel()
        Notifier.cancelCall(app)
        state.value = current.copy(phase = CallPhase.CONNECTING)
        scope.launch {
            withContext(rtc) {
                try {
                    val offer = pendingOffer ?: throw IllegalStateException("teklif yok")
                    val peer = createPeer(current.callId, current.peerId, current.video)
                    peer.setRemote(SessionDescription(SessionDescription.Type.OFFER, offer))
                    remoteDescriptionSet = true
                    val answer = peer.createAnswerAwait(constraints(current.video))
                    peer.setLocal(answer)
                    send("answer", current.peerId, current.callId, JSONObject().put("sdp", answer.description))
                    flushCandidates()
                } catch (e: Exception) {
                    end("Arama bağlanamadı")
                }
            }
        }
    }

    fun reject() {
        val current = state.value ?: return
        scope.launch { withContext(rtc) { end(null, notify = false).also { send("reject", current.peerId, current.callId) } } }
    }

    fun hangup() {
        scope.launch { withContext(rtc) { end(null) } }
    }

    fun dismissEnded() {
        if (state.value?.phase == CallPhase.ENDED) state.value = null
    }

    /** Çıkış yapılırken: açık bir arama varsa sessizce kapatır. */
    fun shutdown() {
        scope.launch { withContext(rtc) { end(null) } }
    }

    // -------------------------------------------------------- arama içi

    fun toggleMute() {
        val current = state.value ?: return
        val muted = !current.muted
        state.value = current.copy(muted = muted)
        scope.launch(rtc) { audioTrack?.setEnabled(!muted) }
    }

    /** Beklemeye al: mikrofonu ve karşıdan geleni sustur; "Beklemede" göster. */
    fun toggleHold() {
        val current = state.value ?: return
        val held = !current.held
        state.value = current.copy(held = held)
        scope.launch(rtc) {
            audioTrack?.setEnabled(!held && !current.muted)
            runCatching { remoteAudioEnabled(!held) }
        }
    }

    fun toggleSpeaker() {
        val current = state.value ?: return
        val speaker = !current.speaker
        state.value = current.copy(speaker = speaker)
        applySpeaker(speaker)
    }

    fun toggleCamera() {
        val current = state.value ?: return
        if (!current.video) return
        val on = !current.cameraOn
        state.value = current.copy(cameraOn = on)
        scope.launch(rtc) {
            videoTrack?.setEnabled(on)
            runCatching { if (on) capturer?.startCapture(1280, 720, 30) else capturer?.stopCapture() }
        }
    }

    fun switchCamera() {
        scope.launch(rtc) { runCatching { capturer?.switchCamera(null) } }
    }

    /** Ekran paylaşımını aç/kapat. Açarken Activity'den izin ister. */
    fun toggleScreenShare() {
        val current = state.value ?: return
        if (!current.video) return
        if (current.screenSharing) stopScreenShare() else screenShareRequester?.invoke()
    }

    /** Activity, ekran yakalama izni sonucunu buraya iletir. */
    fun onScreenCaptureResult(data: Intent?) {
        if (data == null) return
        val current = state.value ?: return
        if (!current.video) return
        ScreenShareService.start(app)
        scope.launch(rtc) {
            try {
                val src = videoSource ?: return@launch
                val helper = surfaceHelper ?: return@launch
                runCatching { capturer?.stopCapture() }
                val screen = ScreenCapturerAndroid(data, object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        scope.launch(rtc) { stopScreenShare() }
                    }
                })
                screen.initialize(helper, app, src.capturerObserver)
                screen.startCapture(1280, 720, 30)
                screenCapturer = screen
                state.value = state.value?.copy(screenSharing = true, cameraOn = true)
            } catch (e: Exception) {
                ScreenShareService.stop(app)
                stopScreenShare()
            }
        }
    }

    fun stopScreenShare() {
        scope.launch(rtc) {
            runCatching { screenCapturer?.stopCapture() }
            runCatching { screenCapturer?.dispose() }
            screenCapturer = null
            ScreenShareService.stop(app)
            // Ekran paylaşımı bittiğinde kamerayı yeniden başlat.
            val current = state.value
            if (current != null && current.video) {
                runCatching { capturer?.startCapture(1280, 720, 30) }
            }
            state.value = state.value?.copy(screenSharing = false)
        }
    }

    private fun remoteAudioEnabled(enabled: Boolean) {
        runCatching {
            pc?.receivers?.forEach { r ->
                val track = r.track()
                if (track is org.webrtc.AudioTrack) track.setEnabled(enabled)
            }
        }
    }

    // -------------------------------------------------------------- WebRTC

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val eglContext = eglContext()
        val audioModule = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    private suspend fun iceServers(): List<PeerConnection.IceServer> {
        val servers = ArrayList<PeerConnection.IceServer>()
        val token = Bayx.accessToken
        if (token != null) {
            val turn = runCatching { Api.turn(token) }.getOrNull()
            val uris = turn?.optJSONArray("uris")
            if (turn != null && uris != null && uris.length() > 0) {
                val list = (0 until uris.length()).map { uris.getString(it) }
                servers.add(
                    PeerConnection.IceServer.builder(list)
                        .setUsername(turn.optString("username"))
                        .setPassword(turn.optString("password"))
                        .createIceServer(),
                )
            }
        }
        servers.add(PeerConnection.IceServer.builder("stun:${Api.SERVER_NAME}:3478").createIceServer())
        return servers
    }

    private suspend fun createPeer(callId: String, peerId: String, video: Boolean): PeerConnection {
        val f = ensureFactory()
        val config = PeerConnection.RTCConfiguration(iceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = f.createPeerConnection(config, observer(callId, peerId))
            ?: throw IllegalStateException("bağlantı oluşturulamadı")
        pc = peer

        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("tayfa-ses", audioSource).also { peer.addTrack(it, listOf("tayfa")) }

        if (video) {
            val enumerator = Camera2Enumerator(app)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) } ?: enumerator.deviceNames.firstOrNull()
            if (name != null) {
                val cam = enumerator.createCapturer(name, null)
                val helper = SurfaceTextureHelper.create("tayfa-kamera", eglContext())
                val source = f.createVideoSource(false)
                cam.initialize(helper, app, source.capturerObserver)
                cam.startCapture(1280, 720, 30)
                capturer = cam
                surfaceHelper = helper
                videoSource = source
                videoTrack = f.createVideoTrack("tayfa-goruntu", source).also {
                    peer.addTrack(it, listOf("tayfa"))
                    localVideo.value = it
                }
            }
        }
        startAudio(video)
        return peer
    }

    private fun observer(callId: String, peerId: String) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            send(
                "candidate",
                peerId,
                callId,
                JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex),
            )
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            scope.launch(rtc) {
                val current = state.value ?: return@launch
                if (current.callId != callId) return@launch
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        disconnectJob?.cancel()
                        if (current.phase != CallPhase.ACTIVE) {
                            state.value = current.copy(phase = CallPhase.ACTIVE, startedAt = System.currentTimeMillis())
                        }
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        disconnectJob?.cancel()
                        disconnectJob = scope.launch {
                            delay(10_000)
                            withContext(rtc) { if (state.value?.callId == callId) end("Bağlantı koptu") }
                        }
                    }
                    PeerConnection.PeerConnectionState.FAILED -> end("Bağlantı kurulamadı")
                    else -> Unit
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track()
            if (track is VideoTrack) remoteVideo.value = track
        }

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun constraints(video: Boolean) = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (video) "true" else "false"))
    }

    /** Aramayı yerelde kapatır; [notify] doğruysa karşı tarafa kapatma mesajı gönderir. */
    private fun end(reason: String?, notify: Boolean = true) {
        val current = state.value ?: return
        if (current.phase == CallPhase.ENDED) return
        if (notify) send("hangup", current.peerId, current.callId)
        runCatching {
            CallLog.record(
                peerId = current.peerId,
                peerName = current.peerName,
                outgoing = current.fromMe,
                answered = current.phase == CallPhase.ACTIVE,
                video = current.video,
            )
        }
        ringJob?.cancel()
        disconnectJob?.cancel()
        stopRinging()
        Notifier.cancelCall(app)
        localVideo.value = null
        remoteVideo.value = null
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        runCatching { pc?.dispose() }
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        screenCapturer = null
        runCatching { ScreenShareService.stop(app) }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        capturer = null
        surfaceHelper = null
        videoSource = null
        videoTrack = null
        audioSource = null
        audioTrack = null
        pc = null
        pendingOffer = null
        pendingCandidates.clear()
        remoteDescriptionSet = false
        stopAudio()
        if (reason == null) {
            state.value = null
        } else {
            state.value = current.copy(phase = CallPhase.ENDED, endReason = reason)
            scope.launch {
                delay(2_500)
                if (state.value?.callId == current.callId && state.value?.phase == CallPhase.ENDED) state.value = null
            }
        }
    }

    // ------------------------------------------------------------- ses

    private fun audioManager() = app.getSystemService(AudioManager::class.java)

    private fun startAudio(speaker: Boolean) {
        val am = audioManager() ?: return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        applySpeaker(speaker)
    }

    private fun applySpeaker(on: Boolean) {
        val am = audioManager() ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            val wanted = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = am.availableCommunicationDevices.firstOrNull { it.type == wanted }
            if (device != null) am.setCommunicationDevice(device) else if (!on) am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    private fun stopAudio() {
        val am = audioManager() ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
    }

    private fun startRinging() {
        stopRinging()
        runCatching {
            ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply {
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        runCatching {
            app.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700, 500), 0))
        }
    }

    private fun stopRinging() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { app.getSystemService(Vibrator::class.java)?.cancel() }
    }

    private fun newCallId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        return (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    // ------------------------------------------------- SDP yardımcıları

    private suspend fun PeerConnection.createOfferAwait(c: MediaConstraints): SessionDescription =
        suspendCancellableCoroutine { cont ->
            createOffer(createObserver { result -> result.fold({ cont.resume(it) }, { cont.resumeWithException(it) }) }, c)
        }

    private suspend fun PeerConnection.createAnswerAwait(c: MediaConstraints): SessionDescription =
        suspendCancellableCoroutine { cont ->
            createAnswer(createObserver { result -> result.fold({ cont.resume(it) }, { cont.resumeWithException(it) }) }, c)
        }

    private suspend fun PeerConnection.setLocal(sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        setLocalDescription(setObserver { result -> result.fold({ cont.resume(Unit) }, { cont.resumeWithException(it) }) }, sdp)
    }

    private suspend fun PeerConnection.setRemote(sdp: SessionDescription) = suspendCancellableCoroutine<Unit> { cont ->
        setRemoteDescription(setObserver { result -> result.fold({ cont.resume(Unit) }, { cont.resumeWithException(it) }) }, sdp)
    }

    private fun createObserver(done: (Result<SessionDescription>) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {
            if (sdp != null) done(Result.success(sdp)) else done(Result.failure(IllegalStateException("boş SDP")))
        }
        override fun onCreateFailure(error: String?) = done(Result.failure(IllegalStateException(error ?: "SDP hatası")))
        override fun onSetSuccess() = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    private fun setObserver(done: (Result<Unit>) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetSuccess() = done(Result.success(Unit))
        override fun onSetFailure(error: String?) = done(Result.failure(IllegalStateException(error ?: "SDP hatası")))
    }
}
