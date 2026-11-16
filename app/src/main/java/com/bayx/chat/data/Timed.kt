package com.bayx.chat.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Zamanlı işler (cihazda): ileri tarihli mesaj gönderimi ve "mesajı hatırlat".
 * Mesajlar uçtan uca şifreli olduğu için sunucu gönderemez; zamanı gelince telefon gönderir.
 */
object Timed {
    data class Job(
        val id: Long,
        val kind: String, // "gonder" ya da "hatirlat"
        val roomId: String,
        val roomName: String,
        val text: String,
        val at: Long,
    )

    const val SEND = "gonder"
    const val REMIND = "hatirlat"
    private const val PREFS = "tayfa_zamanli"
    private lateinit var app: Context
    val jobs = MutableStateFlow<List<Job>>(emptyList())

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        jobs.value = load()
        // Telefon yeniden başladıysa ya da zamanı geçmiş iş varsa yeniden kur.
        jobs.value.forEach { arm(it) }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(): List<Job> = runCatching {
        val arr = JSONArray(prefs().getString("isler", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Job(o.getLong("id"), o.getString("k"), o.getString("r"), o.optString("n"), o.getString("t"), o.getLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Job>) {
        jobs.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("k", it.kind).put("r", it.roomId).put("n", it.roomName).put("t", it.text).put("at", it.at)) }
        prefs().edit().putString("isler", arr.toString()).apply()
    }

    fun add(kind: String, roomId: String, roomName: String, text: String, at: Long): Job {
        val job = Job(System.currentTimeMillis(), kind, roomId, roomName, text.take(4000), at)
        save(jobs.value + job)
        arm(job)
        return job
    }

    fun cancel(id: Long) {
        val job = jobs.value.firstOrNull { it.id == id } ?: return
        runCatching { alarm().cancel(pending(job)) }
        save(jobs.value.filterNot { it.id == id })
    }

    fun forRoom(roomId: String, kind: String) = jobs.value.filter { it.roomId == roomId && it.kind == kind }

    internal fun take(id: Long): Job? {
        val job = jobs.value.firstOrNull { it.id == id } ?: return null
        save(jobs.value.filterNot { it.id == id })
        return job
    }

    private fun alarm() = app.getSystemService(AlarmManager::class.java)

    private fun pending(job: Job): PendingIntent {
        val intent = Intent(app, TimedReceiver::class.java).setAction("com.bayx.chat.ZAMANLI").putExtra("id", job.id)
        return PendingIntent.getBroadcast(app, (job.id % Int.MAX_VALUE).toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun arm(job: Job) {
        val at = job.at.coerceAtLeast(System.currentTimeMillis() + 2_000)
        val am = alarm() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(job))
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(job))
            }
        }
    }
}

/** Zamanı gelen işi yapar: mesajı gönderir ya da hatırlatma bildirimi gösterir. */
class TimedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.MY_PACKAGE_REPLACED") {
            Timed.init(context)
            return
        }
        Timed.init(context)
        val job = Timed.take(intent.getLongExtra("id", -1)) ?: return
        if (job.kind == Timed.REMIND) {
            Notifier.showReminder(context, job.roomId, job.roomName, job.text, job.id)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var ok = false
            try {
                ok = withTimeoutOrNull(40_000) {
                    val state = Bayx.auth.first { it is AuthState.LoggedIn || it is AuthState.LoggedOut || it is AuthState.Offline }
                    if (state is AuthState.LoggedOut) return@withTimeoutOrNull false
                    var session: ChatSession? = null
                    repeat(15) {
                        session = Bayx.openChat(job.roomId)
                        if (session != null) return@repeat
                        delay(1000)
                    }
                    val s = session ?: return@withTimeoutOrNull false
                    try {
                        Bayx.sendText(s, job.text)
                    } finally {
                        delay(1500)
                        Bayx.closeChat(s)
                    }
                } ?: false
            } finally {
                if (!ok) Notifier.showScheduledFailed(context, job.roomId, job.roomName)
                pending.finish()
            }
        }
    }
}
