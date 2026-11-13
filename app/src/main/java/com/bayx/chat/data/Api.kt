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

    /** Şifresiz kayıt / giriş yanıtı. status: ok, pending ya da rejected. */
    data class AuthReply(
        val status: String,
        val username: String = "",
        val otp: String = "",
        val recoveryCode: String? = null,
        val ticket: String? = null,
    )

    private fun authReply(json: JSONObject) = AuthReply(
        status = json.optString("status"),
        username = json.optString("username"),
        otp = json.optString("otp"),
        recoveryCode = json.optString("recovery_code").ifEmpty { null },
        ticket = json.optString("ticket").ifEmpty { null },
    )

    suspend fun register(username: String, displayName: String, invite: String?) = io {
        val body = JSONObject().put("username", username).put("display_name", displayName)
        if (!invite.isNullOrBlank()) body.put("invite", invite)
        authReply(request("POST", "/bayx/v2/register", body))
    }

    suspend fun ticket(ticket: String) = io { authReply(request("POST", "/bayx/v2/ticket", JSONObject().put("ticket", ticket))) }

    suspend fun recover(username: String, code: String) = io {
        authReply(request("POST", "/bayx/v2/recover", JSONObject().put("username", username).put("recovery_code", code)))
    }

    suspend fun redeemLink(code: String) = io { authReply(request("POST", "/bayx/v2/link/redeem", JSONObject().put("code", code))) }

    /** Kod ve (varsa) bağlantı döndüren uçlar: giriş kodu, web kodu, davet. */
    data class CodeReply(val code: String, val url: String)

    private fun codeReply(json: JSONObject) = CodeReply(json.optString("code"), json.optString("url"))

    suspend fun newLoginLink(token: String) = io { codeReply(request("POST", "/bayx/v2/link/new", JSONObject(), token)) }

    suspend fun webCode(token: String) = io { codeReply(request("POST", "/bayx/v2/web/code", JSONObject(), token)) }

    suspend fun newRecovery(token: String): String = io {
        request("POST", "/bayx/v2/recovery/new", JSONObject(), token).optString("recovery_code")
    }

    suspend fun seal(token: String) = io { request("POST", "/bayx/v2/seal", JSONObject(), token); Unit }

    suspend fun createInvite(token: String, uses: Int, days: Int) = io {
        codeReply(request("POST", "/bayx/admin/invite", JSONObject().put("uses", uses).put("days", days), token))
    }

    suspend fun adminLoginLink(token: String, userId: String) = io {
        codeReply(request("POST", "/bayx/admin/login-link", JSONObject().put("user_id", userId), token))
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

    suspend fun adminAction(token: String, action: String, userId: String) = io {
        val body = JSONObject().put("user_id", userId)
        request("POST", "/bayx/admin/$action", body, token)
        Unit
    }
}
