package com.bayx.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.CallEntry
import com.bayx.chat.data.CallLog
import kotlinx.coroutines.launch

private val MissedRed = Color(0xFFB4232A)

@Composable
fun CallsScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val entries by CallLog.entries.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var opening by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun openWith(peerId: String) {
        if (opening) return
        opening = true
        scope.launch {
            val roomId = Bayx.startDirectChat(peerId)
            opening = false
            if (roomId != null) onOpenChat(roomId) else toast("Sohbet açılamadı. Tekrar dene.")
        }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text("Aramalar", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                Text(
                    "Temizle",
                    color = SoftBlue,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable { confirmClear = true }.padding(8.dp),
                )
            }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Outlined.Call, null, tint = SoftBlue, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(14.dp))
                    Text("Henüz arama yok", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t("Bir sohbeti açıp üstteki telefon ya da kamera simgesine dokunarak arama başlat. Aramaların burada listelenir."),
                        color = SoftBlue, fontSize = 14.sp,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.peerId + ":" + it.time }) { e -> CallRow(e) { openWith(e.peerId) } }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Arama geçmişini temizle") },
            text = { Text("Tüm arama kayıtları silinsin mi? Bu işlem geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = { CallLog.clear(); confirmClear = false }) { Text("Temizle") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Vazgeç") }
            },
        )
    }
}

@Composable
private fun CallRow(e: CallEntry, onClick: () -> Unit) {
    val missed = !e.outgoing && !e.answered
    val arrow = when {
        missed -> Icons.AutoMirrored.Filled.CallMissed
        e.outgoing -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }
    val label = when {
        missed -> t("Cevapsız")
        e.outgoing && !e.answered -> t("Giden · ulaşılamadı")
        e.outgoing -> "Giden arama"
        else -> "Gelen arama"
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(e.peerName.ifBlank { "?" }, 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.peerName.ifBlank { "Bilinmeyen" },
                color = if (missed) MissedRed else Ink,
                fontSize = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    arrow, null,
                    tint = if (missed) MissedRed else SoftBlue,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "$label · ${formatTime(e.time)}",
                    color = if (missed) MissedRed else SoftBlue, fontSize = 13.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            if (e.video) Icons.Outlined.Videocam else Icons.Outlined.Call,
            contentDescription = if (e.video) t("Görüntülü ara") else "Sesli ara",
            tint = Blue,
            modifier = Modifier.size(24.dp),
        )
    }
}
