package com.bayx.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kişiler: arkadaşlar, gelen/giden istekler, engellenenler. */
object ContactsRepo {
    // ------------------------------------------------------------- kişiler

    val contacts = MutableStateFlow(Api.Contacts(emptyList(), emptyList(), emptyList()))

    suspend fun refreshContacts() {
        val token = Bayx.accessToken ?: return
        runCatching { Api.contacts(token) }.onSuccess {
            contacts.value = it
            Bayx.rememberMembers(it.friends + it.incoming + it.outgoing)
        }
    }

    suspend fun findUser(username: String): Api.ContactFind? {
        val token = Bayx.accessToken ?: return null
        return runCatching { Api.contactFind(token, username) }.getOrNull()
    }

    /** Kişi isteği gönderir; hata varsa mesajı döndürür. */
    suspend fun sendContactRequest(username: String): String? {
        val token = Bayx.accessToken ?: return "Oturum yok."
        return try {
            Api.contactRequest(token, username)
            refreshContacts()
            null
        } catch (e: ApiException) {
            e.message
        }
    }

    suspend fun acceptContact(username: String): Boolean {
        val token = Bayx.accessToken ?: return false
        val ok = runCatching { Api.contactAccept(token, username) }.isSuccess
        if (ok) refreshContacts()
        return ok
    }

    suspend fun rejectContact(username: String): Boolean {
        val token = Bayx.accessToken ?: return false
        val ok = runCatching { Api.contactReject(token, username) }.isSuccess
        if (ok) refreshContacts()
        return ok
    }

    suspend fun blockContact(username: String): Boolean {
        val token = Bayx.accessToken ?: return false
        val ok = runCatching { Api.contactBlock(token, username) }.isSuccess
        if (ok) refreshContacts()
        return ok
    }

    suspend fun unblockContact(username: String): Boolean {
        val token = Bayx.accessToken ?: return false
        val ok = runCatching { Api.contactUnblock(token, username) }.isSuccess
        if (ok) refreshContacts()
        return ok
    }

    /** Oturum kapanınca. */
    internal fun reset() {
        contacts.value = Api.Contacts(emptyList(), emptyList(), emptyList())
    }
}
