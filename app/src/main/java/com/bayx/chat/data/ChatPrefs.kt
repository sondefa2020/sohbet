package com.bayx.chat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** Sohbet başına yerel ayarlar: sabitle, sessize al, arşivle. Cihazda tutulur. */
data class ChatFlags(
    val pinned: Boolean = false,
    val muted: Boolean = false,
    val archived: Boolean = false,
)

object ChatPrefs {
    private const val PREF = "tayfa_sohbet_ayar"
    private const val KEY = "bayraklar"

    private lateinit var app: Context
    val state = MutableStateFlow<Map<String, ChatFlags>>(emptyMap())

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        state.value = load()
    }

    fun flags(roomId: String): ChatFlags = state.value[roomId] ?: ChatFlags()

    fun isMuted(roomId: String): Boolean = flags(roomId).muted

    fun togglePin(roomId: String) = update(roomId) { it.copy(pinned = !it.pinned) }
    fun toggleMute(roomId: String) = update(roomId) { it.copy(muted = !it.muted) }

    /** Arşivle/çıkar. Arşivlenince sabitleme kaldırılır (ikisi birlikte anlamsız). */
    fun toggleArchive(roomId: String) = update(roomId) {
        val archived = !it.archived
        it.copy(archived = archived, pinned = if (archived) false else it.pinned)
    }

    private fun update(roomId: String, change: (ChatFlags) -> ChatFlags) {
        if (!::app.isInitialized) return
        val current = state.value[roomId] ?: ChatFlags()
        val next = change(current)
        val map = state.value.toMutableMap()
        if (next == ChatFlags()) map.remove(roomId) else map[roomId] = next
        state.value = map
        save(map)
    }

    private fun prefs() = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun load(): Map<String, ChatFlags> = runCatching {
        val raw = prefs().getString(KEY, null) ?: return emptyMap()
        val o = JSONObject(raw)
        val map = mutableMapOf<String, ChatFlags>()
        o.keys().forEach { k ->
            val f = o.getJSONObject(k)
            map[k] = ChatFlags(f.optBoolean("p"), f.optBoolean("m"), f.optBoolean("a"))
        }
        map
    }.getOrDefault(emptyMap())

    private fun save(map: Map<String, ChatFlags>) {
        val o = JSONObject()
        map.forEach { (k, f) ->
            o.put(k, JSONObject().put("p", f.pinned).put("m", f.muted).put("a", f.archived))
        }
        prefs().edit().putString(KEY, o.toString()).apply()
    }
}
