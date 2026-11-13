package com.bayx.chat

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.PersistableBundle
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.bayx.chat.data.Gate
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

// ---------------------------------------------------------------- renkler

/** Sıcak krem zemin üzerinde kum rengi vurgu (gönder düğmesi, ana düğmeler). */
internal val SandBrush = Brush.verticalGradient(listOf(Color(0xFFE6C9A6), Color(0xFFC79A6E)))
internal val SandInk = Color(0xFF2A1C10)

/** Kendi mesaj balonum: açık şeftali. */
internal val PeachBrush = Brush.linearGradient(listOf(Color(0xFFFBEEDF), Color(0xFFF3DDC6)))
internal val ChipBg = Color(0xFFF1ECE6)

/** Saten kartlar: yumuşak, ipeksi degradeler. */
internal object Satin {
    val Steel = listOf(Color(0xFF8FA6C8), Color(0xFF5D7AA6), Color(0xFF3F5884))
    val Lavender = listOf(Color(0xFFB4A6DA), Color(0xFF8676BE), Color(0xFF5E4F98))
    val Sand = listOf(Color(0xFFE9D3B6), Color(0xFFC9A27A), Color(0xFF9D7550))
    val Mauve = listOf(Color(0xFFD6A9C2), Color(0xFFAE7898), Color(0xFF7D5070))
    val Periwinkle = listOf(Color(0xFFA9B3EE), Color(0xFF7480D8), Color(0xFF5059B0))
    val Bronze = listOf(Color(0xFFB9A08A), Color(0xFF7E6553), Color(0xFF4E3D33))
}

// ----------------------------------------------------------------- parçalar

/** Saten degradeli kare kart: beyaz simge ve yazı. */
@Composable
internal fun SatinTile(
    label: String,
    icon: ImageVector,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .shadow(6.dp, shape, ambientColor = colors.last(), spotColor = colors.last())
            .clip(shape)
            .background(Brush.linearGradient(colors, start = Offset(0f, 0f), end = Offset(380f, 420f)))
            .drawBehind {
                // İpek parlaması: köşeden geçen açık bir şerit.
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.38f), Color.Transparent),
                        center = Offset(size.width * 0.25f, size.height * 0.1f),
                        radius = size.width * 0.9f,
                    ),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.25f, size.height * 0.1f),
                )
            }
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
    }
}

/** Yuvarlak, açık bej zeminli simge düğmesi (başlıklardaki arama, görüntülü arama vb.). */
@Composable
internal fun ChipIcon(icon: ImageVector, description: String, size: Dp = 46.dp, tint: Color = Ink, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(ChipBg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

/** Beyaz, yumuşak gölgeli, çok yuvarlak kart. */
@Composable
internal fun SoftCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        content = content,
    )
}

/** Logo: koyu küre ve neon balon, etrafında açık bir halka. */
@Composable
internal fun LogoMark(size: Dp) {
    Image(painterResource(R.drawable.ic_logo), contentDescription = "Tayfa", modifier = Modifier.size(size))
}

/** Kod alanı: büyük harf, karışan karakterler düzeltilir, 4'lü gruplara tire eklenir. */
@Composable
internal fun CodeField(value: String, onValueChange: (String) -> Unit, placeholder: String, length: Int) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(formatCode(it, length)) },
        singleLine = true,
        placeholder = { Text(placeholder, color = Muted, fontFamily = FontFamily.Monospace) },
        textStyle = androidx.compose.ui.text.TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 21.sp,
            letterSpacing = 2.sp,
            textAlign = TextAlign.Center,
            color = Ink,
        ),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
        ),
        shape = RoundedCornerShape(20.dp),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color(0xFFFCFAF7),
    unfocusedBorderColor = Line,
    focusedBorderColor = Blue,
    cursorColor = Blue,
)

private const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

internal fun formatCode(raw: String, length: Int): String {
    val clean = raw.uppercase()
        .replace('0', 'O').replace('1', 'I')
        .filter { it in CODE_ALPHABET }
        .take(length)
    return clean.chunked(4).joinToString("-")
}

/** Ad soyaddan kullanıcı adı önerisi: "Ayşe Yılmaz" → "ayse.yilmaz". */
internal fun suggestUsername(name: String): String {
    val map = mapOf('ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u', 'â' to 'a', 'î' to 'i', 'û' to 'u')
    val base = name.lowercase(java.util.Locale.forLanguageTag("tr"))
        .map { map[it] ?: it }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), ".")
        .filter { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }
        .trim('.', '_', '-')
        .take(20)
    return base
}

// ------------------------------------------------------------------ QR kodu

