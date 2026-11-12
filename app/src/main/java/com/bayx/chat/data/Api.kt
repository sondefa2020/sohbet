package com.bayx.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Member(val userId: String, val displayName: String, val admin: Boolean, val pending: Boolean)

data class AdminMembers(val members: List<Member>, val count: Int, val max: Int)

class ApiException(val code: String, message: String) : Exception(message)

/** Bayx kayıt/yönetim servisi ve birkaç doğrudan Matrix çağrısı için küçük HTTP istemcisi. */
object Api {
    const val HOMESERVER = "https://sohbet.acioglu.tr"
    const val SERVER_NAME = "sohbet.acioglu.tr"

    private fun request(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        val conn = URL(HOMESERVER + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
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
            throw ApiException("BAGLANTI", "Sunucuya bağlanılamadı. İnternet bağlantını kontrol et.")
        }
    }

    suspend fun register(username: String, password: String, displayName: String) = io {
        request(
            "POST",
            "/bayx/register",
            JSONObject().put("username", username).put("password", password).put("display_name", displayName),
        )
        Unit
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
            )
        }
    }

    suspend fun members(token: String): List<Member> = io {
        parseMembers(request("GET", "/bayx/members", token = token))
    }

    suspend fun adminMembers(token: String): AdminMembers = io {
        val json = request("GET", "/bayx/admin/members", token = token)
        AdminMembers(parseMembers(json), json.optInt("count"), json.optInt("max", 10))
    }

    suspend fun adminAction(token: String, action: String, userId: String, newPassword: String? = null) = io {
        val body = JSONObject().put("user_id", userId)
        if (newPassword != null) body.put("new_password", newPassword)
        request("POST", "/bayx/admin/$action", body, token)
        Unit
    }
}
