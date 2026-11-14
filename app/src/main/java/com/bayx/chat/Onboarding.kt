package com.bayx.chat

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Gate

/** İlk kurulumda bir kez gösterilen hoş geldin ve sözleşme onay ekranı (WhatsApp tarzı). */
@Composable
fun OnboardingScreen() {
    var legal by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(PageBrush)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))
        LogoMark(120.dp)
        Spacer(Modifier.height(16.dp))
        Text("Tayfa'ya hoş geldin", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            t("Özel, davetli bir sohbet uygulaması. Devam etmeden önce birkaç kısa noktaya göz at."),
            color = Muted,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Point(Icons.Outlined.Lock, t("Uçtan uca şifreli"), t("Mesajların yalnızca sen ve karşındaki arasında okunur; gelen medya telefonun galerisine kaydedilmez."))
        Spacer(Modifier.height(20.dp))
        Point(Icons.Outlined.PersonAddAlt, t("Kişi onayı ile"), t("Biri seninle ancak sen kabul edersen sohbet edebilir. Kabul etmediğin kimse profilini göremez."))
        Spacer(Modifier.height(20.dp))
        Point(Icons.Outlined.Shield, t("Senin sorumluluğunda"), t("Hesabını ve paylaştıklarını sen yönetirsin. Yöneticinin kuralları ihlal eden hesapları kısıtlama yetkisi vardır."))
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(28.dp))
        val notice = buildAnnotatedString {
            append("Devam ederek ")
            withStyle(SpanStyle(color = Blue, fontWeight = FontWeight.SemiBold)) { append(t("Kullanıcı Sözleşmesi")) }
            append(" ve ")
            withStyle(SpanStyle(color = Blue, fontWeight = FontWeight.SemiBold)) { append(t("Gizlilik Politikası")) }
            append(t("'nı kabul etmiş olursun."))
        }
        Text(notice, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
        Row(horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { legal = t("Kullanıcı Sözleşmesi") to Legal.TERMS }) { Text("Sözleşmeyi oku", fontSize = 13.sp) }
            TextButton(onClick = { legal = t("Gizlilik Politikası") to Legal.PRIVACY }) { Text("Gizliliği oku", fontSize = 13.sp) }
        }
        Spacer(Modifier.height(8.dp))
        SandButton("Kabul et ve devam et") { Gate.acceptOnboarding() }
        Spacer(Modifier.height(28.dp))
    }

    legal?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { legal = null },
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text(body, color = Ink, fontSize = 13.5.sp, lineHeight = 20.sp)
                }
            },
            confirmButton = { TextButton(onClick = { legal = null }) { Text("Kapat") } },
        )
    }
}

@Composable
private fun Point(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFFEAF3E6)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color(0xFF2E8B6E), modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(body, color = Muted, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}
