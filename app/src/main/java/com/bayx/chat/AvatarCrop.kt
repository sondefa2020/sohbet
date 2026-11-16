package com.bayx.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Seçilen fotoğrafı kırpmaya hazırlar: küçültür ve telefonun kayıt yönüne (EXIF) göre düz çevirir. */
internal fun loadForCrop(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
    val src = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return@runCatching null
    val degrees = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            when (android.media.ExifInterface(input).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)
    if (degrees == 0f) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees) }, true)
}.getOrNull()

private fun rotate90(b: Bitmap): Bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(90f) }, true)

/**
 * Profil fotoğrafı kırpma (WhatsApp gibi): iki parmakla yakınlaştır, sürükleyerek konumla,
 * istersen döndür; daire içindeki alan profil fotoğrafı olur.
 */
@Composable
fun AvatarCropDialog(source: Bitmap, onCancel: () -> Unit, onDone: (ByteArray) -> Unit) {
    var bitmap by remember(source) { mutableStateOf(source) }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    // Daire çapı: ekranın kısa kenarının %82'si. Fotoğraf daireyi her zaman tam doldurur.
    val circle = min(area.width, area.height) * 0.82f
    val base = if (circle > 0f) max(circle / bitmap.width, circle / bitmap.height) else 1f
    fun clamp(o: Offset, z: Float): Offset {
        val s = base * z
        val maxX = max(0f, (bitmap.width * s - circle) / 2f)
        val maxY = max(0f, (bitmap.height * s - circle) / 2f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    fun crop(): ByteArray? = runCatching {
        val s = base * zoom
        val side = (circle / s).roundToInt().coerceAtMost(min(bitmap.width, bitmap.height))
        val left = (bitmap.width / 2f + (-circle / 2f - offset.x) / s).roundToInt().coerceIn(0, bitmap.width - side)
        val top = (bitmap.height / 2f + (-circle / 2f - offset.y) / s).roundToInt().coerceIn(0, bitmap.height - side)
        val scale = min(1f, 640f / side)
        val out = Bitmap.createBitmap(bitmap, left, top, side, side, Matrix().apply { postScale(scale, scale) }, true)
        java.io.ByteArrayOutputStream().use { stream ->
            out.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            stream.toByteArray()
        }
    }.getOrNull()

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Text(
                t("Fotoğrafı ayarla"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(18.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { area = it }
                    .pointerInput(bitmap, circle) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val z = (zoom * gestureZoom).coerceIn(1f, 5f)
                            zoom = z
                            offset = clamp(offset + pan, z)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val s = base * zoom
                Canvas(Modifier.fillMaxSize()) {
                    val w = bitmap.width * s
                    val h = bitmap.height * s
                    drawImage(
                        image,
                        dstOffset = IntOffset(
                            (size.width / 2f - w / 2f + offset.x).roundToInt(),
                            (size.height / 2f - h / 2f + offset.y).roundToInt(),
                        ),
                        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    )
                }
                // Daire dışı karartılır.
                Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                    drawRect(Color.Black.copy(alpha = 0.62f))
                    drawCircle(Color.Transparent, radius = circle / 2f, center = center, blendMode = BlendMode.Clear)
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = circle / 2f, center = center, style = Stroke(width = 2.dp.toPx()))
                }
            }
            Text(
                t("İki parmakla yakınlaştır, sürükleyerek ortala."),
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text(t("Vazgeç"), color = Color.White, fontSize = 16.sp) }
                IconButton(onClick = { bitmap = rotate90(bitmap) }) {
                    Icon(Icons.AutoMirrored.Outlined.RotateRight, t("Döndür"), tint = Color.White)
                }
                TextButton(onClick = { crop()?.let(onDone) }) {
                    Text(t("Bitti"), color = Color(0xFFE8B98C), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
