package com.bayx.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bayx.chat.data.Bayx
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** WhatsApp Web tarzı: web'deki QR'ı okut ya da 6 haneli kodu gir, web oturumu açılsın. */
@Composable
fun WebLinkScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = rememberToast()

    var scanning by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Onay bekleyen bağlama isteği: (pairingId, kod)
    var pending by remember { mutableStateOf<Pair<String?, String?>?>(null) }

    fun approve(pairingId: String?, enteredCode: String?) {
        if (busy || done) return
        busy = true
        error = null
        scope.launch {
            val err = Bayx.approveWeb(pairingId, enteredCode)
            busy = false
            if (err == null) {
                done = true
                scanning = false
            } else {
                error = err
                scanning = false
            }
        }
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else toast("QR okumak için kamera izni gerekli.")
    }

    fun startScan() {
        error = null
        val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (ok) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    if (scanning) {
        QrScanner(
            onResult = { text ->
                scanning = false
                if (text.startsWith("tayfaweb:")) pending = text to null
                else error = t("Bu bir Tayfa Web kodu değil.")
            },
            onCancel = { scanning = false },
        )
        return
    }

    pending?.let { req ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(t("Bu bilgisayarı bağla?")) },
            text = {
                Text(t("Onaylarsan o bilgisayardaki tarayıcı hesabına girer ve mesajlarını görebilir. Yalnızca kendi açtığın ekrandaki kodu onayla; başkasının gönderdiği QR ya da kodu asla onaylama."))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { pending = null; approve(req.first, req.second) }) { Text(t("Bağla")) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pending = null }) { Text(t("Vazgeç")) }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(PageBrush)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Ink) }
            Text("Web'e bağlan", color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(6.dp))

        if (done) {
            SoftCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✓", color = Color(0xFF2E8B6E), fontSize = 46.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Web oturumu açıldı", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t("Bilgisayarındaki Tayfa Web birkaç saniye içinde açılır."),
                        color = SoftBlue, fontSize = 14.sp, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    SandButton("Tamam") { onBack() }
                }
            }
            return@Column
        }

        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    t("Bilgisayarda web adresini aç. Ekranda bir QR kod ve 6 haneli numara göreceksin."),
                    color = SoftBlue, fontSize = 14.5.sp,
                )
                Spacer(Modifier.height(18.dp))
                SandButton("QR Kodu Tara", icon = Icons.Outlined.QrCodeScanner, busy = busy) { startScan() }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("ya da 6 haneli kodu gir", color = SoftBlue, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))

        PillField(
            value = code,
            onValueChange = { code = it.filter { ch -> ch.isDigit() }.take(6) },
            placeholder = "000000",
            icon = Icons.Outlined.Dialpad,
            keyboardType = KeyboardType.Number,
        )
        Spacer(Modifier.height(12.dp))
        SandButton("Onayla", busy = busy) {
            if (code.length == 6) pending = null to code else toast(t("6 haneli kodu gir."))
        }

        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(error!!, color = Color(0xFFB4232A), fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp))
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** CameraX + zxing ile QR okuyucu; "tayfaweb:<id>" yüklü QR'ı yakalayınca onResult çağrılır. */
@Composable
private fun QrScanner(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var delivered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor, QrAnalyzer { text ->
                        if (!delivered) {
                            delivered = true
                            previewView.post { onResult(text) }
                        }
                    })
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                        )
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        // Hedef çerçeve
        Box(
            Modifier
                .align(Alignment.Center)
                .size(240.dp)
                .border(3.dp, Color.White, RoundedCornerShape(22.dp)),
        )

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                t("QR kodu çerçeveye hizala"),
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(18.dp))
            GhostButton(t("Vazgeç"), modifier = Modifier.fillMaxWidth(0.6f)) { onCancel() }
        }

        IconButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.TopStart).systemBarsPadding().padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri", tint = Color.White)
        }
    }
}

private class QrAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (image.format == android.graphics.ImageFormat.YUV_420_888) {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val height = image.height
                val width = image.width
                val need = rowStride * height
                val data = ByteArray(need)
                buffer.rewind()
                val n = minOf(buffer.remaining(), need)
                buffer.get(data, 0, n)
                val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
                val bitmap = BinaryBitmap(HybridBinarizer(source))
                val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
                if (result != null && result.text != null) onFound(result.text)
            }
        } catch (_: Exception) {
            // kare başına hata yut
        } finally {
            reader.reset()
            image.close()
        }
    }
}
