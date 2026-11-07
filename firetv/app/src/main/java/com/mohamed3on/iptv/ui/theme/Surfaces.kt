package com.mohamed3on.iptv.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.tv.material3.Text

/*
 * Materials and depth. API 30 has no blur, and nothing can blur a video SurfaceView, so the
 * "glass" is a tinted translucent layer plus gradient scrims that darken the picture only
 * where chrome sits. Bigger surfaces read heavier (more opaque, rounder); the focused row is
 * the lightest layer, so it reads as closest. Never stack one translucent surface on another.
 *
 * The stick's GPU is small and shares memory with a 4K decode, so every surface here is drawn
 * in one pass: no offscreen layers, no clips where a rounded fill will do.
 */

/** A floating chrome panel: translucent material with a faint lit edge. */
fun Modifier.material(radius: Dp = Radius.panel): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(Palette.material).border(1.dp, Palette.edge, shape)
}

/**
 * The channel list's scrim under its columns: see-through enough to follow the picture behind,
 * dark enough that secondary text keeps ~4:1 even over a white sky.
 */
val ListScrim = Color(0xB3000000)

/** Where the channel columns end, as a fraction of the screen's width. */
private const val COLUMNS_END = 0.91f

/**
 * Darkens the video behind the channel list: one flat tint under the columns, easing off past them
 * toward the right edge. Flat on purpose: a gradient across the whole screen cost the stick's GPU
 * more than everything else in a frame (rows scrolled at 36ms a frame over it, 9ms over a flat tint),
 * so the gradient covers only the strip the rows never reach. [alpha] is read while drawing, so the
 * scrim fades with no layer and no recomposition.
 */
fun Modifier.leftScrim(alpha: () -> Float = { 1f }): Modifier = drawWithCache {
    val solidTo = size.width * COLUMNS_END
    val tail = Brush.horizontalGradient(0f to ListScrim, 1f to Color(0x59000000), startX = solidTo, endX = size.width)
    onDrawBehind {
        val a = alpha().coerceIn(0f, 1f)
        drawRect(ListScrim, size = Size(solidTo, size.height), alpha = a)
        drawRect(tail, topLeft = Offset(solidTo, 0f), size = Size(size.width - solidTo, size.height), alpha = a)
    }
}

/**
 * Darkens the video behind the banner, from the bottom edge up: dark enough by the channel name that
 * the picture's own captions never read through it. [alpha] is read while drawing.
 */
fun Modifier.bottomScrim(alpha: () -> Float = { 1f }): Modifier = drawWithCache {
    val brush = Brush.verticalGradient(0f to Color.Transparent, 0.4f to Color(0xB3000000), 1f to Color(0xEB000000))
    onDrawBehind { drawRect(brush, alpha = alpha().coerceIn(0f, 1f)) }
}

/** Dims everything behind a modal surface (quick panel, still-watching prompt). */
val Scrim = Color(0x99000000)

/** Darkens the foot of a wide card's picture, so its name and progress read over any picture. */
val CardScrim = Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xE6000000))

/** Darkens a genre tile's poster from the middle down: dark enough at the foot that its name reads over the poster's own lettering. */
val TileScrim = Brush.verticalGradient(0.25f to Color.Transparent, 0.6f to Color(0xB3000000), 1f to Color(0xF2000000))

/**
 * Focus as depth: the focused item lifts toward the viewer (scale + the raised surface),
 * edged by a thin soft-white ring at ≥3:1 contrast. It springs on the same beat as the key press and
 * redirects smoothly when focus moves on mid-animation. One spring drives all three, read only
 * while drawing, so a focus move repaints two rows and recomposes nothing.
 */
@Composable
fun Modifier.focusLift(focused: Boolean, radius: Dp = Radius.row): Modifier {
    val reduced = LocalReducedMotion.current
    val lift = animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "lift")
    return graphicsLayer {
        val s = if (reduced) 1f else 1f + (Motion.FOCUS_SCALE - 1f) * lift.value
        scaleX = s
        scaleY = s
    }.drawBehind {
        val p = lift.value.coerceIn(0f, 1f)
        if (p == 0f) return@drawBehind
        val r = radius.toPx()
        val ring = 1.5.dp.toPx()
        drawRoundRect(Palette.raised, cornerRadius = CornerRadius(r), alpha = p)
        drawRoundRect(
            Palette.focusRing, topLeft = Offset(ring / 2, ring / 2), size = Size(size.width - ring, size.height - ring),
            cornerRadius = CornerRadius(r - ring / 2), style = Stroke(ring), alpha = p,
        )
    }
}

/** One measurer for the app's chips, so a badge's text is laid out once, not once per row. */
val LocalTextMeasurer = staticCompositionLocalOf<TextMeasurer> { error("IptvTheme provides it") }

private val CHIP_PAD_H = 6.dp
private val CHIP_PAD_V = 1.dp

/**
 * Small capsules in a row, drawn in one canvas from cached text layouts: rows show them by the
 * dozen, and a Text per capsule cost more to measure than the rest of the row.
 */
