package com.bayx.chat.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Cihaz rehberi (WhatsApp mantığı): rehberdeki numaralardan Tayfa kullananlar bulunur.
 * Numaralar sunucuya açık gönderilmez; yalnızca özetleri (SHA-256) gönderilir. Sohbet listesinde
 * kişiler rehberdeki adlarıyla görünür.
 */
object PhonebookRepo {
    data class Entry(
        val userId: String,
        /** Rehberde kayıtlı ad. */
        val name: String,
        val phone: String,
        val avatarUrl: String?,
        val status: String,
    )

    val entries = MutableStateFlow<List<Entry>>(emptyList())
    val syncing = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /** Rehberde olup Tayfa kullanmayan kişi sayısı (davet önerisi için). */
    val notOnTayfa = MutableStateFlow(0)

    private const val PREFS = "tayfa_rehber"
    private const val SALT = "tayfa-rehber-v1:"
    private const val SYNC_EVERY_MS = 6 * 60 * 60 * 1000L

    private val byUser = HashMap<String, Entry>()

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Rehberdeki adı (yoksa null): sohbet listesinde ve sohbet başlığında kullanılır. */
    fun nameFor(userId: String?): String? = userId?.let { synchronized(byUser) { byUser[it]?.name } }

    /** Bu kişinin numarası rehberimde kayıtlı mı (arama/mesaj izni). */
    fun isContact(userId: String?): Boolean = userId != null && synchronized(byUser) { byUser.containsKey(userId) }

    /** Numarayı ülke koduyla, yalnızca rakam olarak döndürür (sunucudaki kuralla aynı). */
    fun normalize(raw: String, country: String = "90"): String? {
        val trimmed = raw.trim()
        var digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        when {
            trimmed.startsWith("+") -> Unit
            digits.startsWith("00") -> digits = digits.substring(2)
            country == "90" && digits.length == 11 && digits.startsWith("0") -> digits = "90" + digits.substring(1)
            country == "90" && digits.length == 10 && digits.startsWith("5") -> digits = "90$digits"
            digits.startsWith("0") -> digits = country + digits.substring(1)
            !digits.startsWith(country) -> digits = country + digits
        }
        if (digits.startsWith("90") && !Regex("^905\\d{9}$").matches(digits)) return null
        if (digits.length !in 8..15) return null
        return digits
    }

    fun hash(phone: String): String =
        MessageDigest.getInstance("SHA-256").digest((SALT + phone).toByteArray()).joinToString("") { "%02x".format(it) }

    /** Açılışta: önceki eşleşmeleri hemen göster, gerekiyorsa arka planda yenile. */
    suspend fun start(context: Context) {
        load(context)
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("son", 0L)
        if (hasPermission(context) && System.currentTimeMillis() - last > SYNC_EVERY_MS) sync(context)
    }

    /** Rehberi okuyup sunucuyla eşleştirir. */
    suspend fun sync(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission(context) || syncing.value) return@withContext false
        val token = Bayx.accessToken ?: return@withContext false
        syncing.value = true
        error.value = null
        try {
            val book = readPhonebook(context)
            val hashes = book.keys.associateBy { hash(it) }
            val matches = Api.contactsMatch(token, hashes.keys.take(5000))
            val list = matches.mapNotNull { m ->
                val phone = hashes[m.hash] ?: return@mapNotNull null
                Entry(m.userId, book[phone] ?: m.displayName, phone, m.avatarUrl, m.status)
            }.distinctBy { it.userId }.sortedBy { it.name.lowercase() }
            apply(list)
            notOnTayfa.value = (book.size - list.size).coerceAtLeast(0)
            save(context, list)
            true
        } catch (e: ApiException) {
            error.value = e.message
            false
        } catch (e: Exception) {
            error.value = "Rehber eşleştirilemedi."
            false
        } finally {
            syncing.value = false
        }
    }

    private fun readPhonebook(context: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
            val n = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val d = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            while (c.moveToNext()) {
                val phone = normalize(c.getString(n) ?: continue) ?: continue
                if (!out.containsKey(phone)) out[phone] = c.getString(d)?.trim().orEmpty().ifEmpty { "+$phone" }
            }
        }
        return out
    }

    private fun apply(list: List<Entry>) {
        synchronized(byUser) {
            byUser.clear()
            list.forEach { byUser[it.userId] = it }
        }
        entries.value = list
        runCatching { Bayx.refreshNames() }
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("u", it.userId).put("n", it.name).put("p", it.phone).put("a", it.avatarUrl ?: "").put("s", it.status))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("liste", arr.toString())
            .putLong("son", System.currentTimeMillis())
            .apply()
    }

    private fun load(context: Context) {
        if (entries.value.isNotEmpty()) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("liste", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(o.getString("u"), o.getString("n"), o.getString("p"), o.optString("a").ifEmpty { null }, o.optString("s"))
            }
            apply(list)
        }
    }

    /** Oturum kapanınca: bu cihazdaki eşleşmeler silinir. */
    fun reset(context: Context) {
        synchronized(byUser) { byUser.clear() }
        entries.value = emptyList()
        notOnTayfa.value = 0
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
