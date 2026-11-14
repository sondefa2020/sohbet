package com.bayx.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bayx.chat.data.Api
import com.bayx.chat.data.Bayx
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val StatusBgColors = listOf(
    "#2E8B6E", "#1F6B52", "#B07A4E", "#6D4FC2", "#B4232A", "#2563A8", "#C2185B", "#374151",
)

private fun parseColor(hex: String, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(fallback)

@Composable
fun StatusScreen(onBack: () -> Unit) {
    val data by Bayx.statuses.collectAsState()
    val scope = rememberCoroutineScope()
    var composing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Api.StatusUser?>(null) }

    LaunchedEffect(Unit) { Bayx.refreshStatuses() }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text("Durum", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                val mine = data?.mine
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { if (mine != null) viewing = mine else composing = true }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        StatusRing(hasStatus = mine != null, allSeen = false) { Avatar("Ben", 58.dp) }
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF2E8B6E))
                                .border(2.dp, PageBrush, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Durumum", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (mine != null) tf("%s güncelleme · dokunup gör", mine.items.size) else t("Durum paylaşmak için dokun"),
                            color = SoftBlue, fontSize = 13.sp,
                        )
                    }
                    if (mine != null) {
                        IconButton(onClick = { composing = true }) {
                            Icon(Icons.Filled.Add, "Ekle", tint = Blue)
                        }
                    }
                }
            }

            val friends = data?.friends.orEmpty()
            if (friends.isNotEmpty()) {
                item {
                    Text(
                        t("Son güncellemeler"),
                        color = SoftBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 22.dp, top = 10.dp, bottom = 4.dp),
                    )
                }
                items(friends, key = { it.userId }) { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewing = u }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusRing(hasStatus = true, allSeen = u.allSeen) { Avatar(u.name, 58.dp) }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(u.name, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatTime(u.lastTime), color = SoftBlue, fontSize = 13.sp)
                        }
                    }
                }
            } else {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Arkadaşlarının durumları burada görünür.\nİlk durumu sen paylaş!",
                            color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                        )
                    }
                }
            }
        }
    }

    if (composing) {
        StatusComposer(
            onDismiss = { composing = false },
            onShare = { text, bg ->
                composing = false
                scope.launch { Bayx.addTextStatus(text, bg) }
            },
        )
    }

    viewing?.let { user ->
        val mine = user.userId == data?.mine?.userId
        StatusViewer(
            user = user,
            mine = mine,
            onClose = { viewing = null },
            onSeen = { item -> if (!mine) Bayx.markStatusSeen(user.userId, item.id) },
            onDelete = { item -> scope.launch { Bayx.deleteStatus(item.id) }; viewing = null },
        )
    }
}

@Composable
private fun StatusRing(hasStatus: Boolean, allSeen: Boolean, content: @Composable () -> Unit) {
    val ring = when {
        !hasStatus -> Color(0xFFD9CFC2)
        allSeen -> Color(0xFFC7BBA9)
        else -> Color(0xFF2E8B6E)
    }
    Box(
        Modifier.size(66.dp).border(2.5.dp, ring, CircleShape).padding(4.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun StatusComposer(onDismiss: () -> Unit, onShare: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var bgIndex by remember { mutableStateOf(0) }
    val bg = parseColor(StatusBgColors[bgIndex], Color(0xFF2E8B6E))
    val animated by animateColorAsState(bg, label = "bg")

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(CardBg),
        ) {
            Box(
                Modifier.fillMaxWidth().height(260.dp).background(animated),
                contentAlignment = Alignment.Center,
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { if (it.length <= 700) text = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text("Ne düşünüyorsun?", color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        inner()
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusBgColors.forEachIndexed { i, hex ->
                    val c = parseColor(hex, Color(0xFF2E8B6E))
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(c)
                            .then(if (i == bgIndex) Modifier.border(2.dp, Ink, CircleShape) else Modifier)
                            .clickable { bgIndex = i },
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Vazgeç") }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = { if (text.isNotBlank()) onShare(text.trim(), StatusBgColors[bgIndex]) },
                    colors = ButtonDefaults.buttonColors(containerColor = Blue),
                ) { Text("Paylaş") }
            }
        }
    }
}

@Composable
private fun StatusViewer(
    user: Api.StatusUser,
    mine: Boolean,
    onClose: () -> Unit,
    onSeen: (Api.StatusItem) -> Unit,
    onDelete: (Api.StatusItem) -> Unit,
) {
    var index by remember(user.userId) { mutableStateOf(0) }
    val item = user.items.getOrNull(index) ?: run { onClose(); return }

    LaunchedEffect(index) {
        onSeen(item)
        delay(5000)
        if (index < user.items.size - 1) index++ else onClose()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(parseColor(item.bg, Color(0xFF14110F))),
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // İlerleme çubukları
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    user.items.forEachIndexed { i, _ ->
                        Box(
                            Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (i <= index) Color.White else Color.White.copy(alpha = 0.35f)),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(user.name, 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (mine) "Durumum" else user.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(formatTime(item.time), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                    }
                    if (mine) {
                        IconButton(onClick = { onDelete(item) }) { Icon(Icons.Outlined.Delete, "Sil", tint = Color.White) }
                    }
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kapat", tint = Color.White) }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Dokunma alanları: sol=geri, sağ=ileri
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().clickable { if (index > 0) index-- else onClose() })
                        Box(Modifier.weight(1f).fillMaxHeight().clickable { if (index < user.items.size - 1) index++ else onClose() })
                    }
                    Text(
                        item.text,
                        color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
            }
        }
    }
}
