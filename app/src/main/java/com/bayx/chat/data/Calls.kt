package com.bayx.chat.data

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
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
    /** Giden arama: karşı tarafın telefonu daveti aldı (interneti var) ve çalıyor. */
    val ringing: Boolean = false,
    /** Görüşme sürerken bağlantı koptu; yeniden kuruluyor. */
    val reconnecting: Boolean = false,
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

    /** Arka plan servisi çalışırken uygulama kapalı olsa da aramalar dinlenir. */
    @Volatile var keepAlive = false
        set(value) {
            field = value
            if (value) ensurePolling()
        }

    private var pollJob: Job? = null
    private var ringJob: Job? = null
    private var disconnectJob: Job? = null
    private var connectJob: Job? = null
    private var restartJob: Job? = null

    /** Sunucudan alınan son arama olayı; sunucu teslim edilenleri bu teyitle siler. */
    @Volatile private var lastEventId = 0L

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

    /** Bildirimdeki "Cevapla" ile açıldıysa arama ekranı izinleri alıp hemen cevaplar. */
    @Volatile var autoAcceptCallId: String? = null

    private var pendingOffer: String? = null
    private val pendingCandidates = ArrayList<IceCandidate>()
    /** Davetten önce gelen adaylar (ağ sırası karışırsa kaybolmasın). */
    private val earlyCandidates = LinkedHashMap<String, MutableList<IceCandidate>>()
    private var remoteDescriptionSet = false
    private var ringtone: Ringtone? = null
    private var focusRequest: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var scoStarted = false

    // Gönderilen sinyaller sırayla gider: davet her zaman adaylardan önce ulaşır.
    private class Outgoing(val type: String, val to: String, val callId: String, val data: JSONObject)
    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
    private var senderStarted = false

    fun init(context: Context) {
        app = context.applicationContext
        CallLog.init(app)
        startSender()
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

    /** Arama dinlemesi sürmeli mi? (Uygulama açık, arka plan servisi çalışıyor ya da görüşme var.) */
    private fun shouldListen() = foreground || keepAlive || state.value != null

    @Synchronized
    fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var failures = 0
            var socketWorks = true
            var pollsSinceSocket = 0
            while (shouldListen()) {
                val token = Bayx.accessToken
                if (token == null) {
                    delay(3_000)
                    continue
                }
                // Önce anlık bağlantı (WebSocket); kurulamazsa (eski sunucu, kısıtlı ağ) uzun yoklama.
                if (socketWorks) {
                    val opened = runSocket(token)
                    if (opened) {
                        failures = 0
                        delay(500) // bağlantı koptu: kısa bir aradan sonra yeniden bağlan
                        continue
                    }
                    socketWorks = false
                    pollsSinceSocket = 0
                }
                val events = try {
                    Api.callPoll(token, lastEventId).also { failures = 0 }
                } catch (e: Exception) {
                    failures++
                    // Görüşme sürerken hızlı dene; boşta pili korumak için yavaşla.
                    val cap = if (state.value != null) 3_000L else 15_000L
                    delay((1_000L * failures).coerceAtMost(cap))
                    continue
                }
                processEvents(events)
                // Ara sıra anlık bağlantıyı yeniden dene (ör. ağ değişti ya da sunucu güncellendi).
                if (!socketWorks && ++pollsSinceSocket >= 5) socketWorks = true
            }
        }
    }

    /** Gelen arama olaylarını sırayla işler; daha önce işlenmiş olanları atlar. */
    private suspend fun processEvents(events: List<JSONObject>) {
        // Aynı paket içinde kapatılmış aramaların davetini atla.
        val closed = events.filter { it.optString("type") in setOf("hangup", "reject") }
            .map { it.optString("call_id") }.toSet()
        for (event in events) {
            val id = event.optLong("id", 0L)
            if (id > 0L) {
                if (id <= lastEventId) continue // daha önce işlendi (yanıt yolda kaybolup yeniden geldi)
                lastEventId = id
            }
            if (event.optString("type") == "invite" && event.optString("call_id") in closed) continue
            withContext(rtc) { runCatching { handle(event) } }
        }
    }

    /**
     * Anlık arama sinyali: bağlantı açık kaldığı sürece olaylar geldiği anda işlenir.
     * Bağlantı hiç kurulamadıysa false döner (çağıran uzun yoklamaya geçer).
     */
    private suspend fun runSocket(token: String): Boolean = suspendCancellableCoroutine { cont ->
        var opened = false
        val inbox = Channel<List<JSONObject>>(Channel.UNLIMITED)
        lateinit var socket: okhttp3.WebSocket
        val worker = scope.launch {
            for (batch in inbox) {
                processEvents(batch)
                runCatching { socket.send(JSONObject().put("after", lastEventId).toString()) }
            }
        }
        // Dinleme gerekmediğinde (uygulama kapandı, servis durdu) bağlantıyı kapat.
        val watcher = scope.launch {
            while (true) {
                delay(5_000)
                if (!shouldListen() || Bayx.accessToken != token) {
                    runCatching { socket.close(1000, null) }
                    break
                }
            }
        }
        fun finish() {
            watcher.cancel()
            inbox.close()
            if (cont.isActive) cont.resume(opened)
        }
        socket = Api.callSocket(token, lastEventId, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                opened = true
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val array = json.optJSONArray("events") ?: return
                inbox.trySend((0 until array.length()).map { array.getJSONObject(it) })
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) = finish()

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) = finish()
        })
        cont.invokeOnCancellation {
            worker.cancel()
            watcher.cancel()
            runCatching { socket.cancel() }
        }
    }

    private fun startSender() {
        if (senderStarted) return
        senderStarted = true
        scope.launch {
            for (item in outbox) {
                // Kısa bir ağ kesintisinde sinyal kaybolmasın: birkaç kez dene.
                for (attempt in 0 until 4) {
                    val token = Bayx.accessToken ?: break
                    val ok = runCatching { Api.callSend(token, item.to, item.callId, item.type, item.data) }.isSuccess
                    if (ok) break
                    delay(700L * (attempt + 1))
                }
            }
        }
    }

    private fun send(type: String, to: String, callId: String, data: JSONObject = JSONObject()) {
        outbox.trySend(Outgoing(type, to, callId, data))
    }

    // ------------------------------------------------------------ olaylar

    private suspend fun handle(event: JSONObject) {
        val type = event.optString("type")
        val from = event.optString("from")
        val callId = event.optString("call_id")
        val data = event.optJSONObject("data") ?: JSONObject()
        val current = state.value

        if (type == "invite") {
            // Telefon uzun süre çevrimdışı kaldıysa bayat daveti çaldırma.
            if (event.optDouble("age", 0.0) > 40.0) return
            if (current != null && current.phase != CallPhase.ENDED) {
                if (current.callId != callId) send("busy", from, callId)
                return
            }
            val sdp = data.optString("sdp")
            if (sdp.isEmpty()) return
            pendingOffer = sdp
            pendingCandidates.clear()
            earlyCandidates.remove(callId)?.let { pendingCandidates.addAll(it) }
            earlyCandidates.clear()
            remoteDescriptionSet = false
            val name = data.optString("name").ifBlank { from.substringBefore(":").removePrefix("@") }
            val video = data.optBoolean("video")
            state.value = CallState(callId, from, name, video, CallPhase.INCOMING, speaker = false, cameraOn = video, fromMe = false)
            // Arayana "telefonum çalıyor" de: arayan ekranında "Çalıyor…" görünür.
            send("ringing", from, callId)
            ringIncoming(name, video, from, callId)
            ringJob = scope.launch {
                delay(45_000)
                withContext(rtc) {
                    if (state.value?.callId == callId && state.value?.phase == CallPhase.INCOMING) end("Cevapsız arama", notify = false)
                }
            }
            return
        }

        if (current == null || current.callId != callId || current.peerId != from) {
            // Davet henüz gelmeden ulaşan aday: davet gelince kullanılmak üzere sakla.
            if (type == "candidate" && (current == null || current.phase == CallPhase.ENDED)) {
                val list = earlyCandidates.getOrPut(callId) { ArrayList() }
                if (list.size < 50) list.add(candidateOf(data))
                while (earlyCandidates.size > 4) earlyCandidates.remove(earlyCandidates.keys.first())
            }
            return
        }
        when (type) {
            "ringing" -> {
                if (current.phase == CallPhase.OUTGOING && !current.ringing) state.value = current.copy(ringing = true, peerOnline = true)
            }
            "answer" -> {
                if (current.phase != CallPhase.OUTGOING) return
                ringJob?.cancel()
                state.value = current.copy(phase = CallPhase.CONNECTING, ringing = true)
                val peer = pc ?: return
                try {
                    peer.setRemote(SessionDescription(SessionDescription.Type.ANSWER, data.optString("sdp")))
                } catch (e: Exception) {
                    end("Arama bağlanamadı")
                    return
                }
                remoteDescriptionSet = true
                flushCandidates()
                watchConnecting(callId)
            }
            "candidate" -> {
                val candidate = candidateOf(data)
                if (remoteDescriptionSet && pc != null) pc?.addIceCandidate(candidate) else pendingCandidates.add(candidate)
            }
            "reoffer" -> {
                // Arayan, ağ değişince bağlantıyı yeniden kuruyor (ICE yeniden başlatma).
                val peer = pc ?: return
                try {
                    peer.setRemote(SessionDescription(SessionDescription.Type.OFFER, data.optString("sdp")))
                    val answer = peer.createAnswerAwait(constraints(current.video))
                    peer.setLocal(answer)
                    send("reanswer", current.peerId, current.callId, JSONObject().put("sdp", answer.description))
                } catch (ignored: Exception) {
                }
            }
            "reanswer" -> {
                val peer = pc ?: return
                runCatching { peer.setRemote(SessionDescription(SessionDescription.Type.ANSWER, data.optString("sdp"))) }
            }
            "hangup" -> end(if (current.phase == CallPhase.INCOMING) "Cevapsız arama" else "Arama sona erdi", notify = false)
            "reject" -> end("Arama reddedildi", notify = false)
            "busy" -> end("Kişi başka bir görüşmede", notify = false)
        }
    }

    private fun candidateOf(data: JSONObject) =
        IceCandidate(data.optString("sdpMid"), data.optInt("sdpMLineIndex"), data.optString("candidate"))

    private fun flushCandidates() {
        val peer = pc ?: return
        pendingCandidates.forEach { peer.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    /** Cevaplandıktan sonra bağlantı makul sürede kurulamazsa aramayı açıkça bitir (asılı kalmasın). */
    private fun watchConnecting(callId: String) {
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(30_000)
            withContext(rtc) {
                val s = state.value
                if (s?.callId == callId && s.phase == CallPhase.CONNECTING) end("Bağlantı kurulamadı")
            }
        }
    }

    // ------------------------------------------------------- arama başlat

    /** Giden arama. Mikrofon (ve görüntülüyse kamera) izni önceden alınmış olmalı. */
    fun startCall(peerId: String, peerName: String, video: Boolean) {
        if (state.value?.let { it.phase != CallPhase.ENDED } == true) return
        val callId = newCallId()
        val online = PresenceRepo.online.value.contains(peerId)
        val speaker = video && !headsetConnected()
        state.value = CallState(callId, peerId, peerName, video, CallPhase.OUTGOING, speaker = speaker, cameraOn = video, peerOnline = online, fromMe = true)
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
        autoAcceptCallId = null
        val speaker = current.video && !headsetConnected()
        state.value = current.copy(phase = CallPhase.CONNECTING, speaker = speaker)
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
                    watchConnecting(current.callId)
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

    /** Bildirimdeki "Reddet": davet henüz işlenmemiş olsa bile arayana ret gönderir. */
    fun rejectFromNotification(from: String?, callId: String?) {
        Notifier.cancelCall(app)
        stopRinging()
        val current = state.value
        if (current != null && (callId == null || current.callId == callId)) {
            reject()
        } else if (!from.isNullOrBlank() && !callId.isNullOrBlank()) {
            send("reject", from, callId)
        }
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
        applyRoute(speaker)
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
            // TURN bilgisi alınamazsa (ör. kısa kesinti) bir kez daha dene: mobil ağlarda TURN şart.
            var turn: JSONObject? = null
            for (attempt in 0 until 2) {
                turn = runCatching { Api.turn(token) }.getOrNull()
                if ((turn?.optJSONArray("uris")?.length() ?: 0) > 0) break
                delay(600)
            }
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
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Ağ değişince (Wi-Fi ↔ mobil veri) yeni yolları hemen dene.
            iceCandidatePoolSize = 2
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
        startAudio(state.value?.speaker ?: false)
        // Uygulama arka plana geçse de mikrofon/kamera kesilmesin (Android bunu ön plan servisi ister).
        CallService.start(app, video)
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
                        restartJob?.cancel()
                        connectJob?.cancel()
                        if (current.phase != CallPhase.ACTIVE) {
                            state.value = current.copy(phase = CallPhase.ACTIVE, startedAt = System.currentTimeMillis(), reconnecting = false)
                        } else if (current.reconnecting) {
                            state.value = current.copy(reconnecting = false)
                        }
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED,
                    -> onConnectionLost(callId, newState == PeerConnection.PeerConnectionState.FAILED)
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

    /**
     * Bağlantı koptu: hemen kapatmak yerine yeniden kurmayı dene. Arayan taraf ICE'ı yeniden
     * başlatır (aynı anda iki taraf denemesin diye yalnızca o); 30 saniyede dönmezse kapanır.
     */
    private fun onConnectionLost(callId: String, failed: Boolean) {
        val current = state.value ?: return
        if (current.phase != CallPhase.ACTIVE && current.phase != CallPhase.CONNECTING) return
        if (current.phase == CallPhase.ACTIVE && !current.reconnecting) state.value = current.copy(reconnecting = true)
        if (current.fromMe && restartJob?.isActive != true) {
            restartJob = scope.launch {
                // Kısa kopmalar çoğu zaman kendiliğinden düzelir; düzelmezse yeniden başlat.
                delay(if (failed) 0L else 2_500L)
                var tries = 0
                while (tries < 4) {
                    val s = state.value
                    if (s?.callId != callId || s.phase == CallPhase.ENDED) return@launch
                    if (s.phase == CallPhase.ACTIVE && !s.reconnecting) return@launch
                    withContext(rtc) { restartIce(callId) }
                    tries++
                    delay(6_000)
                }
            }
        }
        if (disconnectJob?.isActive != true) {
            disconnectJob = scope.launch {
                delay(30_000)
                withContext(rtc) {
                    val s = state.value
                    if (s?.callId == callId && (s.reconnecting || s.phase == CallPhase.CONNECTING)) end("Bağlantı koptu")
                }
            }
        }
    }

    private suspend fun restartIce(callId: String) {
        val peer = pc ?: return
        val current = state.value ?: return
        if (current.callId != callId) return
        try {
            val c = constraints(current.video).apply {
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
            }
            val offer = peer.createOfferAwait(c)
            peer.setLocal(offer)
            send("reoffer", current.peerId, callId, JSONObject().put("sdp", offer.description))
        } catch (ignored: Exception) {
        }
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
        connectJob?.cancel()
        restartJob?.cancel()
        stopRinging()
        Notifier.cancelCall(app)
        autoAcceptCallId = null
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
        CallService.stop(app)
        if (reason == null) {
            state.value = null
        } else {
            state.value = current.copy(phase = CallPhase.ENDED, endReason = reason, reconnecting = false)
            scope.launch {
                delay(2_500)
                if (state.value?.callId == current.callId && state.value?.phase == CallPhase.ENDED) state.value = null
            }
        }
    }

    // ------------------------------------------------------------- ses

    private fun audioManager() = app.getSystemService(AudioManager::class.java)

    private val headsetTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    ) + (if (Build.VERSION.SDK_INT >= 31) setOf(AudioDeviceInfo.TYPE_BLE_HEADSET) else emptySet())

    /** Kablolu ya da Bluetooth kulaklık bağlı mı? */
    fun headsetConnected(): Boolean {
        val am = audioManager() ?: return false
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes }
    }

    private fun startAudio(speaker: Boolean) {
        val am = audioManager() ?: return
        // Müzik/video çalıyorsa görüşme süresince dursun.
        if (focusRequest == null) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
            runCatching { am.requestAudioFocus(request) }
            focusRequest = request
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        applyRoute(speaker)
        // Görüşme sırasında kulaklık takılır/çıkarılırsa sesi doğru yere yönlendir.
        if (deviceCallback == null) {
            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = reroute(addedDevices, added = true)
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = reroute(removedDevices, added = false)
            }
            runCatching { am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper())) }
            deviceCallback = cb
        }
    }

    private fun reroute(devices: Array<out AudioDeviceInfo>?, added: Boolean) {
        if (devices?.any { it.type in headsetTypes } != true) return
        val current = state.value ?: return
        if (current.phase == CallPhase.ENDED || current.phase == CallPhase.INCOMING) return
        // Kulaklık takılınca hoparlörü kapat; çıkarılınca görüntülüde hoparlöre dön.
        val speaker = if (added) false else current.video
        state.value = current.copy(speaker = speaker)
        applyRoute(speaker)
    }

    /** Sesi yönlendir: hoparlör, yoksa bağlı kulaklık (Bluetooth/kablolu), yoksa ahize. */
    private fun applyRoute(speaker: Boolean) {
        val am = audioManager() ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = am.availableCommunicationDevices
            val target = if (speaker) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            } else {
                val order = listOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                )
                order.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
            }
            if (target != null) runCatching { am.setCommunicationDevice(target) } else runCatching { am.clearCommunicationDevice() }
        } else {
            val bluetooth = !speaker && am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            @Suppress("DEPRECATION")
            run {
                if (bluetooth) {
                    if (!scoStarted) runCatching { am.startBluetoothSco(); scoStarted = true }
                    am.isBluetoothScoOn = true
                } else if (scoStarted) {
                    runCatching { am.stopBluetoothSco() }
                    am.isBluetoothScoOn = false
                    scoStarted = false
                }
                am.isSpeakerphoneOn = speaker
            }
        }
    }

    private fun stopAudio() {
        val am = audioManager() ?: return
        deviceCallback?.let { runCatching { am.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            run {
                if (scoStarted) {
                    runCatching { am.stopBluetoothSco() }
                    am.isBluetoothScoOn = false
                    scoStarted = false
                }
                am.isSpeakerphoneOn = false
            }
        }
        am.mode = AudioManager.MODE_NORMAL
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    /**
     * Gelen arama: uygulama açıksa zil sesini uygulama çalar; kapalıysa sesli, ısrarcı arama
     * bildirimi (kilit ekranında tam ekran) çalar. İkisi aynı anda çalmaz.
     */
    private fun ringIncoming(name: String, video: Boolean, from: String, callId: String) {
        Notifier.cancelCall(app)
        val shown = !foreground && Notifier.showIncomingCall(app, name, video, from, callId)
        if (!shown) startRinging()
    }

    private fun startRinging() {
        stopRinging()
        val am = audioManager()
        val mode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            runCatching {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = RingtoneManager.getRingtone(app, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    if (Build.VERSION.SDK_INT >= 28) isLooping = true
                    play()
                }
            }
        }
        // Sessiz modda titreşim de yok; titreşim ve normal modda titre.
        if (mode != AudioManager.RINGER_MODE_SILENT) {
            runCatching {
                app.getSystemService(Vibrator::class.java)
                    ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700, 500), 0))
            }
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
