package com.bayx.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Member(
    val userId: String,
    val displayName: String,
    val admin: Boolean,
    val pending: Boolean,
    val avatarUrl: String? = null,
    val status: String = "",
    val mode: String = "offline",
)

/** Başkalarının gördüğü durum: online, busy, away ya da offline. */
data class UserPresence(val mode: String, val status: String)

data class PresenceResult(val users: Map<String, UserPresence>, val myMode: String, val myStatus: String)

data class AdminMembers(val members: List<Member>, val count: Int, val max: Int, val invites: Int = 0)

class ApiException(val code: String, message: String) : Exception(message)

/** Bayx kayıt/yönetim servisi ve birkaç doğrudan Matrix çağrısı için küçük HTTP istemcisi. */
object Api {
    const val HOMESERVER = "https://sohbet.acioglu.tr"
    const val SERVER_NAME = "sohbet.acioglu.tr"

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        readTimeoutMs: Int = 20_000,
    ): JSONObject {
        val conn = URL(HOMESERVER + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (status !in 200..299) {
                val code = json.optString("error").ifEmpty { json.optString("errcode") }.ifEmpty { "HTTP_$status" }
                val message = json.optString("message").ifEmpty { "İşlem tamamlanamadı ($status)." }
                throw ApiException(code, message)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            // Teknik ayrıntı sorun giderme içindir; şifre ya da anahtar içermez.
            val detail = (e::class.simpleName ?: "Hata") + ": " + (e.message ?: "").take(250)
            throw ApiException("BAGLANTI", "Sunucuya bağlanılamadı. İnternet bağlantını kontrol et.\n\nAyrıntı: $detail")
        }
    }

    /** Kolay kayıt: kullanıcı adı + şifre (+ isteğe bağlı e-posta). */
    suspend fun registerSimple(username: String, password: String, email: String?): Boolean = io {
        val body = JSONObject().put("username", username).put("password", password)
        if (!email.isNullOrBlank()) body.put("email", email)
        request("POST", "/bayx/v3/register", body).optBoolean("email_pending")
    }

    suspend fun resetRequest(login: String): String = io {
        request("POST", "/bayx/v3/reset/request", JSONObject().put("login", login)).optString("message")
    }

    suspend fun resetConfirm(login: String, code: String, password: String) = io {
        request("POST", "/bayx/v3/reset/confirm", JSONObject().put("login", login).put("code", code).put("password", password))
        Unit
    }

    data class EmailStatus(val email: String, val verified: Boolean, val daysLeft: Int, val mailReady: Boolean)

    private fun emailStatus(json: JSONObject) =
        EmailStatus(json.optString("email"), json.optBoolean("verified"), json.optInt("days_left"), json.optBoolean("mail_ready"))

    suspend fun emailStatus(token: String) = io { emailStatus(request("POST", "/bayx/v3/email/status", JSONObject(), token)) }
    suspend fun emailSet(token: String, email: String) = io { emailStatus(request("POST", "/bayx/v3/email/set", JSONObject().put("email", email), token)) }
    suspend fun emailResend(token: String) = io { emailStatus(request("POST", "/bayx/v3/email/resend", JSONObject(), token)) }

    // ---------------------------------------------------------------- kişiler

    data class Contacts(val friends: List<Member>, val incoming: List<Member>, val outgoing: List<Member>)
    data class ContactFind(val found: Boolean, val userId: String, val username: String, val displayName: String, val relation: String)

    private fun briefList(json: JSONObject, key: String): List<Member> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { i ->
            val m = array.getJSONObject(i)
            Member(
                userId = m.optString("user_id"),
                displayName = m.optString("display_name").ifEmpty { m.optString("username") },
                admin = false,
                pending = false,
                avatarUrl = m.optString("avatar_url").ifEmpty { null },
                status = m.optString("status"),
                mode = m.optString("mode").ifEmpty { "offline" },
            )
        }
    }

    suspend fun contacts(token: String): Contacts = io {
        val json = request("POST", "/bayx/v3/contacts", JSONObject(), token)
        Contacts(briefList(json, "friends"), briefList(json, "incoming"), briefList(json, "outgoing"))
    }

    suspend fun contactFind(token: String, username: String): ContactFind = io {
        val json = request("POST", "/bayx/v3/contacts/find", JSONObject().put("username", username), token)
        ContactFind(
            found = json.optBoolean("found"),
            userId = json.optString("user_id"),
            username = json.optString("username"),
            displayName = json.optString("display_name").ifEmpty { json.optString("username") },
            relation = json.optString("relation", "none"),
        )
    }

    suspend fun contactRequest(token: String, username: String): String = io {
        request("POST", "/bayx/v3/contacts/request", JSONObject().put("username", username), token).optString("relation")
    }
    suspend fun contactAccept(token: String, username: String) = io { request("POST", "/bayx/v3/contacts/accept", JSONObject().put("username", username), token); Unit }
    suspend fun contactReject(token: String, username: String) = io { request("POST", "/bayx/v3/contacts/reject", JSONObject().put("username", username), token); Unit }

    /** Kod ve (varsa) bağlantı döndüren uçlar: web kodu, davet. */
    data class CodeReply(val code: String, val url: String)

    private fun codeReply(json: JSONObject) = CodeReply(json.optString("code"), json.optString("url"))

    suspend fun webCode(token: String) = io { codeReply(request("POST", "/bayx/v2/web/code", JSONObject(), token)) }

    suspend fun webPassword(token: String): String = io {
        request("POST", "/bayx/v2/web/password", JSONObject(), token).optString("password")
    }

    suspend fun createInvite(token: String, uses: Int, days: Int) = io {
        codeReply(request("POST", "/bayx/admin/invite", JSONObject().put("uses", uses).put("days", days), token))
    }

    /** Hesap kullanılabiliyorsa null, değilse Matrix hata kodunu (ör. M_USER_LOCKED) döndürür. */
    suspend fun accountProblem(token: String): String? = io {
        try {
            request("GET", "/_matrix/client/v3/account/whoami", token = token)
            null
        } catch (e: ApiException) {
            e.code
        }
    }

    private fun parseMembers(json: JSONObject): List<Member> {
        val array = json.optJSONArray("members") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val m = array.getJSONObject(i)
            Member(
                userId = m.optString("user_id"),
                displayName = m.optString("display_name").ifEmpty { m.optString("user_id") },
                admin = m.optBoolean("admin"),
                pending = m.optBoolean("pending"),
                avatarUrl = m.optString("avatar_url").ifEmpty { null },
                status = m.optString("status"),
                mode = m.optString("mode").ifEmpty { "offline" },
            )
        }
    }

    suspend fun members(token: String): List<Member> = io {
        parseMembers(request("GET", "/bayx/members", token = token))
    }

    /** Yoklama gönderir ve şu an çevrimiçi olan üyelerin kimliklerini döndürür. */
    suspend fun presence(token: String, active: Boolean, mode: String? = null, status: String? = null): PresenceResult = io {
        val body = JSONObject().put("active", active)
        if (mode != null) body.put("mode", mode)
        if (status != null) body.put("status", status)
        val json = request("POST", "/bayx/presence", body, token)
        val users = HashMap<String, UserPresence>()
        json.optJSONObject("users")?.let { obj ->
            obj.keys().forEach { uid ->
                val p = obj.optJSONObject(uid) ?: return@forEach
                users[uid] = UserPresence(p.optString("mode", "offline"), p.optString("status"))
            }
        }
        val me = json.optJSONObject("me")
        PresenceResult(users, me?.optString("mode", "online") ?: "online", me?.optString("status").orEmpty())
    }

    // ------------------------------------------------------------ arama

    suspend fun callSend(token: String, to: String, callId: String, type: String, data: JSONObject) = io {
        request(
            "POST",
            "/bayx/call/send",
            JSONObject().put("to", to).put("call_id", callId).put("type", type).put("data", data),
            token,
        )
        Unit
    }

    /** Uzun yoklama: sunucu en fazla 25 saniye bekletir. */
    suspend fun callPoll(token: String): List<JSONObject> = io {
        val json = request("POST", "/bayx/call/poll", JSONObject(), token, readTimeoutMs = 40_000)
        val array = json.optJSONArray("events")
        if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }
    }

    suspend fun turn(token: String): JSONObject = io { request("GET", "/bayx/turn", token = token) }

    suspend fun adminMembers(token: String): AdminMembers = io {
        val json = request("GET", "/bayx/admin/members", token = token)
        AdminMembers(parseMembers(json), json.optInt("count"), json.optInt("max", 10), json.optInt("invites"))
    }

    suspend fun adminAction(token: String, action: String, userId: String, newPassword: String? = null) = io {
        val body = JSONObject().put("user_id", userId)
        if (newPassword != null) body.put("new_password", newPassword)
        request("POST", "/bayx/admin/$action", body, token)
        Unit
    }
}
