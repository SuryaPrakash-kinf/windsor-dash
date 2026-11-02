package com.spk.windsordash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Windsor Dash visual language: a dark instrument cluster.
 * Near-black blue background, cards lifted by a hairline border and a faint top glow,
 * one electric teal accent for charge, amber for power drawn, green for energy coming back.
 * Numbers are big, light and tabular; labels are small, spaced capitals.
 */

internal val Bg = Color(0xFF070A0F)
internal val Card = Color(0xFF0F151D)
internal val CardHi = Color(0xFF151D27)
internal val Line = Color(0xFF1E2935)
internal val Dim = Color(0xFF8593A5)
internal val Faint = Color(0xFF55636F)
internal val Ink = Color(0xFFF2F5F8)
internal val Teal = Color(0xFF2DD4BF)
internal val Cyan = Color(0xFF38BDF8)
internal val Amber = Color(0xFFFBBF24)
internal val Orange = Color(0xFFFB923C)
internal val Red = Color(0xFFF87171)
internal val Green = Color(0xFF4ADE80)
internal val Ice = Color(0xFF7DD3FC)

/** Tabular figures so live numbers don't jitter sideways as digits change. */
internal val Num = TextStyle(fontFeatureSettings = "tnum")

/** Small spaced capitals used for every label. */
@Composable
internal fun Label(text: String, color: Color = Dim, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium, modifier = modifier)
}

/** A number with a smaller unit after it, baseline aligned. */
@Composable
internal fun Value(
    value: String, unit: String = "", size: TextUnit = 22.sp, color: Color = Ink,
    weight: FontWeight = FontWeight.SemiBold, modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.Bottom, modifier = modifier) {
        Text(value, color = color, fontSize = size, fontWeight = weight, style = Num, maxLines = 1)
        if (unit.isNotEmpty()) Text(
            " $unit", color = Dim, fontSize = (size.value * 0.48f).coerceAtLeast(11f).sp,
            modifier = Modifier.padding(bottom = (size.value * 0.12f).dp), maxLines = 1,
        )
    }
}

/** Card: lifted surface with a hairline border. An accent colour tints the top edge. */
@Composable
internal fun Panel(accent: Color? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(CardHi, Card), endY = 260f))
            .border(1.dp, Line, shape)
    ) {
        if (accent != null) Box(
            Modifier.fillMaxWidth().height(2.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.9f), Color.Transparent)))
        )
        Column(Modifier.padding(18.dp)) { content() }
    }
}

/** Card heading: coloured dot + spaced label, optional thing on the right. */
@Composable
internal fun Heading(title: String, dot: Color = Teal, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Label(title, color = Ink.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
        trailing()
    }
    Spacer(Modifier.height(14.dp))
}

/** Small rounded tile: label over value. */
@Composable
internal fun RowScope.Tile(label: String, value: String, unit: String = "", color: Color = Ink, sub: String? = null) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF))
            .border(1.dp, Color(0x0AFFFFFF), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Label(label)
        Spacer(Modifier.height(6.dp))
        Value(value, unit, size = 22.sp, color = color)
        if (sub != null) Text(sub, color = Faint, fontSize = 11.sp, maxLines = 1)
    }
}