@Composable
private fun Chips(labels: List<String>, style: TextStyle, fill: Color?, outline: Color?, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    val measurer = LocalTextMeasurer.current
    val density = LocalDensity.current
    val layouts: List<TextLayoutResult> = remember(labels, style, measurer) { labels.map { measurer.measure(it, style, maxLines = 1) } }
    val bare = fill == null && outline == null
    val padH = if (bare) 0f else with(density) { CHIP_PAD_H.toPx() }
    val padV = if (bare) 0f else with(density) { CHIP_PAD_V.toPx() }
    val gap = with(density) { Space.s.toPx() }
    val width = layouts.sumOf { it.size.width.toDouble() }.toFloat() + layouts.size * padH * 2 + (layouts.size - 1) * gap
    val height = layouts.maxOf { it.size.height } + padV * 2
    Canvas(modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() })) {
        val r = CornerRadius(Radius.chip.toPx())
        val line = 1.dp.toPx()
        var x = 0f
        for (layout in layouts) {
            val w = layout.size.width + padH * 2
            if (fill != null) drawRoundRect(fill, topLeft = Offset(x, 0f), size = Size(w, height), cornerRadius = r)
            if (outline != null) drawRoundRect(
                outline, topLeft = Offset(x + line / 2, line / 2), size = Size(w - line, height - line),
                cornerRadius = r, style = Stroke(line),
            )
            drawText(layout, topLeft = Offset(x + padH, padV))
            x += w + gap
        }
    }
}

private val badgeStyle = Type.caption

/** Quality badges (4K, FHD, 50fps, Atmos) as one quiet line, "4K · 50fps · Atmos": no capsules to compete with the name. */
@Composable
fun Badges(labels: List<String>, modifier: Modifier = Modifier) =
    Chips(remember(labels) { if (labels.isEmpty()) labels else listOf(labels.joinToString(" · ")) }, badgeStyle, fill = null, outline = null, modifier)

private val formatStyle = Type.caption.copy(color = Palette.textSecondary)
private val formatOutline = Palette.textTertiary.copy(alpha = 0.6f)

/** A copy's formats as outlined capsules ("4K", "Dolby Vision", "DTS-HD 5.1"), read at a glance where a "·" list reads as a sentence. */
@Composable
fun FormatChips(labels: List<String>, modifier: Modifier = Modifier) = Chips(labels, formatStyle, fill = null, outline = formatOutline, modifier)

private val liveLabel = listOf("LIVE")
private val liveStyle = Type.caption.copy(color = Palette.live)
private val liveFill = Palette.live.copy(alpha = 0.16f)

/** "LIVE" marker for programmes airing now: red text on a red tint, so a list of them doesn't shout. */
@Composable
fun LiveMark(modifier: Modifier = Modifier) = Chips(liveLabel, liveStyle, fill = liveFill, outline = null, modifier)

/**
 * Programme progress, neutral so the accent stays for focus. [fraction] is read inside the draw phase, so the shared minute ticker
 * repaints only this bar, never recomposes the row.
 */
@Composable
fun ProgressLine(fraction: () -> Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(Palette.track, cornerRadius = r)
                drawRoundRect(Palette.textSecondary, size = size.copy(width = size.width * fraction().coerceIn(0f, 1f)), cornerRadius = r)
            },
    )
}

/**
 * Scroll edge effect for lists: content fades into [color] (what the list sits on) at the top and
 * bottom edges instead of meeting a hard divider. Drawn over the content as two gradient bands, so
 * it costs two small fills rather than an offscreen copy of the whole list each frame. Over a
 * see-through [color] the bands show, so they must span the screen's full width at its edges.
 * [alpha] is read while drawing.
 */
fun Modifier.edgeFade(color: Color, top: Dp = 24.dp, bottom: Dp = 48.dp, alpha: () -> Float = { 1f }): Modifier = drawWithCache {
    val t = top.toPx()
    val b = bottom.toPx()
    val topBrush = Brush.verticalGradient(0f to color, 1f to color.copy(alpha = 0f), startY = 0f, endY = t)
    val bottomBrush = Brush.verticalGradient(0f to color.copy(alpha = 0f), 1f to color, startY = size.height - b, endY = size.height)
    onDrawWithContent {
        drawContent()
        val a = alpha().coerceIn(0f, 1f)
        drawRect(topBrush, size = Size(size.width, t), alpha = a)
        drawRect(bottomBrush, topLeft = Offset(0f, size.height - b), size = Size(size.width, b), alpha = a)
    }
}

/** A thin accent thumb at the right of an inner list that scrolls, read while drawing. */
fun Modifier.scrollbar(state: LazyListState): Modifier = drawWithContent {
    drawContent()
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total == 0 || visible >= total) return@drawWithContent
    val w = 3.dp.toPx()
    val h = (size.height * visible / total).coerceAtLeast(24.dp.toPx())
    val top = (size.height - h) * (state.firstVisibleItemIndex.toFloat() / (total - visible)).coerceIn(0f, 1f)
    drawRoundRect(Palette.accent, topLeft = Offset(size.width - w, top), size = Size(w, h), cornerRadius = CornerRadius(w / 2))
}

/** A [ProblemCard]'s widest: a sentence a line, still read as a card. */
private val CARD_WIDTH = 560.dp

/**
 * What went wrong (or wants an answer), centred over the dimmed screen: [title], [body], then [action], the
 * card's one button when it has one.
 */
@Composable
fun ProblemCard(title: String, body: String, action: @Composable ColumnScope.() -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = CARD_WIDTH).material().padding(Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(title, style = Type.title)
            Text(body, style = Type.body)
            action()
        }
    }
}

/**
 * A small note in a screen's top corner (live TV's health, a film's notices) that fades in while [value] is set
 * and out once it clears, showing the last value as it goes.
 */
@Composable
fun <T : Any> CornerNotice(value: T?, modifier: Modifier = Modifier, content: @Composable RowScope.(T) -> Unit) {
    val shown = animateFloatAsState(if (value != null) 1f else 0f, Motion.panel(), label = "corner")
    val kept = remember { arrayOfNulls<Any>(1) }
    if (value != null) kept[0] = value
    @Suppress("UNCHECKED_CAST") val last = kept[0] as T? ?: return
    if (value == null && shown.value == 0f) return
    Row(
        modifier
            .graphicsLayer { alpha = shown.value }
            .material(Radius.panel)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) { content(last) }
}
