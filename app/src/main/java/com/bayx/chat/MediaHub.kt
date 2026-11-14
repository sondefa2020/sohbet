package com.bayx.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.ChatSession
import kotlinx.coroutines.launch

/** Medya sekmesi: sohbet seç, o sohbetteki fotoğraf ve videoları gör. */
@Composable
fun MediaHubScreen(onBack: () -> Unit) {
    val rooms by Bayx.rooms.collectAsState()
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<ChatSession?>(null) }
    var loading by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { session?.let { Bayx.closeChat(it) } } }

    Column(Modifier.fillMaxSize().background(Color(0xFFF4F8FC)).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
            Text("Medya", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            t("Fotoğraf ve videolarını görmek istediğin sohbeti seç."),
            color = Color(0xFF5B6B7C),
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rooms.filter { !it.secret }, key = { it.id }) { chat ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !loading) {
                        loading = true
                        scope.launch {
                            session?.let { Bayx.closeChat(it) }
                            session = Bayx.openChat(chat.id)
                            loading = false
                        }
                    }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Avatar(chat.name, 48.dp, url = chat.avatarUrl)
                    Spacer(Modifier.width(14.dp))
                    Text(chat.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    session?.let { current ->
        MediaGalleryDialog(current) {
            Bayx.closeChat(current)
            session = null
        }
    }
}