/** Two-column grid of label/value pairs (compact, for secondary figures). */
@Composable
internal fun Grid(vararg cells: Pair<String, String>) {
    cells.toList().chunked(2).forEachIndexed { i, row ->
        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x0DFFFFFF)))
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            row.forEach { (k, v) ->
                Column(Modifier.weight(1f)) {
                    Text(k, color = Dim, fontSize = 12.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(v, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, style = Num)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** Pill-shaped status: dot + text. */
@Composable
internal fun StatusPill(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(CircleShape).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Pill button for quick actions. [filled] = the main action. */
@Composable
internal fun PillButton(text: String, filled: Boolean = false, color: Color = Teal, onClick: () -> Unit) {
    val shape = CircleShape
    val m = Modifier.clip(shape).let {
        if (filled) it.background(Brush.horizontalGradient(listOf(color, Cyan)))
        else it.background(Color(0x0DFFFFFF)).border(1.dp, Line, shape)
    }.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp)
    Text(text, color = if (filled) Color(0xFF04201C) else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = m)
}

/** SoC colour: teal normally, amber when low, red when very low. */
internal fun socColor(soc: Double?): Color = when {
    soc == null -> Dim
    soc < 15 -> Red
    soc < 30 -> Amber
    else -> Teal
}

/** Temperature colour for a reading in °C. */
internal fun tempColor(c: Int?): Color = when {
    c == null -> Dim
    c < 15 -> Ice
    c < 38 -> Green
    c < 48 -> Amber
    else -> Orange
}

/**
 * The hero gauge: a 270° ring filled to SoC, with the number in the middle.
 * Charging adds a soft glow and a "charging" line under the number.
 */
@Composable
internal fun SocRing(soc: Double?, charging: Boolean, size: Dp = 176.dp) {
    val frac by animateFloatAsState(((soc ?: 0.0) / 100).toFloat().coerceIn(0f, 1f), tween(700), label = "soc")
    val c = socColor(soc)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size).rotate(135f)) {
            val w = 14.dp.toPx()
            val inset = w / 2 + 4.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val tl = Offset(inset, inset)
            // ticks every 10 %
            for (i in 0..10) {
                val a = Math.toRadians((i * 27.0))
                val r1 = this.size.width / 2 - 1.dp.toPx()
                val r0 = r1 - 3.dp.toPx()
                val cx = this.size.width / 2; val cy = this.size.height / 2
                drawLine(
                    Faint.copy(alpha = 0.6f),
                    Offset(cx + (r0 * kotlin.math.cos(a)).toFloat(), cy + (r0 * kotlin.math.sin(a)).toFloat()),
                    Offset(cx + (r1 * kotlin.math.cos(a)).toFloat(), cy + (r1 * kotlin.math.sin(a)).toFloat()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawArc(Line, 0f, 270f, false, tl, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            if (frac > 0f) {
                val brush = Brush.sweepGradient(
                    0f to c.copy(alpha = 0.55f), 0.75f * frac to c, 1f to c.copy(alpha = 0.55f),
                    center = Offset(this.size.width / 2, this.size.height / 2),
                )
                if (charging) drawArc(c.copy(alpha = 0.18f), 0f, 270f * frac, false, tl, arcSize, style = Stroke(w * 2.2f, cap = StrokeCap.Round))
                drawArc(brush, 0f, 270f * frac, false, tl, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(soc?.roundToInt()?.toString() ?: "–", color = Ink, fontSize = 54.sp, fontWeight = FontWeight.Light, style = Num)
                Text("%", color = Dim, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp, start = 1.dp))
            }
            Label(if (charging) "⚡ charging" else "charge", color = if (charging) Green else Dim)
        }
    }
}

/**
 * Power meter: zero in the middle, energy coming in (regen / charging) fills left in green,
 * power drawn fills right in amber. Scales to ±[maxKw].
 */
@Composable
internal fun PowerMeter(kw: Double?, maxKw: Double = 60.0) {
    val v by animateFloatAsState(((kw ?: 0.0) / maxKw).toFloat().coerceIn(-1f, 1f), tween(350), label = "kw")
    Column {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val h = size.height; val r = CornerRadius(h / 2, h / 2)
            drawRoundRect(Line, cornerRadius = r)
            val mid = size.width / 2
            if (abs(v) > 0.002f) {
                val len = abs(v) * mid
                if (v > 0) drawRoundRect(
                    Brush.horizontalGradient(listOf(Amber.copy(alpha = 0.6f), Orange), startX = mid, endX = mid + len),
                    topLeft = Offset(mid, 0f), size = Size(len, h), cornerRadius = r,
                ) else drawRoundRect(
                    Brush.horizontalGradient(listOf(Green, Teal.copy(alpha = 0.6f)), startX = mid - len, endX = mid),
                    topLeft = Offset(mid - len, 0f), size = Size(len, h), cornerRadius = r,
                )
            }
            drawLine(Ink.copy(alpha = 0.7f), Offset(mid, -2f), Offset(mid, h + 2f), strokeWidth = 2.dp.toPx())
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("◂ regen · charge", color = Faint, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("0", color = Faint, fontSize = 10.sp)
            Text("drive ▸", color = Faint, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

/** Highest vs lowest cell as a short bar on a fixed 0–60 mV scale, coloured by spread. */
@Composable
internal fun SpreadBar(spreadMv: Int?) {
    val f = ((spreadMv ?: 0) / 60f).coerceIn(0f, 1f)
    val c = when { spreadMv == null -> Dim; spreadMv <= 20 -> Green; spreadMv <= 50 -> Amber; else -> Red }
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Line, cornerRadius = r)
        if (spreadMv != null) drawRoundRect(c, size = Size((size.width * f).coerceAtLeast(size.height), size.height), cornerRadius = r)
    }
}

/** Collapsible group for the technical panels, so the main screen stays clean. */
@Composable
internal fun Expander(title: String, subtitle: String, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open = !open }
                .border(1.dp, Line, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Faint, fontSize = 12.sp)
            }
            Text(if (open) "–" else "+", color = Teal, fontSize = 22.sp, fontWeight = FontWeight.Light)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}
