package com.bayx.chat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.PowerLevel
import org.matrix.rustcomponents.sdk.UserPowerLevelUpdate

/** Grup yönetimi: üyeler, yetkiler, ad değiştirme, ayrılma. */
object GroupRepo {
    // ------------------------------------------------------------- grup yönetimi

    data class GroupMember(
        val userId: String,
        val name: String,
        val avatarUrl: String?,
        val power: Long,
        val isMe: Boolean,
        val invited: Boolean,
    ) {
        val isAdmin get() = power >= 100
        val isModerator get() = power >= 50
    }

    data class GroupInfo(
        val roomId: String,
        val name: String,
        val members: List<GroupMember>,
        val myPower: Long,
    ) {
        val amAdmin get() = myPower >= 100
        val canManage get() = myPower >= 50
    }

    suspend fun groupInfo(roomId: String): GroupInfo? = withContext(Dispatchers.IO) {
        val c = Bayx.client ?: return@withContext null
        val room = c.getRoom(roomId) ?: return@withContext null
        val me = (Bayx.auth.value as? AuthState.LoggedIn)?.userId
        runCatching {
            val list = ArrayList<GroupMember>()
            val iterator = room.members()
            while (true) {
                val chunk = iterator.nextChunk(50u) ?: break
                for (m in chunk) {
                    val state = m.membership
                    if (state != MembershipState.Join && state != MembershipState.Invite) continue
                    val power = when (val pl = m.powerLevel) {
                        is PowerLevel.Infinite -> 10_000L
                        is PowerLevel.Value -> pl.value
                    }
                    list.add(
                        GroupMember(
                            userId = m.userId,
                            name = m.displayName ?: m.userId.removePrefix("@").substringBefore(":"),
                            avatarUrl = m.avatarUrl,
                            power = power,
                            isMe = m.userId == me,
                            invited = state == MembershipState.Invite,
                        ),
                    )
                }
            }
            iterator.close()
            list.sortWith(compareByDescending<GroupMember> { it.power }.thenBy { it.name.lowercase() })
            val myPower = list.firstOrNull { it.isMe }?.power ?: 0L
            GroupInfo(roomId, room.displayName() ?: "Grup", list, myPower)
        }.getOrNull()
    }

    suspend fun renameGroup(roomId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val room = Bayx.client?.getRoom(roomId) ?: return@withContext false
        val ok = runCatching { room.setName(name.trim()) }.isSuccess
        if (ok) Bayx.refreshRooms()
        ok
    }

    suspend fun addGroupMembers(roomId: String, userIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        val room = Bayx.client?.getRoom(roomId) ?: return@withContext false
        var allOk = true
        for (id in userIds) {
            allOk = runCatching { room.inviteUserById(id) }.isSuccess && allOk
        }
        allOk
    }

    suspend fun removeGroupMember(roomId: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        val room = Bayx.client?.getRoom(roomId) ?: return@withContext false
        runCatching { room.kickUser(userId, null) }.isSuccess
    }

    suspend fun setGroupAdmin(roomId: String, userId: String, admin: Boolean): Boolean = withContext(Dispatchers.IO) {
        val room = Bayx.client?.getRoom(roomId) ?: return@withContext false
        runCatching {
            room.updatePowerLevelsForUsers(listOf(UserPowerLevelUpdate(userId, if (admin) 100L else 0L)))
        }.isSuccess
    }

    suspend fun leaveGroup(roomId: String): Boolean = withContext(Dispatchers.IO) {
        val room = Bayx.client?.getRoom(roomId) ?: return@withContext false
        val ok = runCatching { room.leave() }.isSuccess
        if (ok) Bayx.refreshRooms()
        ok
    }
}
