package com.bayx.chat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.ChatSession
import com.bayx.chat.data.GroupRepo
import com.bayx.chat.data.StatusRepo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Ekranların durumu ve işlemleri ViewModel'lerde tutulur: ekran döndürülse ya da tema
// değişse bile açık sohbet, yüklenen bilgiler ve süren işlemler kaybolmaz. Ekranlar
// yalnızca durumu gösterir ve kullanıcının isteğini ViewModel'e iletir.

/** Açık bir sohbet: ekran yaşadığı sürece tek bir sohbet oturumu açık tutulur. */
class ChatViewModel(private val roomId: String) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data object Failed : State
        data class Ready(val session: ChatSession) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var cleared = false

    init {
        viewModelScope.launch {
            // Açma işlemi yarıda kesilmesin; ekran o arada kapandıysa hemen geri kapatılır.
            val session = withContext(NonCancellable) { runCatching { Bayx.openChat(roomId) }.getOrNull() }
            if (cleared) {
                session?.let(Bayx::closeChat)
                return@launch
            }
            _state.value = if (session != null) State.Ready(session) else State.Failed
        }
    }

    override fun onCleared() {
        cleared = true
        (state.value as? State.Ready)?.session?.let(Bayx::closeChat)
    }
}

/** Grup bilgisi ekranı: üyeler, yetkiler ve yönetim işlemleri. */
class GroupInfoViewModel(private val roomId: String) : ViewModel() {
    private val _info = MutableStateFlow<GroupRepo.GroupInfo?>(null)
    val info: StateFlow<GroupRepo.GroupInfo?> = _info.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Kullanıcıya kısa bilgi (ör. "Üye eklenemedi."). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        _info.value = GroupRepo.groupInfo(roomId)
        _loading.value = false
    }

    private fun act(fail: String, block: suspend () -> Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            if (!block()) _messages.tryEmit(fail)
            reload()
            _busy.value = false
        }
    }

    fun rename(name: String, fail: String) = act(fail) { GroupRepo.renameGroup(roomId, name) }
    fun addMembers(ids: List<String>, fail: String) = act(fail) { GroupRepo.addGroupMembers(roomId, ids) }
    fun toggleAdmin(member: GroupRepo.GroupMember, fail: String) =
        act(fail) { GroupRepo.setGroupAdmin(roomId, member.userId, !member.isAdmin) }
    fun remove(member: GroupRepo.GroupMember, fail: String) = act(fail) { GroupRepo.removeGroupMember(roomId, member.userId) }

    fun leave(fail: String, onLeft: () -> Unit) {
        viewModelScope.launch {
            if (GroupRepo.leaveGroup(roomId)) onLeft() else _messages.tryEmit(fail)
        }
    }
}

/** Durum/hikâye ekranı. */
class StatusViewModel : ViewModel() {
    val statuses = StatusRepo.statuses
    val error = StatusRepo.statusError

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _uploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = _uploading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { StatusRepo.refreshStatuses() }
    }

    fun addText(text: String, bg: String) {
        viewModelScope.launch { StatusRepo.addTextStatus(text, bg)?.let { _messages.tryEmit(it) } }
    }

    fun addImage(jpeg: ByteArray, fail: String, caption: String = "") {
        _uploading.value = true
        viewModelScope.launch {
            val ok = StatusRepo.addImageStatus(jpeg, caption)
            _uploading.value = false
            if (!ok) _messages.tryEmit(StatusRepo.statusError.value ?: fail)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { StatusRepo.deleteStatus(id) }
    }

    fun markSeen(owner: String, id: String) = StatusRepo.markStatusSeen(owner, id)
}
