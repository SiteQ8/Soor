package com.eworldq8.soor.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eworldq8.soor.scan.DeviceKind
import kotlin.math.cos
import kotlin.math.sin

/** When true nothing loops, so a screenshot or a still frame gets one fixed moment. */
val LocalStill = staticCompositionLocalOf { false }

/** The same device drawings as the lab on the site, so the app and site speak one visual language. */
@Composable
fun DeviceGlyph(kind: DeviceKind, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { drawGlyph(kind, tint) }
        if (kind == DeviceKind.UNKNOWN) Text("?", color = tint, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

private fun DrawScope.drawGlyph(kind: DeviceKind, tint: Color) {
    val s = size.minDimension * 0.36f
    val c = center
    val w = size.minDimension * 0.075f
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun rr(x: Float, y: Float, width: Float, height: Float, r: Float) =
        drawRoundRect(tint, Offset(c.x + x, c.y + y), Size(width, height), CornerRadius(r, r), style = stroke)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, Offset(c.x + x1, c.y + y1), Offset(c.x + x2, c.y + y2), strokeWidth = w, cap = StrokeCap.Round)
    fun dot(x: Float, y: Float, r: Float) = drawCircle(tint, r, Offset(c.x + x, c.y + y))
    when (kind) {
        DeviceKind.ROUTER -> {
            rr(-s, -s * 0.1f, 2 * s, s * 0.8f, s * 0.2f)
            line(-s * 0.55f, -s * 0.1f, -s * 0.8f, -s * 0.9f)
            line(s * 0.55f, -s * 0.1f, s * 0.8f, -s * 0.9f)
            dot(-s * 0.45f, s * 0.3f, s * 0.1f); dot(0f, s * 0.3f, s * 0.1f); dot(s * 0.45f, s * 0.3f, s * 0.1f)
        }
        DeviceKind.CAMERA -> {
            rr(-s, -s * 0.5f, s * 1.45f, s, s * 0.22f)
            val p = Path().apply {
                moveTo(c.x + s * 0.45f, c.y - s * 0.2f); lineTo(c.x + s, c.y - s * 0.48f)
                lineTo(c.x + s, c.y + s * 0.48f); lineTo(c.x + s * 0.45f, c.y + s * 0.2f)
            }
            drawPath(p, tint, style = stroke)
            drawCircle(tint, s * 0.26f, Offset(c.x - s * 0.3f, c.y), style = stroke)
        }
        DeviceKind.TV -> { rr(-s, -s * 0.72f, 2 * s, s * 1.22f, s * 0.14f); line(-s * 0.4f, s * 0.82f, s * 0.4f, s * 0.82f) }
        DeviceKind.NAS -> {
            rr(-s * 0.75f, -s * 0.92f, s * 1.5f, s * 0.82f, s * 0.14f); rr(-s * 0.75f, s * 0.1f, s * 1.5f, s * 0.82f, s * 0.14f)
            dot(s * 0.4f, -s * 0.5f, s * 0.09f); dot(s * 0.4f, s * 0.52f, s * 0.09f)
        }
        DeviceKind.COMPUTER -> { rr(-s * 0.78f, -s * 0.78f, s * 1.56f, s * 1.08f, s * 0.1f); line(-s * 1.05f, s * 0.55f, s * 1.05f, s * 0.55f) }
        DeviceKind.PHONE -> { rr(-s * 0.5f, -s * 0.95f, s, s * 1.9f, s * 0.22f); dot(0f, s * 0.7f, s * 0.09f) }
        DeviceKind.PRINTER -> {
            rr(-s, -s * 0.3f, 2 * s, s * 0.95f, s * 0.16f); rr(-s * 0.6f, -s * 0.95f, s * 1.2f, s * 0.65f, s * 0.08f)
            line(-s * 0.5f, s * 0.9f, s * 0.5f, s * 0.9f)
        }
        DeviceKind.IOT -> {
            val p = Path()
            for (i in 0 until 6) {
                val a = Math.PI / 3 * i - Math.PI / 6
                val x = c.x + cos(a).toFloat() * s
                val y = c.y + sin(a).toFloat() * s
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close(); drawPath(p, tint, style = stroke); dot(0f, 0f, s * 0.28f)
        }
        DeviceKind.UNKNOWN -> drawCircle(tint, s * 0.95f, c, style = stroke)
    }
}

data class RadarDot(val angle: Float, val radius: Float, val color: Color)

/** The scan made visible: rings, a sweeping arm, and each device lighting up as it is found. */
@Composable
fun Radar(modifier: Modifier, sweeping: Boolean, dots: List<RadarDot> = emptyList()) {
    val still = LocalStill.current
    var sweep: State<Float>? = null
    var ring: State<Float>? = null
    if (!still) {
        val t = rememberInfiniteTransition(label = "radar")
        sweep = t.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "sweep")
        ring = t.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearOutSlowInEasing)), label = "ring")
    }
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        val sw = sweep?.value ?: 300f
        val rg = ring?.value ?: 0.45f
        drawCircle(Brush.radialGradient(listOf(Theme.navy.copy(alpha = 0.34f), Color.Transparent), center = c, radius = r), r, c)
        for (k in 1..3) drawCircle(Theme.navyLite.copy(alpha = 0.24f), r * k / 3f, c, style = Stroke(1.dp.toPx()))
        drawLine(Theme.navyLite.copy(alpha = 0.12f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(Theme.navyLite.copy(alpha = 0.12f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
        drawCircle(Theme.signal.copy(alpha = 0.42f * (1f - rg)), r * (0.25f + 0.75f * rg), c, style = Stroke(1.5.dp.toPx()))
        if (sweeping) rotate(sw, c) {
            drawCircle(Brush.sweepGradient(0f to Color.Transparent, 0.74f to Color.Transparent, 1f to Theme.signal.copy(alpha = 0.45f), center = c), r, c)
            drawLine(Theme.signal.copy(alpha = 0.95f), c, Offset(c.x + r, c.y), 2.dp.toPx(), cap = StrokeCap.Round)
        }
        dots.forEach { d ->
            val a = Math.toRadians(d.angle.toDouble())
            val p = Offset(c.x + cos(a).toFloat() * r * d.radius, c.y + sin(a).toFloat() * r * d.radius)
            drawCircle(d.color.copy(alpha = 0.22f), 11.dp.toPx(), p)
            drawCircle(d.color, 5.dp.toPx(), p)
        }
        if (sweeping) {
            drawCircle(Theme.navy, 8.dp.toPx(), c)
            drawCircle(Theme.ink.copy(alpha = 0.85f), 8.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** The rampart across the status banner: whole when the home is closed to the internet, breached when it is not. */
@Composable
fun WallStrip(breached: Boolean, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val h = size.height
        val body = h * 0.45f
        val mh = h * 0.42f
        val mw = h * 0.72f
        val gap = h * 0.5f
        val y = h - body
        drawRect(color, Offset(0f, y), Size(size.width, body))
        var x = 0f
        while (x < size.width) { drawRect(color, Offset(x, y - mh), Size(mw, mh + 1f)); x += mw + gap }
        if (breached) {
            val gw = h * 1.2f
            val bx = size.width / 2f - gw / 2f
            drawRect(Theme.bg, Offset(bx, y - mh - 1f), Size(gw, body + mh + 2f))
            drawRect(Theme.crit.copy(alpha = 0.55f), Offset(bx, y - mh - 1f), Size(gw, body + mh + 2f))
        }
    }
}
