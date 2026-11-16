package com.bayx.chat.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * "Kimler bana ulaşabilir": herkes ya da yalnızca kişilerim (arkadaşlar + rehberimde kayıtlı olanlar).
 * Yalnızca kişilerim seçiliyse diğerlerinden gelen sohbet davetleri otomatik açılmaz,
 * "Mesaj istekleri"ne düşer; aramaları sunucu iletmez.
 */
object ReachRepo {
    const val EVERYONE = "herkes"
    const val CONTACTS = "kisiler"

    val reach = MutableStateFlow(EVERYONE)
    val allowed = MutableStateFlow<Set<String>>(emptySet())
    @Volatile private var loadedAt = 0L
    private var app: Context? = null

    /** Son bilinen seçim cihazda saklanır: açılışta sunucu yanıtını beklemeden uygulanır. */
    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        val p = context.getSharedPreferences("tayfa_ulasim", Context.MODE_PRIVATE)
        reach.value = p.getString("kim", EVERYONE) ?: EVERYONE
        allowed.value = p.getStringSet("izinli", emptySet()).orEmpty()
    }

    private fun persist() {
        app?.getSharedPreferences("tayfa_ulasim", Context.MODE_PRIVATE)?.edit()
            ?.putString("kim", reach.value)?.putStringSet("izinli", allowed.value)?.apply()
    }

    suspend fun refresh(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - loadedAt < 60_000) return@withContext
        val token = Bayx.accessToken ?: return@withContext
        runCatching { Api.reachAllowed(token) }.getOrNull()?.let { (r, ids) ->
            reach.value = r
            allowed.value = ids.toSet()
            loadedAt = System.currentTimeMillis()
            persist()
        }
    }

    suspend fun set(value: String): Boolean = withContext(Dispatchers.IO) {
        val token = Bayx.accessToken ?: return@withContext false
        runCatching { Api.setReach(token, value) }.isSuccess.also { if (it) { reach.value = value; loadedAt = 0; persist() } }
    }

    /** Bu kişiden gelen davet doğrudan açılabilir mi? */
    fun accepts(userId: String?): Boolean =
        reach.value != CONTACTS || (userId != null && userId in allowed.value)
}
