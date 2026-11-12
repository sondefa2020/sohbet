package com.bayx.chat.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.math.max

class PreparedImage(val bytes: ByteArray, val width: Int, val height: Int)

class PreparedVideo(
    val bytes: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val thumbnail: ByteArray?,
    val thumbWidth: Int,
    val thumbHeight: Int,
)

class Recording(val bytes: ByteArray, val mime: String, val durationMs: Long, val waveform: List<Float>)

/** Bellekteki (çözülmüş) medyayı diske yazmadan oynatmak için veri kaynağı. */
class ByteArrayDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val count = minOf(size, (data.size - position).toInt())
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() = Unit
}

object MediaTools {
    const val MAX_VIDEO_BYTES = 50 * 1024 * 1024

    /** Kamera çekimi için uygulamanın özel önbelleğinde geçici dosya. Galeriye yazılmaz. */
    fun newCameraFile(context: Context): File {
        val dir = File(context.cacheDir, "cekim").apply { mkdirs() }
        return File(dir, "cekim-" + System.currentTimeMillis() + ".jpg")
    }

    fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun toJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun scaled(bitmap: Bitmap, maxSide: Int, rotation: Int = 0): Bitmap {
        val scale = maxSide.toFloat() / max(bitmap.width, bitmap.height)
        if (scale >= 1f && rotation == 0) return bitmap
        val matrix = Matrix()
        if (scale < 1f) matrix.postScale(scale, scale)
        if (rotation != 0) matrix.postRotate(rotation.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Seçilen ya da çekilen fotoğrafı küçültür, döndürür ve JPEG'e çevirir (konum gibi EXIF verileri atılır). */
    fun prepareImage(context: Context, uri: Uri): PreparedImage? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 3840) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val rotation = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
        val bitmap = scaled(decoded, 1920, rotation)
        PreparedImage(toJpeg(bitmap, 85), bitmap.width, bitmap.height)
    }.getOrNull()

    private fun readLimited(stream: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    /** Videoyu belleğe okur (en fazla [MAX_VIDEO_BYTES]) ve bir kapak karesi çıkarır. */
    fun prepareVideo(context: Context, uri: Uri): PreparedVideo? = runCatching {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { readLimited(it, MAX_VIDEO_BYTES) } ?: return null
        var width = 0
        var height = 0
        var duration = 0L
        var thumbnail: ByteArray? = null
        var thumbWidth = 0
        var thumbHeight = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun meta(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            if (rotation == 90 || rotation == 270) {
                val swap = width
                width = height
                height = swap
            }
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.getFrameAtTime(0)?.let { frame ->
                val small = scaled(frame, 640)
                thumbnail = toJpeg(small, 80)
                thumbWidth = small.width
                thumbHeight = small.height
            }
        } catch (e: Exception) {
            // Üst veri okunamasa da video gönderilebilir.
        } finally {
            runCatching { retriever.release() }
        }
        PreparedVideo(bytes, resolver.getType(uri) ?: "video/mp4", width, height, duration, thumbnail, thumbWidth, thumbHeight)
    }.getOrNull()

    // ------------------------------------------------------------------ konum

    private fun providerRank(provider: String) = when (provider) {
        "fused" -> 0
        LocationManager.NETWORK_PROVIDER -> 1
        LocationManager.GPS_PROVIDER -> 2
        else -> 3
    }

    /** İzin verilmiş olmalı. Yakın zamanlı bir konum varsa onu, yoksa tek seferlik yeni konumu döndürür. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
            .filter { it != LocationManager.PASSIVE_PROVIDER }
            .sortedBy { providerRank(it) }
        if (providers.isEmpty()) return null
        val known = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        val now = System.currentTimeMillis()
        known.filter { now - it.time < 2 * 60_000 }.minByOrNull { it.accuracy }?.let { return it }

        for (provider in providers) {
            val location = withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val signal = CancellationSignal()
                            cont.invokeOnCancellation { signal.cancel() }
                            manager.getCurrentLocation(provider, signal, context.mainExecutor) { result ->
                                if (cont.isActive) cont.resume(result)
                            }
                        } else {
                            val listener = object : LocationListener {
                                override fun onLocationChanged(location: Location) {
                                    if (cont.isActive) cont.resume(location)
                                }

                                @Deprecated("Eski Android sürümleri için gerekli")
                                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                                override fun onProviderEnabled(provider: String) = Unit

                                override fun onProviderDisabled(provider: String) = Unit
                            }
                            cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                            @Suppress("DEPRECATION")
                            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
            if (location != null) return location
        }
        return known.maxByOrNull { it.time }
    }
}

/** Sesli mesaj kaydı. Geçici dosya uygulamanın özel önbelleğindedir ve gönderimden hemen sonra silinir. */
class VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L
    private var mime = "audio/ogg"
    private val samples = ArrayList<Float>()

    fun start(context: Context): Boolean {
        cancel()
        return try {
            val dir = File(context.cacheDir, "kayit").apply { mkdirs() }
            val ogg = Build.VERSION.SDK_INT >= 29
            val target = File(dir, if (ogg) "kayit.ogg" else "kayit.m4a")
            target.delete()
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (ogg) {
                r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                r.setAudioSamplingRate(48_000)
                r.setAudioEncodingBitRate(32_000)
                mime = "audio/ogg"
            } else {
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioSamplingRate(44_100)
                r.setAudioEncodingBitRate(64_000)
                mime = "audio/mp4"
            }
            r.setAudioChannels(1)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = target
            samples.clear()
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            cancel()
            false
        }
    }

    /** Dalga biçimi için anlık ses düzeyini kaydeder; düzenli aralıklarla çağrılır. */
    fun sample() {
        val r = recorder ?: return
        runCatching { samples.add((r.maxAmplitude / 32767f).coerceIn(0f, 1f)) }
    }

    fun elapsedSeconds(): Int =
        if (recorder == null) 0 else ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()

    /** Kaydı bitirir. Çok kısa ya da bozuk kayıtlar için null döner. */
    fun stop(): Recording? {
        val r = recorder ?: return null
        val target = file
        val duration = SystemClock.elapsedRealtime() - startedAt
        val stopped = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        recorder = null
        file = null
        val bytes = if (stopped && target != null && duration >= 700) runCatching { target.readBytes() }.getOrNull() else null
        target?.delete()
        if (bytes == null || bytes.isEmpty()) return null
        return Recording(bytes, mime, duration, samples.toList())
    }

    fun cancel() {
        val r = recorder
        if (r != null) {
            runCatching { r.stop() }
            runCatching { r.release() }
        }
        recorder = null
        file?.delete()
        file = null
    }
}
