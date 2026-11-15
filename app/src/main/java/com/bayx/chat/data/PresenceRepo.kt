package com.bayx.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

/** Çevrimiçi durumu: kimin çevrimiçi olduğu, MSN tarzı durumlar ve durum mesajları. */
object PresenceRepo {
    /** Şu an çevrimiçi olan üyelerin kullanıcı kimlikleri. */
    val online = MutableStateFlow<Set<String>>(emptySet())

    /** Üyelerin MSN tarzı durumları ve durum mesajları. */
    val presences = MutableStateFlow<Map<String, UserPresence>>(emptyMap())

    /** Kendi durumum (online, busy, away, invisible) ve durum mesajım. */
    val myMode = MutableStateFlow("online")
    val myStatus = MutableStateFlow("")

    internal fun startPresence() {
        presenceJob?.cancel()
        presenceJob = Bayx.scope.launch {
            while (isActive) {
                val token = Bayx.accessToken
                if (Bayx.foreground && token != null) {
                    runCatching { Api.presence(token, active = true) }.onSuccess { applyPresence(it) }
                }
                delay(30_000)
            }
        }
    }

    // ------------------------------------------------------------- durum

    internal fun applyPresence(result: PresenceResult) {
        presences.value = result.users
        online.value = result.users.filterValues { it.mode != "offline" }.keys
        myMode.value = result.myMode
        myStatus.value = result.myStatus
    }

    /** MSN tarzı durum: online, busy, away ya da invisible (çevrimdışı görün). */
    fun setMode(mode: String) {
        myMode.value = mode
        val token = Bayx.accessToken ?: return
        Bayx.scope.launch { runCatching { Api.presence(token, Bayx.foreground, mode = mode) }.onSuccess { applyPresence(it) } }
    }

    fun setStatusMessage(text: String) {
        myStatus.value = text
        val token = Bayx.accessToken ?: return
        Bayx.scope.launch { runCatching { Api.presence(token, Bayx.foreground, status = text) }.onSuccess { applyPresence(it) } }
    }

    private var presenceJob: Job? = null

    /** Uygulama öne/arkaya geçince sunucuya bildir. */
    internal fun report(active: Boolean) {
        val token = Bayx.accessToken ?: return
        Bayx.scope.launch {
            runCatching { Api.presence(token, active) }.onSuccess { if (active) applyPresence(it) }
        }
    }

    /** Oturum kapanınca. */
    internal fun reset() {
        presenceJob?.cancel()
        presenceJob = null
        online.value = emptySet()
        presences.value = emptyMap()
        myMode.value = "online"
        myStatus.value = ""
    }
}
