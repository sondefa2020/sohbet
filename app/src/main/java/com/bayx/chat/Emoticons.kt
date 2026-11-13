package com.bayx.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.bayx.chat.data.Bayx
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Tayfa'nın özgün hareketli ifadeleri; MSN tarzı yazı kodlarıyla çağrılır. */
enum class Emo(val codes: List<String>, val label: String) {
    SMILE(listOf(":)", ":-)"), "Gülümseme"),
    LAUGH(listOf(":D", ":-D", ":d"), "Kahkaha"),
    WINK(listOf(";)", ";-)"), "Göz kırpma"),
    TONGUE(listOf(":P", ":p", ":-P"), "Dil çıkarma"),
    SAD(listOf(":(", ":-("), "Üzgün"),
    CRY(listOf(":'("), "Ağlama"),
    SURPRISED(listOf(":O", ":o", ":-O"), "Şaşkın"),
    COOL(listOf("(H)", "(h)", "8)"), "Havalı"),
    ANGRY(listOf(":@"), "Kızgın"),
    BLUSH(listOf(":$"), "Utanma"),
    KISS(listOf("(K)", "(k)", ":*"), "Öpücük"),
    HEART(listOf("<3", "(L)", "(l)"), "Kalp"),
    THUMBS(listOf("(Y)", "(y)"), "Beğendim"),
    BROKEN(listOf("</3", "(U)", "(u)"), "Kırık kalp"),
    ;

    val code: String get() = codes.first()
}

private val allCodes: List<Pair<String, Emo>> =
    Emo.entries.flatMap { e -> e.codes.map { it to e } }.sortedByDescending { it.first.length }

/** Metni düz yazı ve ifade parçalarına ayırır. */
private fun tokenize(text: String): List<Any> {
    val out = ArrayList<Any>()
    val buf = StringBuilder()
    var i = 0
    while (i < text.length) {
        val match = allCodes.firstOrNull { (code, _) -> text.startsWith(code, i) }
        // "8)" gibi kodlar ancak kelime başında ifade sayılır (ör. "18)" sayı olarak kalır).
        val ok = match != null && (match.first[0].isLetterOrDigit().not() || i == 0 || !text[i - 1].isLetterOrDigit())
        if (match != null && ok) {
            if (buf.isNotEmpty()) {
                out.add(buf.toString())
                buf.clear()
            }
            out.add(match.second)
            i += match.first.length
        } else {
            buf.append(text[i])
            i++
        }
    }
    if (buf.isNotEmpty()) out.add(buf.toString())
    return out
}

/** Mesaj yalnızca tek bir ifadeden oluşuyorsa onu döndürür (büyük gösterilir). */
fun singleEmoticon(text: String): Emo? {
    val parts = tokenize(text.trim())
    return (parts.singleOrNull() as? Emo)
}

