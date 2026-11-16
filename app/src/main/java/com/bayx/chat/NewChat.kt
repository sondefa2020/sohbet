package com.bayx.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bayx.chat.data.Api
import com.bayx.chat.data.Bayx
import com.bayx.chat.data.ContactsRepo
import com.bayx.chat.data.PhonebookRepo
import com.bayx.chat.data.PresenceRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Yeni sohbet (WhatsApp'taki "Kişi seç"): rehberindeki Tayfa kullanıcıları, numara çevirerek
 * sohbet, kullanıcı adıyla bulma, QR, yeni grup ve kendime not. İstek göndermek gerekmez.
 */
@Composable
fun NewChatScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onDialpad: () -> Unit,
    onFindName: () -> Unit,
    onPerson: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val book by PhonebookRepo.entries.collectAsState()
    val contacts by ContactsRepo.contacts.collectAsState()
    val presences by PresenceRepo.presences.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    var scanQr by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        PhonebookRepo.syncIfStale(context, 30_000L)
        ContactsRepo.refreshContacts()
    }
    val people = groupCandidates(book, contacts.friends).filter { m ->
        query.isBlank() || m.displayName.contains(query, true) ||
            book.firstOrNull { it.userId == m.userId }?.phone?.contains(query.filter { c -> c.isDigit() }.ifEmpty { "\u0000" }) == true
    }

    fun open(userId: String) {
        if (busy) return
        busy = true
        scope.launch {
            val id = Bayx.startDirectChat(userId)
            busy = false
            if (id != null) onOpenChat(id) else toast(t("Sohbet açılamadı. Tekrar dene."))
        }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Ink) }
            Column(Modifier.weight(1f)) {
                Text(t("Yeni sohbet"), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(tf("%s kişi", people.size), color = Muted, fontSize = 13.sp)
            }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Outlined.Search, t("Ara"), tint = Ink) }
        }
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(t("Ad ya da numara ara"), color = Muted, maxLines = 1) },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                    unfocusedBorderColor = Line, focusedBorderColor = Blue,
                ),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp).fillMaxWidth(),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (query.isBlank()) {
                item { ActionLine(Icons.Outlined.Groups, t("Yeni grup")) { newGroup = true } }
                item { ActionLine(Icons.Outlined.Dialpad, t("Numara ile sohbet"), t("Rehberde kayıtlı olmasa da numarayı yaz")) { onDialpad() } }
                item { ActionLine(Icons.Outlined.AlternateEmail, t("Kullanıcı adıyla bul")) { onFindName() } }
                item { ActionLine(Icons.Outlined.QrCodeScanner, t("QR kodu okut")) { scanQr = true } }
                item {
                    ActionLine(Icons.Outlined.EditNote, t("Kendime not"), t("Not, bağlantı ve dosyalarını sakla")) {
                        scope.launch {
                            val id = Bayx.noteToSelf()
                            if (id != null) onOpenChat(id) else toast(t("Not sohbeti açılamadı. Tekrar dene."))
                        }
                    }
                }
                item {
                    Text(t("Tayfa'daki kişilerin"), color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp))
                }
            }
            if (people.isEmpty()) {
                item {
                    Text(
                        if (query.isNotBlank()) t("Eşleşen kişi yok. Numarayı \"Numara ile sohbet\" ile yazabilirsin.")
                        else t("Rehberindeki kişilerden henüz Tayfa kullanan yok. Onları davet et ya da numarayla yaz."),
                        color = Muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(people, key = { it.userId }) { m ->
                val p = presences[m.userId]
                Row(
                    Modifier.fillMaxWidth().clickable { open(m.userId) }.padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.clip(CircleShape).clickable { onPerson(m.userId) }) { Avatar(m.displayName, 48.dp, url = m.avatarUrl, mode = p?.mode) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (p?.status ?: m.status).ifBlank { t("Merhaba! Ben de Tayfa kullanıyorum.") },
                            color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item {
                ActionLine(Icons.Outlined.PersonAddAlt, t("Arkadaşlarını Tayfa'ya davet et")) {
                    val handle = AccountCache.load(context).username.ifBlank { Bayx.myLocalpart() }
                    shareText(context, tf("Tayfa'da benimle konuş: %s", inviteLink(handle)))
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (newGroup) {
        NewGroupDialog(
            members = groupCandidates(book, contacts.friends),
            onDismiss = { newGroup = false },
            onCreate = { name, ids ->
                newGroup = false
                scope.launch {
                    val roomId = Bayx.createGroup(name, ids)
                    if (roomId != null) onOpenChat(roomId) else toast(t("Grup oluşturulamadı. Tekrar dene."))
                }
            },
        )
    }
    if (scanQr) {
        ScanQrDialog(onDismiss = { scanQr = false }) { text ->
            scanQr = false
            if (handleScannedText(text)) onBack() else toast(t("Bu bir Tayfa kişi kodu değil."))
        }
    }
}

@Composable
private fun ActionLine(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(BlueBrush), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ====================================================================== numara ile sohbet

/** Numara çevirme ekranı: numarayı tuşla, Tayfa'daysa sohbet aç / ara; değilse SMS ile davet et. */
@Composable
fun DialpadScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var region by remember { mutableStateOf(PhonebookRepo.region(context)) }
    var digits by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Api.ContactFind?>(null) }
    var looking by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    val check = Phones.check(region, digits)
    val askCallPermissions = rememberCallPermissions { toast(t("Arama için mikrofon (ve görüntülü aramada kamera) izni gerekli.")) }
    val small = LocalConfiguration.current.screenHeightDp < 700

    // Numara tamamlanınca kendiliğinden Tayfa'da ara.
    LaunchedEffect(check) {
        result = null
        notFound = false
        val valid = check as? Phones.Check.Valid ?: return@LaunchedEffect
        delay(350)
        looking = true
        val hit = ContactsRepo.findUser(valid.e164)
        looking = false
        if (hit?.found == true) result = hit else notFound = true
    }

    fun open(userId: String, then: (String) -> Unit) {
        scope.launch {
            val id = Bayx.startDirectChat(userId)
            if (id != null) then(id) else toast(t("Sohbet açılamadı. Tekrar dene."))
        }
    }

    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Ink) }
            Text(t("Numara ile sohbet"), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            CountryPhoneField(region = region, onRegion = { region = it }, digits = digits, onDigits = { digits = it }, check = check)
            Spacer(Modifier.height(10.dp))
            // Sonuç kartı
            Box(Modifier.fillMaxWidth().height(if (small) 76.dp else 92.dp), contentAlignment = Alignment.Center) {
                val r = result
                when {
                    looking -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    r != null -> Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(r.displayName, 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t("Tayfa'da"), color = Color(0xFF1E8E4E), fontSize = 13.sp)
                        }
                    }
                    notFound -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(t("Bu numara Tayfa'da değil."), color = Muted, fontSize = 14.sp)
                        Text(
                            t("SMS ile davet et"), color = Blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable {
                                val e164 = (check as? Phones.Check.Valid)?.e164 ?: return@clickable
                                val handle = AccountCache.load(context).username.ifBlank { Bayx.myLocalpart() }
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$e164"))
                                            .putExtra("sms_body", tf("Tayfa'da benimle konuş: %s", inviteLink(handle))),
                                    )
                                }
                            }.padding(8.dp),
                        )
                    }
                    check is Phones.Check.Invalid || check is Phones.Check.NotMobile ->
                        Text(t("Numarayı kontrol et."), color = Color(0xFFB4232A), fontSize = 14.sp)
                    else -> Text(t("Numarayı yaz; Tayfa'daysa hemen görünür."), color = Muted, fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // Tuş takımı
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(if (small) 6.dp else 10.dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫")).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { key ->
                        Box(
                            Modifier.weight(1f).aspectRatio(if (small) 2.2f else 1.8f).clip(RoundedCornerShape(22.dp))
                                .then(if (key.isEmpty()) Modifier else Modifier.background(CardBg).clickable {
                                    digits = if (key == "⌫") digits.dropLast(1) else (digits + key).take(15)
                                }),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (key == "⌫") {
                                Icon(Icons.AutoMirrored.Outlined.Backspace, t("Sil"), tint = Ink)
                            } else if (key.isNotEmpty()) {
                                Text(key, color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // İşlemler: Mesaj / Sesli / Görüntülü
        val r = result
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DialAction(t("Mesaj"), enabled = r != null, modifier = Modifier.weight(1f)) { r?.let { open(it.userId, onOpenChat) } }
            DialAction(t("Sesli ara"), enabled = r != null, modifier = Modifier.weight(1f)) {
                r?.let { hit -> askCallPermissions(false) { com.bayx.chat.data.Calls.startCall(hit.userId, hit.displayName, false) } }
            }
            DialAction(t("Görüntülü"), enabled = r != null, modifier = Modifier.weight(1f)) {
                r?.let { hit -> askCallPermissions(true) { com.bayx.chat.data.Calls.startCall(hit.userId, hit.displayName, true) } }
            }
        }
    }
}

@Composable
private fun DialAction(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(50.dp).clip(RoundedCornerShape(25.dp))
            .background(if (enabled) BlueBrush else androidx.compose.ui.graphics.SolidColor(Line))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

// ====================================================================== kullanıcı adıyla bul

@Composable
fun FindByNameScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit, onPerson: (String) -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var value by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Api.ContactFind?>(null) }
    var looking by remember { mutableStateOf(false) }
    val clean = value.trim().lowercase().removePrefix("@")
    val wellFormed = Regex("^[a-z0-9][a-z0-9._-]{2,19}$").matches(clean)
    LaunchedEffect(clean) {
        result = null
        if (!wellFormed) return@LaunchedEffect
        delay(450)
        looking = true
        result = ContactsRepo.findUser(clean)
        looking = false
    }
    Column(Modifier.fillMaxSize().background(PageBrush).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Geri"), tint = Ink) }
            Text(t("Kullanıcı adıyla bul"), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = value,
            onValueChange = { v -> value = v.filter { !it.isWhitespace() }.take(21) },
            singleLine = true,
            prefix = { Text("@") },
            placeholder = { Text(t("kullaniciadi"), color = Muted) },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CardBg, unfocusedContainerColor = CardBg,
                unfocusedBorderColor = Line, focusedBorderColor = Blue,
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        )
        val r = result
        when {
            looking -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
            r != null && r.found -> Row(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardBg)
                    .clickable { onPerson(r.userId) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(r.displayName, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.displayName, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("@" + r.username, color = Muted, fontSize = 13.sp)
                }
                if (r.relation != "self") {
                    Text(
                        t("Mesaj"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(BlueBrush).clickable {
                            scope.launch {
                                val id = Bayx.startDirectChat(r.userId)
                                if (id != null) onOpenChat(id) else toast(t("Sohbet açılamadı. Tekrar dene."))
                            }
                        }.padding(horizontal = 16.dp, vertical = 9.dp),
                    )
                }
            }
            wellFormed && r != null && !r.found -> Text(
                tf("@%s adında bir kullanıcı yok.", clean), color = Muted, fontSize = 14.sp,
                modifier = Modifier.padding(20.dp),
            )
            else -> Text(
                t("Kişinin Tayfa kullanıcı adını yaz. Kullanıcı adını Profil > Kullanıcı adı'nda görebilir."),
                color = Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(20.dp),
            )
        }
    }
}
