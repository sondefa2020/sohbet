package com.bayx.chat.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Yıldızlı mesajlar (WhatsApp'taki gibi): önemli mesajları işaretle, tek ekranda bul. Cihazda tutulur. */
object Starred {
    data class Item(val roomId: String, val key: String, val text: String, val sender: String, val time: Long, val roomName: String)

    private lateinit var app: Context
    val items = MutableStateFlow<List<Item>>(emptyList())

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        items.value = runCatching {
            val arr = JSONArray(prefs().getString("liste", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Item(o.getString("r"), o.getString("k"), o.optString("t"), o.optString("s"), o.optLong("z"), o.optString("n"))
            }
        }.getOrDefault(emptyList())
    }

    private fun prefs() = app.getSharedPreferences("tayfa_yildizli", Context.MODE_PRIVATE)

    private fun save(list: List<Item>) {
        items.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("r", it.roomId).put("k", it.key).put("t", it.text).put("s", it.sender).put("z", it.time).put("n", it.roomName)) }
        prefs().edit().putString("liste", arr.toString()).apply()
    }

    fun isStarred(key: String) = items.value.any { it.key == key }

    fun toggle(item: Item) {
        if (!::app.isInitialized) return
        save(if (isStarred(item.key)) items.value.filterNot { it.key == item.key } else listOf(item) + items.value)
    }

    fun clear() { if (::app.isInitialized) save(emptyList()) }
}

/** Yarım kalan mesajlar (taslak): sohbetten çıkınca kaybolmaz, listede "Taslak:" diye görünür. */
object Drafts {
    private lateinit var app: Context
    val map = MutableStateFlow<Map<String, String>>(emptyMap())

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val o = runCatching { JSONObject(prefs().getString("taslak", "{}")) }.getOrDefault(JSONObject())
        map.value = o.keys().asSequence().associateWith { o.optString(it) }
    }

    private fun prefs() = app.getSharedPreferences("tayfa_taslak", Context.MODE_PRIVATE)

    fun get(roomId: String): String = map.value[roomId].orEmpty()

    fun set(roomId: String, text: String) {
        if (!::app.isInitialized) return
        val next = if (text.isBlank()) map.value - roomId else map.value + (roomId to text.take(4000))
        if (next == map.value) return
        map.value = next
        prefs().edit().putString("taslak", JSONObject(next).toString()).apply()
    }

    fun clear() { if (::app.isInitialized) { map.value = emptyMap(); prefs().edit().clear().apply() } }
}

/** Kişiye özel not: yalnızca senin gördüğün kısa not (ör. "Muhasebeci, salı günleri arar"). */
object ContactNotes {
    private lateinit var app: Context
    val map = MutableStateFlow<Map<String, String>>(emptyMap())

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val o = runCatching { JSONObject(prefs().getString("notlar", "{}")) }.getOrDefault(JSONObject())
        map.value = o.keys().asSequence().associateWith { o.optString(it) }
    }

    private fun prefs() = app.getSharedPreferences("tayfa_kisi_notlari", Context.MODE_PRIVATE)

    fun set(userId: String, note: String) {
        if (!::app.isInitialized) return
        val next = if (note.isBlank()) map.value - userId else map.value + (userId to note.take(300))
        map.value = next
        prefs().edit().putString("notlar", JSONObject(next).toString()).apply()
    }

    fun clear() { if (::app.isInitialized) { map.value = emptyMap(); prefs().edit().clear().apply() } }
}
