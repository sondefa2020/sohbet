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
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.AddIcCall
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
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

/** Art arda aynı kişiyle, aynı yönde yapılan aramalar tek satırda toplanır: "Dilan (3)". */
private data class CallGroup(val first: CallEntry, val count: Int)

private fun group(list: List<CallEntry>): List<CallGroup> {
    val out = mutableListOf<CallGroup>()
    for (e in list) {
        val last = out.lastOrNull()
        val missed = !e.outgoing && !e.answered
        if (last != null && last.first.peerId == e.peerId && last.first.outgoing == e.outgoing &&
            (!last.first.outgoing && !last.first.answered) == missed && last.first.time - e.time < 6 * 3600_000L
        ) {
            out[out.size - 1] = last.copy(count = last.count + 1)
        } else {
            out += CallGroup(e, 1)
        }
    }
    return out
}

@Composable
fun CallsScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onNewCall: () -> Unit = {},
    onDialpad: () -> Unit = {},
) {
    val entries by CallLog.entries.collectAsState()
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var opening by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var onlyMissed by remember { mutableStateOf(false) }
    // Sağdaki telefon/kamera simgesi doğrudan arar (WhatsApp gibi); satıra dokunmak sohbeti açar.
    val askCall = rememberCallPermissions { toast(t("Arama için mikrofon (ve görüntülü aramada kamera) izni gerekli.")) }

    fun openWith(peerId: String) {
        if (opening) return
        opening = true
        scope.launch {
            val roomId = Bayx.startDirectChat(peerId)
            opening = false
            if (roomId != null) onOpenChat(roomId) else toast(t("Sohbet açılamadı. Tekrar dene."))
        }
    }

    val filtered = remember(entries, query, onlyMissed) {
        entries.filter { e ->
            (!onlyMissed || (!e.outgoing && !e.answered)) &&
                (query.isBlank() || e.peerName.contains(query.trim(), ignoreCase = true))
        }
    }
    val groups = remember(filtered) { group(filtered) }
    val missedCount = remember(entries) { entries.count { !it.outgoing && !it.answered && System.currentTimeMillis() - it.time < 7 * 86_400_000L } }
    // Sık arananlar: en çok konuşulan 8 kişi (hızlı arama halkaları).
    val favorites = remember(entries) {
        entries.filter { it.answered }.groupBy { it.peerId }.entries
            .sortedByDescending { it.value.size }.take(8).map { it.value.first() }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Ink) }
            Text(t("Aramalar"), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                Text(
                    t("Temizle"), color = SoftBlue, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { confirmClear = true }.padding(8.dp),
                )
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            // Arama kutusu
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
                        .background(GlassPill).padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, tint = Muted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) Text(t("Kişi veya arama ara"), color = Muted, fontSize = 15.sp)
                        BasicTextField(
                            query, { query = it }, singleLine = true,
                            textStyle = TextStyle(color = Ink, fontSize = 15.sp), cursorBrush = SolidColor(Blue),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(Icons.Outlined.Close, t("Temizle"), tint = Muted, modifier = Modifier.size(20.dp).clickable { query = "" })
                    }
                }
            }
            // Hızlı işlemler
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickCard(Icons.Outlined.AddIcCall, t("Yeni arama"), Modifier.weight(1f), onClick = onNewCall)
                    QuickCard(Icons.Outlined.Dialpad, t("Tuş takımı"), Modifier.weight(1f), onClick = onDialpad)
                    QuickCard(
                        Icons.AutoMirrored.Filled.CallMissed,
                        if (missedCount > 0) tf("Cevapsız (%s)", missedCount.toString()) else t("Cevapsız"),
                        Modifier.weight(1f), selected = onlyMissed, alert = missedCount > 0,
                    ) { onlyMissed = !onlyMissed }
                }
            }
            // Sık aranan kişiler
            if (favorites.size >= 2 && query.isBlank() && !onlyMissed) {
                item { SectionLabel(t("Sık arananlar")) }
                item {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(favorites, key = { it.peerId }) { f ->
                            Column(
                                Modifier.width(64.dp).clip(RoundedCornerShape(14.dp))
                                    .clickable { askCall(false) { com.bayx.chat.data.Calls.startCall(f.peerId, f.peerName, false) } }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Avatar(f.peerName.ifBlank { "?" }, 54.dp)
                                Spacer(Modifier.height(5.dp))
                                Text(f.peerName.substringBefore(' ').ifBlank { "?" }, color = Ink, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            item { SectionLabel(if (onlyMissed) t("Cevapsız aramalar") else t("Son aramalar")) }
            if (groups.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Outlined.Call, null, tint = SoftBlue, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(
                            when {
                                entries.isEmpty() -> t("Henüz arama yok")
                                onlyMissed -> t("Cevapsız araman yok")
                                else -> t("Sonuç bulunamadı")
                            },
                            color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        )
                        if (entries.isEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                t("\"Yeni arama\" ile bir kişiyi seçip arayabilir ya da tuş takımından numara çevirebilirsin."),
                                color = SoftBlue, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                item {
                    Column(
                        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(GlassPill),
                    ) {
                        groups.forEachIndexed { i, g ->
                            CallRow(
                                g,
                                onAudio = { askCall(false) { com.bayx.chat.data.Calls.startCall(g.first.peerId, g.first.peerName, false) } },
                                onVideo = { askCall(true) { com.bayx.chat.data.Calls.startCall(g.first.peerId, g.first.peerName, true) } },
                            ) { openWith(g.first.peerId) }
                            if (i < groups.lastIndex) {
                                Box(Modifier.padding(start = 78.dp).fillMaxWidth().height(1.dp).background(Line))
                            }
                        }
                    }
                }
            }
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 18.dp, start = 32.dp, end = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.Lock, null, tint = Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t("Aramalar uçtan uca şifrelidir. Geçmiş yalnızca bu telefonda tutulur."),
                        color = Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(t("Arama geçmişini temizle")) },
            text = { Text(t("Tüm arama kayıtları silinsin mi? Bu işlem geri alınamaz.")) },
            confirmButton = {
                TextButton(onClick = { CallLog.clear(); confirmClear = false }) { Text(t("Temizle")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(t("Vazgeç")) }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp))
}

@Composable
private fun QuickCard(
    icon: ImageVector,
    label: String,
    modifier: Modifier,
    selected: Boolean = false,
    alert: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp))
            .background(if (selected) Blue.copy(alpha = 0.16f) else GlassPill)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (alert) MissedRed.copy(alpha = 0.12f) else Blue.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (alert) MissedRed else Blue, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(label, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun durationText(seconds: Long): String = when {
    seconds <= 0 -> ""
    seconds < 60 -> tf("%s sn", seconds.toString())
    seconds < 3600 -> tf("%s dk", (seconds / 60).toString())
    else -> tf("%s sa %s dk", (seconds / 3600).toString(), ((seconds / 60) % 60).toString())
}

@Composable
private fun CallRow(g: CallGroup, onAudio: () -> Unit, onVideo: () -> Unit, onClick: () -> Unit) {
    val e = g.first
    val missed = !e.outgoing && !e.answered
    val arrow = when {
        missed -> Icons.AutoMirrored.Filled.CallMissed
        e.outgoing -> Icons.AutoMirrored.Filled.CallMade
        else -> Icons.AutoMirrored.Filled.CallReceived
    }
    // Arama türü küçük simgeyle gösterilir; yazı kısa kalır, saat kesilmez.
    val label = when {
        missed -> t("Cevapsız")
        e.outgoing && !e.answered -> t("Ulaşılamadı")
        else -> durationText(e.duration).ifEmpty { if (e.outgoing) t("Giden") else t("Gelen") }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(e.peerName.ifBlank { "?" }, 50.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.peerName.ifBlank { t("Bilinmeyen") } + if (g.count > 1) " (${g.count})" else "",
                color = if (missed) MissedRed else Ink,
                fontSize = 16.5.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(arrow, null, tint = if (missed) MissedRed else if (e.outgoing) Color(0xFF1E8E4E) else SoftBlue, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Icon(
                    if (e.video) Icons.Outlined.Videocam else Icons.Outlined.Call,
                    if (e.video) t("Görüntülü") else t("Sesli"),
                    tint = if (missed) MissedRed else SoftBlue, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "$label · ${formatTime(e.time)}",
                    color = if (missed) MissedRed else SoftBlue, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        CallBubble(Icons.Outlined.Call, t("Sesli ara"), onAudio)
        Spacer(Modifier.width(6.dp))
        CallBubble(Icons.Outlined.Videocam, t("Görüntülü ara"), onVideo)
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun CallBubble(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Blue.copy(alpha = 0.10f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = Blue, modifier = Modifier.size(21.dp)) }
}