/** İfade kodlarını satır içinde hareketli ifadelere çeviren metin. */
@Composable
fun EmoticonText(text: String, color: Color, fontSize: TextUnit, modifier: Modifier = Modifier) {
    val parts = remember(text) { tokenize(text) }
    if (parts.none { it is Emo }) {
        Text(text, color = color, fontSize = fontSize, modifier = modifier)
        return
    }
    val inline = HashMap<String, InlineTextContent>()
    val annotated: AnnotatedString = buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            when (part) {
                is String -> append(part)
                is Emo -> {
                    val id = "emo$index"
                    appendInlineContent(id, part.code)
                    inline[id] = InlineTextContent(Placeholder(1.35.em, 1.35.em, PlaceholderVerticalAlign.TextCenter)) {
                        Emoticon(part, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    Text(annotated, color = color, fontSize = fontSize, inlineContent = inline, modifier = modifier)
}

@Composable
fun Emoticon(emo: Emo, modifier: Modifier = Modifier, size: Dp? = null) {
    val transition = rememberInfiniteTransition(label = "ifade")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
        label = "zaman",
    )
    val m = if (size != null) modifier.size(size) else modifier
    Canvas(m) {
        when (emo) {
            Emo.HEART -> drawHeart(t, broken = false)
            Emo.BROKEN -> drawHeart(t, broken = true)
            Emo.THUMBS -> drawThumb(t)
            else -> drawFace(emo, t)
        }
    }
}

// ------------------------------------------------------------------ çizimler

private val FaceTop = Color(0xFFFFE55C)
private val FaceBottom = Color(0xFFF5B400)
private val FaceLine = Color(0xFFB7791F)
private val FaceInk = Color(0xFF3B2A00)

private fun DrawScope.faceBase(fill: List<Color> = listOf(FaceTop, FaceBottom)) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    drawCircle(Brush.verticalGradient(fill, startY = c.y - r, endY = c.y + r), r * 0.94f, c)
    drawCircle(FaceLine, r * 0.94f, c, style = Stroke(width = r * 0.07f))
    // MSN tarzı parlak üst yansıma
    drawOval(
        Color.White.copy(alpha = 0.45f),
        topLeft = Offset(c.x - r * 0.5f, c.y - r * 0.8f),
        size = Size(r * 0.95f, r * 0.45f),
    )
}

private fun DrawScope.eyes(r: Float, c: Offset, openLeft: Float = 1f, openRight: Float = 1f, dy: Float = 0f) {
    val ex = r * 0.32f
    val ey = c.y - r * 0.18f + dy
    val w = r * 0.15f
    val h = r * 0.26f
    if (openLeft > 0.2f) {
        drawOval(FaceInk, Offset(c.x - ex - w / 2, ey - h * openLeft / 2), Size(w, h * openLeft))
    } else {
        drawLine(FaceInk, Offset(c.x - ex - w, ey), Offset(c.x - ex + w, ey), strokeWidth = r * 0.07f, cap = StrokeCap.Round)
    }
    if (openRight > 0.2f) {
        drawOval(FaceInk, Offset(c.x + ex - w / 2, ey - h * openRight / 2), Size(w, h * openRight))
    } else {
        drawLine(FaceInk, Offset(c.x + ex - w, ey), Offset(c.x + ex + w, ey), strokeWidth = r * 0.07f, cap = StrokeCap.Round)
    }
}

private fun blink(t: Float): Float = if (t in 0.80f..0.86f) 0.1f else 1f

private fun DrawScope.smileArc(r: Float, c: Offset, depth: Float = 0.25f, width: Float = 0.5f, dy: Float = 0f) {
    val p = Path().apply {
        moveTo(c.x - r * width, c.y + r * 0.18f + dy)
        quadraticTo(c.x, c.y + r * (0.18f + depth * 2) + dy, c.x + r * width, c.y + r * 0.18f + dy)
    }
    drawPath(p, FaceInk, style = Stroke(width = r * 0.09f, cap = StrokeCap.Round))
}

private fun DrawScope.drawFace(emo: Emo, t: Float) {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    when (emo) {
        Emo.SMILE -> {
            faceBase()
            eyes(r, c, blink(t), blink(t))
            smileArc(r, c)
        }
        Emo.LAUGH -> {
            val bounce = -sin(t * 4 * PI).toFloat().let { if (it < 0) 0f else it } * r * 0.12f
            translate(top = bounce) {
                faceBase()
                eyes(r, c, 0.15f, 0.15f, dy = -r * 0.02f)
                val p = Path().apply {
                    moveTo(c.x - r * 0.48f, c.y + r * 0.12f)
                    lineTo(c.x + r * 0.48f, c.y + r * 0.12f)
                    quadraticTo(c.x, c.y + r * 0.95f, c.x - r * 0.48f, c.y + r * 0.12f)
                    close()
                }
                drawPath(p, FaceInk)
                drawOval(Color(0xFFE5484D), Offset(c.x - r * 0.2f, c.y + r * 0.42f), Size(r * 0.4f, r * 0.18f))
            }
        }
        Emo.WINK -> {
            faceBase()
            val wink = if (t in 0.35f..0.65f) 0.1f else 1f
            eyes(r, c, 1f, wink)
            smileArc(r, c, depth = 0.22f, width = 0.42f)
        }
        Emo.TONGUE -> {
            faceBase()
            eyes(r, c, blink(t), blink(t))
            smileArc(r, c, depth = 0.08f, width = 0.42f)
            val wag = sin(t * 6 * PI).toFloat() * r * 0.06f
            drawRoundRect(
                Color(0xFFE5484D),
                topLeft = Offset(c.x - r * 0.15f + wag, c.y + r * 0.3f),
                size = Size(r * 0.3f, r * 0.38f),
                cornerRadius = CornerRadius(r * 0.15f),
            )
        }
        Emo.SAD -> {
            val droop = sin(t * 2 * PI).toFloat() * r * 0.04f
            faceBase()
            eyes(r, c, 0.8f, 0.8f, dy = droop)
            val p = Path().apply {
                moveTo(c.x - r * 0.38f, c.y + r * 0.55f)
                quadraticTo(c.x, c.y + r * 0.2f, c.x + r * 0.38f, c.y + r * 0.55f)
            }
            drawPath(p, FaceInk, style = Stroke(width = r * 0.09f, cap = StrokeCap.Round))
        }
        Emo.CRY -> {
            faceBase()
            eyes(r, c, 0.25f, 0.25f)
            val p = Path().apply {
                moveTo(c.x - r * 0.35f, c.y + r * 0.55f)
                quadraticTo(c.x, c.y + r * 0.25f, c.x + r * 0.35f, c.y + r * 0.55f)
            }
            drawPath(p, FaceInk, style = Stroke(width = r * 0.09f, cap = StrokeCap.Round))
            // Düşen gözyaşları
            for (side in listOf(-1f, 1f)) {
                val phase = (t + if (side > 0) 0.5f else 0f) % 1f
                val y = c.y - r * 0.05f + phase * r * 0.75f
                drawCircle(
                    Color(0xFF4AA8FF).copy(alpha = 1f - phase * 0.7f),
                    r * 0.1f,
                    Offset(c.x + side * r * 0.32f, y),
                )
            }
        }
        Emo.SURPRISED -> {
            faceBase()
            eyes(r, c, 1.15f, 1.15f)
            val pulse = 1f + sin(t * 4 * PI).toFloat() * 0.12f
            drawOval(
                FaceInk,
                Offset(c.x - r * 0.14f * pulse, c.y + r * 0.22f),
                Size(r * 0.28f * pulse, r * 0.36f * pulse),
            )
        }
        Emo.COOL -> {
            faceBase()
            // Güneş gözlüğü
            val gy = c.y - r * 0.28f
            drawRoundRect(FaceInk, Offset(c.x - r * 0.68f, gy), Size(r * 0.6f, r * 0.34f), CornerRadius(r * 0.12f))
            drawRoundRect(FaceInk, Offset(c.x + r * 0.08f, gy), Size(r * 0.6f, r * 0.34f), CornerRadius(r * 0.12f))
            drawLine(FaceInk, Offset(c.x - r * 0.1f, gy + r * 0.08f), Offset(c.x + r * 0.1f, gy + r * 0.08f), r * 0.08f)
            // Kayan parıltı
            val sx = c.x - r * 0.7f + t * r * 1.6f
            drawLine(
                Color.White.copy(alpha = 0.7f),
                Offset(sx, gy + r * 0.3f),
                Offset(sx + r * 0.16f, gy + r * 0.02f),
                strokeWidth = r * 0.07f,
                cap = StrokeCap.Round,
            )
            smileArc(r, c, depth = 0.14f, width = 0.36f, dy = r * 0.05f)
        }
        Emo.ANGRY -> {
            val heat = (sin(t * 2 * PI).toFloat() + 1f) / 2f
            faceBase(listOf(Color(0xFFFF8A65).copy(alpha = 0.6f + heat * 0.4f).over(FaceTop), Color(0xFFE53935)))
            eyes(r, c, 0.7f, 0.7f, dy = r * 0.05f)
            drawLine(FaceInk, Offset(c.x - r * 0.55f, c.y - r * 0.45f), Offset(c.x - r * 0.15f, c.y - r * 0.28f), r * 0.09f, StrokeCap.Round)
            drawLine(FaceInk, Offset(c.x + r * 0.55f, c.y - r * 0.45f), Offset(c.x + r * 0.15f, c.y - r * 0.28f), r * 0.09f, StrokeCap.Round)
            val p = Path().apply {
                moveTo(c.x - r * 0.35f, c.y + r * 0.5f)
                quadraticTo(c.x, c.y + r * 0.3f, c.x + r * 0.35f, c.y + r * 0.5f)
            }
            drawPath(p, FaceInk, style = Stroke(width = r * 0.09f, cap = StrokeCap.Round))
        }
        Emo.BLUSH -> {
            faceBase()
            val glow = 0.35f + (sin(t * 2 * PI).toFloat() + 1f) * 0.2f
            drawCircle(Color(0xFFFF6B8A).copy(alpha = glow), r * 0.2f, Offset(c.x - r * 0.5f, c.y + r * 0.15f))
            drawCircle(Color(0xFFFF6B8A).copy(alpha = glow), r * 0.2f, Offset(c.x + r * 0.5f, c.y + r * 0.15f))
            // ^^ gözler
            for (side in listOf(-1f, 1f)) {
                val p = Path().apply {
                    moveTo(c.x + side * r * 0.32f - r * 0.13f, c.y - r * 0.12f)
                    lineTo(c.x + side * r * 0.32f, c.y - r * 0.26f)
                    lineTo(c.x + side * r * 0.32f + r * 0.13f, c.y - r * 0.12f)
                }
                drawPath(p, FaceInk, style = Stroke(width = r * 0.08f, cap = StrokeCap.Round))
            }
            smileArc(r, c, depth = 0.1f, width = 0.25f, dy = r * 0.08f)
        }
        Emo.KISS -> {
            faceBase()
            eyes(r, c, 0.15f, 1f)
            drawCircle(Color(0xFFE5484D), r * 0.12f, Offset(c.x - r * 0.04f, c.y + r * 0.38f))
            drawCircle(Color(0xFFE5484D), r * 0.12f, Offset(c.x + r * 0.08f, c.y + r * 0.38f))
            // Yükselen küçük kalp
            translate(left = c.x + r * 0.3f, top = c.y - r * 0.45f - t * r * 0.6f) {
                drawHeartAt(r * 0.5f, Color(0xFFE5484D).copy(alpha = 1f - t))
            }
        }
        else -> faceBase()
    }
}

private fun Color.over(background: Color): Color {
    val a = alpha + background.alpha * (1 - alpha)
    return Color(
        (red * alpha + background.red * background.alpha * (1 - alpha)) / a,
        (green * alpha + background.green * background.alpha * (1 - alpha)) / a,
        (blue * alpha + background.blue * background.alpha * (1 - alpha)) / a,
        a,
    )
}

private fun DrawScope.heartPath(): Path {
    val w = size.width
    val h = size.height
    return Path().apply {
        moveTo(w / 2f, h * 0.88f)
        cubicTo(w * 0.05f, h * 0.55f, w * 0.0f, h * 0.18f, w * 0.27f, h * 0.12f)
        cubicTo(w * 0.40f, h * 0.09f, w * 0.48f, h * 0.18f, w / 2f, h * 0.27f)
        cubicTo(w * 0.52f, h * 0.18f, w * 0.60f, h * 0.09f, w * 0.73f, h * 0.12f)
        cubicTo(w * 1.0f, h * 0.18f, w * 0.95f, h * 0.55f, w / 2f, h * 0.88f)
        close()
    }
}

private fun DrawScope.drawHeartShape(color: Color) {
    drawPath(heartPath(), Brush.verticalGradient(listOf(color.copy(alpha = color.alpha), Color(0xFFB0102E).copy(alpha = color.alpha))))
    drawOval(Color.White.copy(alpha = 0.35f * color.alpha), Offset(size.width * 0.22f, size.height * 0.2f), Size(size.width * 0.2f, size.height * 0.13f))
}

private fun DrawScope.drawHeart(t: Float, broken: Boolean) {
    val beat = 1f + (if (t < 0.15f) sin(t / 0.15f * PI).toFloat() * 0.14f else if (t in 0.2f..0.35f) sin((t - 0.2f) / 0.15f * PI).toFloat() * 0.1f else 0f)
    scale(beat) {
        if (!broken) {
            drawHeartShape(Color(0xFFFF3B5C))
        } else {
            val gap = sin(t * 2 * PI).toFloat().coerceAtLeast(0f) * size.width * 0.06f
            val full = heartPath()
            rotate(-8f) {
                translate(left = -gap) {
                    clipLeftHalf { drawPath(full, Color(0xFF8E2A3E)) }
                }
            }
            rotate(8f) {
                translate(left = gap) {
                    clipRightHalf { drawPath(full, Color(0xFF8E2A3E)) }
                }
            }
        }
    }
}

private inline fun DrawScope.clipLeftHalf(block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipRect(0f, 0f, size.width / 2f, size.height)
    block()
    drawContext.canvas.restore()
}

private inline fun DrawScope.clipRightHalf(block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipRect(size.width / 2f, 0f, size.width, size.height)
    block()
    drawContext.canvas.restore()
}

private fun DrawScope.drawThumb(t: Float) {
    val tilt = sin(t * 4 * PI).toFloat() * 10f
    val w = size.width
    val h = size.height
    rotate(tilt, pivot = Offset(w * 0.5f, h * 0.85f)) {
        // Avuç
        drawRoundRect(
            Brush.verticalGradient(listOf(FaceTop, FaceBottom)),
            Offset(w * 0.22f, h * 0.42f),
            Size(w * 0.56f, h * 0.48f),
            CornerRadius(w * 0.12f),
        )
        drawRoundRect(FaceLine, Offset(w * 0.22f, h * 0.42f), Size(w * 0.56f, h * 0.48f), CornerRadius(w * 0.12f), style = Stroke(w * 0.05f))
        // Başparmak
        drawRoundRect(
            Brush.verticalGradient(listOf(FaceTop, FaceBottom)),
            Offset(w * 0.30f, h * 0.06f),
            Size(w * 0.22f, h * 0.48f),
            CornerRadius(w * 0.11f),
        )
        drawRoundRect(FaceLine, Offset(w * 0.30f, h * 0.06f), Size(w * 0.22f, h * 0.48f), CornerRadius(w * 0.11f), style = Stroke(w * 0.05f))
        // Parmak çizgileri
        for (i in 1..3) {
            val y = h * 0.42f + i * h * 0.12f
            drawLine(FaceLine, Offset(w * 0.5f, y), Offset(w * 0.76f, y), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
        }
    }
}

// --------------------------------------------------------------- efektler

private class Particle(
    val x: Float,
    val delay: Float,
    val speed: Float,
    val sway: Float,
    val size: Float,
    val spin: Float,
    val color: Color,
    val angle: Float,
)

private val ConfettiColors = listOf(
    Color(0xFFFF3B5C), Color(0xFF3DDBC0), Color(0xFF4AA8FF), Color(0xFFFFC53D), Color(0xFF9B6BFF), Color(0xFF22C55E),
)

/** Tüm ekranın üstünde kısa süre oynayan sohbet efekti (dokunuşları engellemez). */
@Composable
fun EffectOverlay() {
    var current by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(Unit) {
        Bayx.effects.collect { name -> current = name to System.nanoTime() }
    }
    val active = current ?: return
    val progress = remember(active) { Animatable(0f) }
    LaunchedEffect(active) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(4200, easing = LinearEasing))
        delay(50)
        if (current == active) current = null
    }
    val particles = remember(active) {
        val rnd = Random(active.second)
        val count = when (active.first) {
            "havaifisek" -> 90
            "kar" -> 70
            else -> 60
        }
        List(count) {
            Particle(
                x = rnd.nextFloat(),
                delay = rnd.nextFloat() * 0.45f,
                speed = 0.6f + rnd.nextFloat() * 0.8f,
                sway = rnd.nextFloat() * 2f - 1f,
                size = 0.6f + rnd.nextFloat() * 0.9f,
                spin = rnd.nextFloat() * 720f - 360f,
                color = ConfettiColors[rnd.nextInt(ConfettiColors.size)],
                angle = rnd.nextFloat() * 2f * PI.toFloat(),
            )
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = progress.value
        val fade = if (p > 0.85f) (1f - p) / 0.15f else 1f
        when (active.first) {
            "havaifisek" -> drawFireworks(particles, p, fade)
            else -> particles.forEach { part ->
                val local = ((p - part.delay) / (1f - part.delay)).coerceIn(0f, 1f) * part.speed
                if (local <= 0f) return@forEach
                val y = -60f + local * (size.height + 120f)
                val x = part.x * size.width + sin(local * 6f + part.sway * 3f) * 40f * part.sway
                when (active.first) {
                    "kalp" -> {
                        val s = 26.dp.toPx() * part.size
                        translate(x - s / 2, y - s / 2) {
                            drawHeartAt(s, Color(0xFFFF3B5C).copy(alpha = fade))
                        }
                    }
                    "kar" -> drawCircle(Color.White.copy(alpha = 0.9f * fade), 4.dp.toPx() * part.size, Offset(x, y))
                    else -> rotate(part.spin * local, pivot = Offset(x, y)) {
                        drawRect(
                            part.color.copy(alpha = fade),
                            topLeft = Offset(x - 5.dp.toPx() * part.size, y - 3.dp.toPx() * part.size),
                            size = Size(10.dp.toPx() * part.size, 6.dp.toPx() * part.size),
                        )
                    }
                }
            }
        }
        if (active.first == "kar") {
            drawRect(Color(0xFF9EC9FF).copy(alpha = 0.10f * fade))
        }
    }
}

private fun DrawScope.drawHeartAt(s: Float, color: Color) {
    val path = Path().apply {
        moveTo(s / 2f, s * 0.88f)
        cubicTo(s * 0.05f, s * 0.55f, 0f, s * 0.18f, s * 0.27f, s * 0.12f)
        cubicTo(s * 0.40f, s * 0.09f, s * 0.48f, s * 0.18f, s / 2f, s * 0.27f)
        cubicTo(s * 0.52f, s * 0.18f, s * 0.60f, s * 0.09f, s * 0.73f, s * 0.12f)
        cubicTo(s, s * 0.18f, s * 0.95f, s * 0.55f, s / 2f, s * 0.88f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawFireworks(particles: List<Particle>, p: Float, fade: Float) {
    val centers = listOf(Offset(0.25f, 0.3f), Offset(0.72f, 0.25f), Offset(0.5f, 0.55f))
    particles.forEachIndexed { i, part ->
        val group = i % centers.size
        val start = group * 0.22f
        val local = ((p - start) / 0.55f).coerceIn(0f, 1f)
        if (local <= 0f || local >= 1f) return@forEachIndexed
        val center = Offset(centers[group].x * size.width, centers[group].y * size.height)
        val dist = local * size.minDimension * 0.32f * part.speed
        val pos = Offset(center.x + cos(part.angle) * dist, center.y + sin(part.angle) * dist + local * local * 80f)
        drawCircle(part.color.copy(alpha = (1f - local) * fade), 3.5.dp.toPx() * part.size, pos)
    }
}

/** İfade panelindeki efekt düğmeleri için küçük önizleme adı. */
fun effectTitle(name: String): String = when (name) {
    "kalp" -> "Kalp yağmuru"
    "konfeti" -> "Konfeti"
    "kar" -> "Kar"
    "havaifisek" -> "Havai fişek"
    else -> name
}
