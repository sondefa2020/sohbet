package com.bayx.chat.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

// ------------------------------------------------------------- sunucu modelleri
// Sunucunun JSON yanıtları; alan adları sunucudakiyle birebir. Bilinmeyen alanlar yok sayılır,
// eksik alanlar varsayılan değeri alır: sunucu yeni alan eklese de uygulama bozulmaz.

@Serializable
private data class ErrorDto(val error: String = "", val errcode: String = "", val message: String = "")

@Serializable
private data class MemberDto(
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    val admin: Boolean = false,
    val pending: Boolean = false,
    @SerialName("avatar_url") val avatarUrl: String = "",
    val status: String = "",
    val mode: String = "",
) {
    fun toMember(fallbackName: String) = Member(
        userId = userId,
        displayName = displayName.ifEmpty { fallbackName },
        admin = admin,
        pending = pending,
        avatarUrl = avatarUrl.ifEmpty { null },
        status = status,
        mode = mode.ifEmpty { "offline" },
    )
}

@Serializable private data class RegisterReply(@SerialName("email_pending") val emailPending: Boolean = false)
@Serializable private data class MessageReply(val message: String = "")
@Serializable private data class IdReply(val id: String = "")
@Serializable private data class RelationReply(val relation: String = "")
@Serializable private data class PasswordReply(val password: String = "")

@Serializable
private data class EmailDto(
    val email: String = "",
    val verified: Boolean = false,
    @SerialName("days_left") val daysLeft: Int = 0,
    @SerialName("mail_ready") val mailReady: Boolean = false,
)

@Serializable
private data class ContactsDto(
    val friends: List<MemberDto> = emptyList(),
    val incoming: List<MemberDto> = emptyList(),
    val outgoing: List<MemberDto> = emptyList(),
    val blocked: List<MemberDto> = emptyList(),
)

@Serializable
private data class FindDto(
    val found: Boolean = false,
    @SerialName("user_id") val userId: String = "",
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    val relation: String = "none",
)

@Serializable private data class ViewerDto(@SerialName("user_id") val userId: String = "", val name: String = "")

@Serializable
private data class StatusItemDto(
    val id: String = "",
    val type: String = "text",
    val text: String = "",
    val bg: String = "",
    val media: String = "",
    val time: Long = 0,
    val seen: Boolean = false,
    val key: String = "",
    val viewers: List<ViewerDto> = emptyList(),
)

@Serializable
private data class StatusUserDto(
    @SerialName("user_id") val userId: String = "",
    val name: String = "",
    val items: List<StatusItemDto> = emptyList(),
    @SerialName("last_time") val lastTime: Long = 0,
    @SerialName("all_seen") val allSeen: Boolean = false,
)

@Serializable private data class StatusListDto(val mine: StatusUserDto? = null, val friends: List<StatusUserDto> = emptyList())
@Serializable private data class CodeDto(val code: String = "", val url: String = "")

@Serializable
private data class ConfigDto(
    val announcement: String = "",
    @SerialName("latest_version") val latestVersion: Int = 0,
    @SerialName("apk_url") val apkUrl: String = "",
)

@Serializable private data class MembersDto(val members: List<MemberDto> = emptyList(), val count: Int = 0, val max: Int = 10, val invites: Int = 0)
@Serializable private data class PresenceUserDto(val mode: String = "offline", val status: String = "")
@Serializable private data class PresenceMeDto(val mode: String = "online", val status: String = "")

@Serializable
private data class PresenceDto(
    val users: Map<String, PresenceUserDto> = emptyMap(),
    val me: PresenceMeDto = PresenceMeDto(),
)

@Serializable private data class CallEventsDto(val events: List<JsonObject> = emptyList())

/** Tayfa kayıt/yönetim servisi ve birkaç doğrudan Matrix çağrısı için HTTP istemcisi. */
object Api {
    const val HOMESERVER = "https://sohbet.acioglu.tr"
    const val SERVER_NAME = "sohbet.acioglu.tr"
    /** Tayfa Web'in adresi (bilgisayardan QR ile giriş). */
    const val WEB_ADDRESS = "web.acioglu.tr"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val jsonType = "application/json".toMediaType()

