package com.bayx.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Bayx
import kotlinx.coroutines.launch

private val LeaveRed = Color(0xFFB4232A)

@Composable
fun GroupInfoScreen(roomId: String, onBack: () -> Unit, onLeft: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var info by remember { mutableStateOf<Bayx.GroupInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    var renaming by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var actionMember by remember { mutableStateOf<Bayx.GroupMember?>(null) }

    suspend fun reload() {
        info = Bayx.groupInfo(roomId)
        loading = false
    }
    LaunchedEffect(roomId) { reload() }

    fun act(block: suspend () -> Boolean, fail: String) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = block()
            if (!ok) toast(fail)
            reload()
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text("Grup bilgisi", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        val g = info
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) }
            return@Column
        }
        if (g == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Grup bilgisi alınamadı.", color = Muted)
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(g.name, 88.dp)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            g.name, color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (g.canManage) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Outlined.Edit, "Adı değiştir", tint = Blue,
                                modifier = Modifier.size(20.dp).clickable { renaming = true },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${g.members.size} üye", color = SoftBlue, fontSize = 14.sp)
                }
            }

            if (g.canManage) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(44.dp).background(Color(0x1A2E8B6E), RoundedCornerShape(22.dp)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Outlined.PersonAddAlt, null, tint = Color(0xFF2E8B6E)) }
                        Spacer(Modifier.width(14.dp))
                        Text("Üye ekle", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            item {
                Text(
                    "Üyeler", color = SoftBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 2.dp),
                )
            }

            items(g.members, key = { it.userId }) { m ->
                val clickable = g.canManage && !m.isMe
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (clickable) Modifier.clickable { actionMember = m } else Modifier)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(m.name, 48.dp, url = m.avatarUrl)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (m.isMe) "${m.name} (sen)" else m.name,
                            color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val sub = when {
                            m.invited -> "Davet edildi"
                            m.isAdmin -> "Yönetici"
                            else -> "Üye"
                        }
                        Text(sub, color = if (m.isAdmin) Color(0xFF2E8B6E) else SoftBlue, fontSize = 13.sp)
                    }
                    if (m.isAdmin) Icon(Icons.Outlined.Shield, null, tint = Color(0xFF2E8B6E), modifier = Modifier.size(18.dp))
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().clickable { leaving = true }.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, null, tint = LeaveRed, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Gruptan ayrıl", color = LeaveRed, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // Adı değiştir
    if (renaming && info != null) {
        var name by remember { mutableStateOf(info!!.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Grup adı") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    renaming = false
                    if (n.isNotEmpty()) act({ Bayx.renameGroup(roomId, n) }, "Ad değiştirilemedi.")
                }) { Text("Kaydet") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Vazgeç") } },
        )
    }

    // Üye ekle
    if (adding && info != null) {
        val existing = info!!.members.map { it.userId }.toSet()
        AddMembersDialog(
            existing = existing,
            onDismiss = { adding = false },
            onAdd = { ids ->
                adding = false
                if (ids.isNotEmpty()) act({ Bayx.addGroupMembers(roomId, ids) }, "Üye eklenemedi.")
            },
        )
    }

    // Gruptan ayrıl
    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text("Gruptan ayrıl") },
            text = { Text("Bu gruptan ayrılmak istediğine emin misin? Tekrar katılman için yeniden davet edilmen gerekir.") },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    scope.launch {
                        if (Bayx.leaveGroup(roomId)) onLeft() else toast("Gruptan ayrılınamadı.")
                    }
                }) { Text("Ayrıl", color = LeaveRed) }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text("Vazgeç") } },
        )
    }

    // Üye işlemleri
    actionMember?.let { m ->
        val amAdmin = info?.amAdmin == true
        MemberActionSheet(
            member = m,
            amAdmin = amAdmin,
            onDismiss = { actionMember = null },
            onToggleAdmin = {
                actionMember = null
                act({ Bayx.setGroupAdmin(roomId, m.userId, !m.isAdmin) }, "Yetki değiştirilemedi.")
            },
            onRemove = {
                actionMember = null
                act({ Bayx.removeGroupMember(roomId, m.userId) }, "Üye çıkarılamadı.")
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberActionSheet(
    member: Bayx.GroupMember,
    amAdmin: Boolean,
    onDismiss: () -> Unit,
    onToggleAdmin: () -> Unit,
    onRemove: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardBg) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                member.name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            )
            if (amAdmin && !member.invited) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onToggleAdmin).padding(horizontal = 24.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Shield, null, tint = Blue, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(20.dp))
                    Text(if (member.isAdmin) "Yöneticiliği kaldır" else "Yönetici yap", color = Ink, fontSize = 16.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onRemove).padding(horizontal = 24.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.PersonRemove, null, tint = LeaveRed, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(20.dp))
                Text("Gruptan çıkar", color = LeaveRed, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun AddMembersDialog(existing: Set<String>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val contacts by Bayx.contacts.collectAsState()
    val candidates = contacts.friends.filter { it.userId !in existing }
    val selected = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Üye ekle") },
        text = {
            if (candidates.isEmpty()) {
                Text("Eklenebilecek kişi yok. Önce Kişiler'den arkadaş ekle.", color = Muted)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    candidates.forEach { f ->
                        val on = selected.contains(f.userId)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (on) selected.remove(f.userId) else selected.add(f.userId)
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = {
                                if (on) selected.remove(f.userId) else selected.add(f.userId)
                            })
                            Spacer(Modifier.width(6.dp))
                            Text(f.displayName, color = Ink, fontSize = 16.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(selected.toList()) }) { Text("Ekle") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } },
    )
}