internal fun qrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF1F1B18.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
internal fun QrImage(text: String, size: Dp) {
    val bitmap = remember(text) { runCatching { qrBitmap(text) }.getOrNull() }
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = "QR kodu",
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(size).clip(RoundedCornerShape(16.dp)),
        )
    }
}

// ---------------------------------------------------------------- pano / paylaş

/** Hassas metni panoya kopyalar (Android 13+ önizlemede gizlenir). */
internal fun copySensitive(context: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 24) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}

internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "Paylaş").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

// ----------------------------------------------------------------- diyaloglar

/** Kurtarma kodu: yalnızca bir kez gösterilir, "Kaydettim" işaretlenmeden kapanmaz. */
@Composable
internal fun RecoveryCodeDialog(code: String, onDone: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(false) }
    SecureWindow()
    AlertDialog(
        onDismissRequest = {},
        containerColor = Color.White,
        title = { Text("Kurtarma kodun", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Çıkış yaptığında ya da telefon değiştirdiğinde hesabına bu kodla girersin. " +
                        "Bir kağıda yaz ya da güvendiğin bir yere kaydet. Bir daha gösterilmez.",
                    color = Muted,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFF6EFE7)).padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(code, fontFamily = FontFamily.Monospace, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
                }
                TextButton(onClick = { copySensitive(context, "Tayfa kurtarma kodu", code) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Kopyala")
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { saved = !saved }) {
                    Checkbox(checked = saved, onCheckedChange = { saved = it })
                    Text("Kodu güvenli bir yere kaydettim", fontSize = 14.sp)
                }
            }
        },
        confirmButton = { TextButton(enabled = saved, onClick = onDone) { Text("Tamam") } },
    )
}

/** Kod + QR + paylaş: başka cihaza giriş, davet ve yöneticinin giriş bağlantısı için. */
@Composable
internal fun ShareCodeDialog(
    title: String,
    explanation: String,
    code: String,
    url: String?,
    shareMessage: String?,
    sensitive: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    if (sensitive) SecureWindow()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(explanation, color = Muted, fontSize = 14.sp)
                if (!url.isNullOrEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.border(1.dp, Line, RoundedCornerShape(18.dp)).padding(10.dp)) { QrImage(url, 200.dp) }
                }
                Spacer(Modifier.height(12.dp))
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink, letterSpacing = 2.sp)
                Row(horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { copySensitive(context, title, url?.ifEmpty { null } ?: code) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Kopyala")
                    }
                    if (shareMessage != null) {
                        TextButton(onClick = { shareText(context, shareMessage) }) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Paylaş")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
    )
}

// ---------------------------------------------------------------- uygulama kilidi

/** Uygulama kilitliyken içeriğin üstünü kapatır; parmak izi ya da ekran kilidi ister. */
@Composable
internal fun LockScreen() {
    val activity = LocalContext.current as? FragmentActivity
    var tries by remember { mutableStateOf(0) }
    fun prompt() {
        val host = activity ?: run { Gate.locked.value = false; return }
        if (!Gate.lockAvailable()) {
            Gate.locked.value = false
            return
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Tayfa kilitli")
            .setSubtitle("Devam etmek için parmak izini ya da ekran kilidini kullan")
            .setAllowedAuthenticators(Gate.AUTHENTICATORS)
            .build()
        BiometricPrompt(
            host,
            androidx.core.content.ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Gate.locked.value = false
                }
            },
        ).authenticate(info)
    }
    LaunchedEffect(tries) { prompt() }
    Box(
        Modifier
            .fillMaxSize()
            .background(PageBrush)
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LogoMark(112.dp)
            Spacer(Modifier.height(18.dp))
            Text("Tayfa kilitli", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(28.dp))
            SandButton("Kilidi aç", icon = Icons.Outlined.Fingerprint, modifier = Modifier.width(220.dp)) { tries++ }
        }
    }
}

/** Kum renkli ana düğme. */
@Composable
internal fun SandButton(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    icon: ImageVector? = null,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .height(58.dp)
            .shadow(8.dp, shape, ambientColor = Color(0xFFB98A5E), spotColor = Color(0xFFB98A5E))
            .clip(shape)
            .background(SandBrush)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(color = SandInk, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = SandInk)
                    Spacer(Modifier.width(10.dp))
                }
                Text(text, color = SandInk, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Beyaz, ince çerçeveli ikincil düğme. */
@Composable
internal fun GhostButton(text: String, modifier: Modifier = Modifier.fillMaxWidth(), icon: ImageVector? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .height(58.dp)
            .shadow(3.dp, shape, ambientColor = Color(0x22000000), spotColor = Color(0x22000000))
            .clip(shape)
            .background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Ink)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