    /** Tek, paylaşılan istemci: bağlantılar yeniden kullanılır (her istekte yeni TLS el sıkışması yok). */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Uzun yoklama için daha uzun okuma süresi; aynı bağlantı havuzunu paylaşır. */
    private val longPoll: OkHttpClient = http.newBuilder().readTimeout(40, TimeUnit.SECONDS).build()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCompleted) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    /** İsteği gönderir; başarılıysa gövdeyi metin olarak, değilse anlamlı bir [ApiException] döndürür. */
    private suspend fun send(
        method: String,
        path: String,
        body: String? = null,
        token: String? = null,
        client: OkHttpClient = http,
    ): String {
        val builder = Request.Builder().url(HOMESERVER + path).header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        val requestBody = (body ?: if (method == "POST") "{}" else null)?.toRequestBody(jsonType)
        builder.method(method, requestBody)
        val response = try {
            client.newCall(builder.build()).await()
        } catch (e: IOException) {
            // Teknik ayrıntı sorun giderme içindir; şifre ya da anahtar içermez.
            val detail = (e::class.simpleName ?: "Hata") + ": " + (e.message ?: "").take(250)
            throw ApiException("BAGLANTI", "Sunucuya bağlanılamadı. İnternet bağlantını kontrol et.\n\nAyrıntı: $detail")
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val err = runCatching { json.decodeFromString<ErrorDto>(text) }.getOrDefault(ErrorDto())
                val code = err.error.ifEmpty { err.errcode }.ifEmpty { "HTTP_${r.code}" }
                val message = err.message.ifEmpty { "İşlem tamamlanamadı (${r.code})." }
                throw ApiException(code, message)
            }
            return text
        }
    }

    private suspend inline fun <reified T> get(path: String, token: String? = null): T =
        decode(send("GET", path, token = token))

    private suspend inline fun <reified T> post(path: String, body: JsonObject? = null, token: String? = null, client: OkHttpClient = http): T =
        decode(send("POST", path, body?.toString(), token, client))

    private inline fun <reified T> decode(text: String): T {
        val serializer: KSerializer<T> = serializer()
        return try {
            json.decodeFromString(serializer, text.ifBlank { "{}" })
        } catch (e: Exception) {
            throw ApiException("YANIT", "Sunucudan beklenmeyen bir yanıt geldi.")
        }
    }

    private suspend fun postUnit(path: String, body: JsonObject? = null, token: String? = null) {
        send("POST", path, body?.toString(), token)
    }

    // ---------------------------------------------------------------- hesap

    /** Kolay kayıt: kullanıcı adı + şifre (+ isteğe bağlı e-posta). */
    suspend fun registerSimple(username: String, password: String, email: String?): Boolean =
        post<RegisterReply>("/bayx/v3/register", buildJsonObject {
            put("username", username)
            put("password", password)
            if (!email.isNullOrBlank()) put("email", email)
        }).emailPending

    suspend fun resetRequest(login: String): String =
        post<MessageReply>("/bayx/v3/reset/request", buildJsonObject { put("login", login) }).message

    suspend fun resetConfirm(login: String, code: String, password: String) =
        postUnit("/bayx/v3/reset/confirm", buildJsonObject {
            put("login", login)
            put("code", code)
            put("password", password)
        })

    data class EmailStatus(val email: String, val verified: Boolean, val daysLeft: Int, val mailReady: Boolean)

    private fun EmailDto.toStatus() = EmailStatus(email, verified, daysLeft, mailReady)

    suspend fun emailStatus(token: String) = post<EmailDto>("/bayx/v3/email/status", token = token).toStatus()
    suspend fun emailSet(token: String, email: String) =
        post<EmailDto>("/bayx/v3/email/set", buildJsonObject { put("email", email) }, token).toStatus()
    suspend fun emailResend(token: String) = post<EmailDto>("/bayx/v3/email/resend", token = token).toStatus()

    // ---------------------------------------------------------------- kişiler

    data class Contacts(val friends: List<Member>, val incoming: List<Member>, val outgoing: List<Member>, val blocked: List<Member> = emptyList())
    data class ContactFind(val found: Boolean, val userId: String, val username: String, val displayName: String, val relation: String)

    private fun List<MemberDto>.brief() = map { it.toMember(it.username) }

    suspend fun contacts(token: String): Contacts {
        val dto = post<ContactsDto>("/bayx/v3/contacts", token = token)
        return Contacts(dto.friends.brief(), dto.incoming.brief(), dto.outgoing.brief(), dto.blocked.brief())
    }

    suspend fun contactFind(token: String, username: String): ContactFind {
        val dto = post<FindDto>("/bayx/v3/contacts/find", buildJsonObject { put("username", username) }, token)
        return ContactFind(dto.found, dto.userId, dto.username, dto.displayName.ifEmpty { dto.username }, dto.relation)
    }

    private fun userBody(username: String) = buildJsonObject { put("username", username) }

    suspend fun contactRequest(token: String, username: String): String =
        post<RelationReply>("/bayx/v3/contacts/request", userBody(username), token).relation
    suspend fun contactAccept(token: String, username: String) = postUnit("/bayx/v3/contacts/accept", userBody(username), token)
    suspend fun contactReject(token: String, username: String) = postUnit("/bayx/v3/contacts/reject", userBody(username), token)
    suspend fun contactBlock(token: String, username: String) = postUnit("/bayx/v3/contacts/block", userBody(username), token)
    suspend fun contactUnblock(token: String, username: String) = postUnit("/bayx/v3/contacts/unblock", userBody(username), token)

    // ---------------------------------------------------------- durum / hikâye

    data class StatusItem(
        val id: String,
        val type: String,
        val text: String,
        val bg: String,
        val media: String,
        val time: Long,
        val seen: Boolean,
        val key: String = "",
        /** Yalnızca kendi durumumda dolu: (kullanıcı kimliği, ad). */
        val viewers: List<Pair<String, String>> = emptyList(),
    )
    data class StatusUser(
        val userId: String,
        val name: String,
        val items: List<StatusItem>,
        val lastTime: Long,
        val allSeen: Boolean,
    )
    data class StatusList(val mine: StatusUser?, val friends: List<StatusUser>)

    private fun StatusUserDto.toUser(): StatusUser? {
        val list = items.map {
            StatusItem(it.id, it.type, it.text, it.bg, it.media, it.time, it.seen, it.key, it.viewers.map { v -> v.userId to v.name })
        }
        if (list.isEmpty()) return null
        return StatusUser(userId, name, list, lastTime, allSeen)
    }

    suspend fun statusList(token: String): StatusList {
        val dto = post<StatusListDto>("/bayx/v3/status/list", token = token)
        return StatusList(dto.mine?.toUser(), dto.friends.mapNotNull { it.toUser() })
    }

    suspend fun statusAdd(token: String, text: String, bg: String): String =
        post<IdReply>("/bayx/v3/status/add", buildJsonObject {
            put("type", "text")
            put("text", text)
            put("bg", bg)
        }, token).id

    suspend fun statusAddImage(token: String, media: String, caption: String, key: String): String =
        post<IdReply>("/bayx/v3/status/add", buildJsonObject {
            put("type", "image")
            put("media", media)
            put("text", caption)
            put("bg", "#14110F")
            put("key", key)
        }, token).id

    suspend fun statusDelete(token: String, id: String) =
        postUnit("/bayx/v3/status/delete", buildJsonObject { put("id", id) }, token)

    suspend fun statusSeen(token: String, owner: String, id: String) =
        postUnit("/bayx/v3/status/seen", buildJsonObject {
            put("owner", owner)
            put("id", id)
        }, token)

    // ------------------------------------------------------- web ve davetler

    /** Kod ve (varsa) bağlantı döndüren uçlar: web kodu, davet. */
    data class CodeReply(val code: String, val url: String)

    private fun CodeDto.toReply() = CodeReply(code, url)

    suspend fun webCode(token: String) = post<CodeDto>("/bayx/v2/web/code", token = token).toReply()

    suspend fun webPassword(token: String): String = post<PasswordReply>("/bayx/v2/web/password", token = token).password

    /** WhatsApp Web tarzı: telefon, web'deki QR'ı (pairing_id) ya da 6 haneli kodu onaylar. */
    suspend fun webApprove(token: String, pairingId: String?, code: String?) =
        postUnit("/bayx/v3/web/approve", buildJsonObject {
            if (!pairingId.isNullOrBlank()) put("pairing_id", pairingId)
            if (!code.isNullOrBlank()) put("code", code)
        }, token)

    // ------------------------------------------------- uygulama ayarı (modüler)

    data class AppConfig(val announcement: String, val latestVersion: Int, val apkUrl: String)

    private fun ConfigDto.toConfig() = AppConfig(announcement, latestVersion, apkUrl)

    suspend fun getConfig(token: String) = post<ConfigDto>("/bayx/config", token = token).toConfig()

    suspend fun setConfig(token: String, announcement: String, latestVersion: Int, apkUrl: String) =
        post<ConfigDto>("/bayx/admin/config", buildJsonObject {
            put("announcement", announcement)
            put("latest_version", latestVersion)
            put("apk_url", apkUrl)
        }, token).toConfig()

    suspend fun createInvite(token: String, uses: Int, days: Int) =
        post<CodeDto>("/bayx/admin/invite", buildJsonObject {
            put("uses", uses)
            put("days", days)
        }, token).toReply()

    /** Hesap kullanılabiliyorsa null, değilse Matrix hata kodunu (ör. M_USER_LOCKED) döndürür. */
    suspend fun accountProblem(token: String): String? = try {
        send("GET", "/_matrix/client/v3/account/whoami", token = token)
        null
    } catch (e: ApiException) {
        e.code
    }

    // ---------------------------------------------------------- üyeler, durum

    suspend fun members(token: String): List<Member> =
        get<MembersDto>("/bayx/members", token).members.map { it.toMember(it.userId) }

    /** Yoklama gönderir ve şu an çevrimiçi olan üyelerin durumlarını döndürür. */
    suspend fun presence(token: String, active: Boolean, mode: String? = null, status: String? = null): PresenceResult {
        val dto = post<PresenceDto>("/bayx/presence", buildJsonObject {
            put("active", active)
            if (mode != null) put("mode", mode)
            if (status != null) put("status", status)
        }, token)
        return PresenceResult(dto.users.mapValues { UserPresence(it.value.mode, it.value.status) }, dto.me.mode, dto.me.status)
    }

    // ------------------------------------------------------------ arama

    suspend fun callSend(token: String, to: String, callId: String, type: String, data: JSONObject) {
        // SDP/ICE içeriği WebRTC'den org.json olarak geliyor; olduğu gibi gömülür.
        val body = "{\"to\":${JSONObject.quote(to)},\"call_id\":${JSONObject.quote(callId)}," +
            "\"type\":${JSONObject.quote(type)},\"data\":$data}"
        send("POST", "/bayx/call/send", body, token)
    }

    /** Uzun yoklama: sunucu en fazla 25 saniye bekletir. [after]: alınan son olay numarası (teyit). */
    suspend fun callPoll(token: String, after: Long): List<JSONObject> {
        val dto = post<CallEventsDto>("/bayx/call/poll", buildJsonObject { put("after", after) }, token, longPoll)
        return dto.events.map { JSONObject(it.toString()) }
    }

    /** Arama sinyalleri için WebSocket istemcisi: 20 sn'de bir canlılık sinyali, okuma süresi sınırsız. */
    private val socketClient: OkHttpClient = http.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Anlık arama sinyali bağlantısı; [after] alınan son olay numarası (teyit). */
    fun callSocket(token: String, after: Long, listener: WebSocketListener): WebSocket {
        val request = Request.Builder()
            .url("$HOMESERVER/bayx/call/ws?after=$after")
            .header("Authorization", "Bearer $token")
            .build()
        return socketClient.newWebSocket(request, listener)
    }

    suspend fun turn(token: String): JSONObject = JSONObject(send("GET", "/bayx/turn", token = token).ifBlank { "{}" })

    // ------------------------------------------------------------ yönetim

    suspend fun adminMembers(token: String): AdminMembers {
        val dto = get<MembersDto>("/bayx/admin/members", token)
        return AdminMembers(dto.members.map { it.toMember(it.userId) }, dto.count, dto.max, dto.invites)
    }

    suspend fun adminAction(token: String, action: String, userId: String, newPassword: String? = null) =
        postUnit("/bayx/admin/$action", buildJsonObject {
            put("user_id", userId)
            if (newPassword != null) put("new_password", newPassword)
        }, token)
}
