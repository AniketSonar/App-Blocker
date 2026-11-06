package com.example.focusguard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reusable building blocks. Every public name starts with "Fg" so it never clashes with other files. */

// ---- cards and text ----------------------------------------------------------------------------
@Composable
fun FgCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun FgSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Small rounded tag, tinted with its own color. */
@Composable
fun FgPill(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
fun FgPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth().height(54.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

// ---- app icon, bars, rings ----------------------------------------------------------------------------
@Composable
fun FgAppIcon(icon: ImageBitmap?, fallback: Color, size: Dp = 44.dp) {
    val shape = RoundedCornerShape(size * 0.28f)
    Box(Modifier.size(size).clip(shape)) {
        if (icon != null) {
            Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(fallback.copy(alpha = 0.35f)))
        }
    }
}

/** A thin progress line. fraction is 0..1. */
@Composable
fun FgThinBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color)
        )
    }
}

/** One horizontal bar split into colored parts (for example productive / neutral / distracting). */
@Composable
fun FgSegmentBar(parts: List<Pair<Color, Long>>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val visible = parts.filter { it.second > 0 }
    if (visible.isEmpty()) return
    Row(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        visible.forEach { (color, value) ->
            Box(Modifier.weight(value.toFloat()).fillMaxHeight().background(color))
        }
    }
}

@Composable
fun FgScoreRing(
    score: Int,
    ringColor: Color,
    trackColor: Color,
    textColor: Color,
    diameter: Dp = 92.dp,
    caption: String = "focus"
) {
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
            drawArc(ringColor, -90f, 360f * score / 100f, false, Offset(inset, inset), arcSize, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", style = MaterialTheme.typography.headlineSmall, color = textColor)
            Text(caption, fontSize = 10.sp, color = textColor.copy(alpha = 0.7f))
        }
    }
}

// ---- the calm "breathing" circle used on the pause and block screens -------------------------------------
@Composable
fun FgBreathing(color: Color, diameter: Dp = 200.dp) {
    val transition = rememberInfiniteTransition(label = "breath")
    val scale by transition.animateFloat(
        initialValue = 0.80f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    Canvas(Modifier.size(diameter)) {
        val r = this.size.minDimension / 2
        drawCircle(color.copy(alpha = 0.08f), radius = r * scale)
        drawCircle(color.copy(alpha = 0.14f), radius = r * 0.74f * scale)
        drawCircle(color.copy(alpha = 0.26f), radius = r * 0.48f * scale)
    }
}

// ---- selectable row (used in dialogs and the pause screen) -------------------------------------------------
@Composable
fun FgChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.dp, if (selected) primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// ---- bottom navigation icons, drawn by hand (no icon library needed) --------------------------------------
enum class FgGlyph { GRID, BARS, TIMER }

@Composable
fun FgNavGlyph(kind: FgGlyph, tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = this.size.width / 24f
        when (kind) {
            FgGlyph.GRID -> {
                val corner = CornerRadius(2.5f * s)
                listOf(2f to 2f, 13f to 2f, 2f to 13f, 13f to 13f).forEach { (x, y) ->
                    drawRoundRect(tint, Offset(x * s, y * s), Size(9f * s, 9f * s), corner)
                }
            }
            FgGlyph.BARS -> {
                val corner = CornerRadius(2f * s)
                drawRoundRect(tint, Offset(3f * s, 11f * s), Size(4.5f * s, 10f * s), corner)
                drawRoundRect(tint, Offset(9.75f * s, 4f * s), Size(4.5f * s, 17f * s), corner)
                drawRoundRect(tint, Offset(16.5f * s, 8f * s), Size(4.5f * s, 13f * s), corner)
            }
            FgGlyph.TIMER -> {
                val stroke = Stroke(width = 2.2f * s, cap = StrokeCap.Round)
                drawCircle(tint, radius = 9f * s, center = Offset(12f * s, 12f * s), style = stroke)
                drawLine(tint, Offset(12f * s, 12f * s), Offset(12f * s, 6.5f * s), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
                drawLine(tint, Offset(12f * s, 12f * s), Offset(15.5f * s, 14f * s), strokeWidth = 2.2f * s, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
fun FgPauseGlyph(tint: Color, size: Dp = 30.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val corner = CornerRadius(w * 0.1f)
        drawRoundRect(tint, Offset(w * 0.22f, h * 0.15f), Size(w * 0.2f, h * 0.7f), corner)
        drawRoundRect(tint, Offset(w * 0.58f, h * 0.15f), Size(w * 0.2f, h * 0.7f), corner)
    }
}

@Composable
fun FgDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
