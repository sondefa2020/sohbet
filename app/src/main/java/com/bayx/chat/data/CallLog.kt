package com.bayx.chat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Tek bir arama geçmişi kaydı. */
data class CallEntry(
    val peerId: String,
    val peerName: String,
    val outgoing: Boolean,
    val answered: Boolean,
    val video: Boolean,
    val time: Long,
)

/** Yerel, cihazda tutulan arama geçmişi (sunucuya gitmez). */
object CallLog {
    private const val PREF = "tayfa_aramalar"
    private const val KEY = "kayitlar"
    private const val MAX = 200

    private lateinit var app: Context
    val entries = MutableStateFlow<List<CallEntry>>(emptyList())

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        entries.value = load()
    }

    /** Biten bir aramayı kaydeder (en yeni başta). */
    fun record(peerId: String, peerName: String, outgoing: Boolean, answered: Boolean, video: Boolean) {
        if (!::app.isInitialized || peerId.isBlank()) return
        val entry = CallEntry(peerId, peerName, outgoing, answered, video, System.currentTimeMillis())
        val list = (listOf(entry) + entries.value).take(MAX)
        entries.value = list
        save(list)
    }

    fun clear() {
        if (!::app.isInitialized) return
        entries.value = emptyList()
        prefs().edit().remove(KEY).apply()
    }

    private fun prefs() = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun load(): List<CallEntry> = runCatching {
        val raw = prefs().getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CallEntry(
                peerId = o.getString("peerId"),
                peerName = o.optString("peerName"),
                outgoing = o.optBoolean("outgoing"),
                answered = o.optBoolean("answered"),
                video = o.optBoolean("video"),
                time = o.optLong("time"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<CallEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("peerId", e.peerId)
                    .put("peerName", e.peerName)
                    .put("outgoing", e.outgoing)
                    .put("answered", e.answered)
                    .put("video", e.video)
                    .put("time", e.time),
            )
        }
        prefs().edit().putString(KEY, arr.toString()).apply()
    }
}
