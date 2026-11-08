package com.mohamed3on.iptv.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.R
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.State
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The one button style: a label that lifts on focus and presses in to 0.96 on OK, so the
 * press is felt on the same frame as the key. Weight never changes with state.
 */
@Composable
fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val press by pressScale(interaction)
    Text(
        label,
        style = Type.headline,
        modifier = modifier
            .graphicsLayer { scaleX = press; scaleY = press }
            .focusLift(focused)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = Space.xl, vertical = Space.m),
    )
}

/** A button's scale: 0.96 while OK holds it down, on the focus spring, so the press lands on the key's frame. */
@Composable
private fun pressScale(interaction: InteractionSource): State<Float> {
    val pressed by interaction.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 0.96f else 1f, Motion.focus(), label = "press")
}

/** True inside the focused row or card: the [FitText] lines in it scroll. */
val LocalFocused = compositionLocalOf { false }

/**
 * True under a screen that is composed but undrawn (a page or a film over it): its text stops scrolling, so it never
 * asks for frames the screen on top then has to draw.
 */
val LocalStill = compositionLocalOf { false }

/**
 * One line that never ends in "…". Past its edge it clips behind a short fade; while [active] (its row or
 * card has focus, or a fixed slot on a page at rest) it scrolls the rest into view after a second's pause,
 * and starts over from the beginning when focus leaves. Only a line that overflows draws the fade, in a
 * layer the size of the line; with reduced motion it never scrolls.
 */
@Composable
fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, active: Boolean = LocalFocused.current) {
    val reduced = LocalReducedMotion.current
    val width = remember { mutableFloatStateOf(0f) }
    Text(
        text,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { width.floatValue = it.multiParagraph.width },
        modifier = modifier
            .fadeEnd(width)
            .then(if (active && !reduced && !LocalStill.current) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1000, velocity = 30.dp) else Modifier),
    )
}

private val FADE = 24.dp

/**
 * Lines that never end in "…": a slot [lines] high (at least [minLines]); text that runs past it moves up a line at a
 * time once it has rested 1.5 s (a short glide, then a pause to read the new line), so at rest the slot always shows
 * whole lines, never one cut through; it pauses at the end and starts over from the top, and from the top when the text
 * changes, clipped under a soft fade at the bottom. With reduced motion the slot grows to hold all of it.
 */
@Composable
fun ScrollText(text: String, style: TextStyle, lines: Int, modifier: Modifier = Modifier, minLines: Int = 1, color: Color = Color.Unspecified) {
    if (LocalReducedMotion.current) return Text(text, style = style, color = color, minLines = minLines, modifier = modifier)
    val density = LocalDensity.current
    val line = with(density) { style.lineHeight.toDp() }
    val step = with(density) { line.roundToPx() }
    val slot = line * lines
    val scroll = rememberScrollState()
    val still = LocalStill.current
    LaunchedEffect(text, still) {
        scroll.scrollTo(0)
        while (!still) {
            delay(1_500)
            val end = scroll.maxValue.takeIf { it in 1 until Int.MAX_VALUE } ?: return@LaunchedEffect
            while (scroll.value < end) {
                scroll.animateScrollTo((scroll.value + step).coerceAtMost(end), tween(LINE_GLIDE_MS, easing = FastOutSlowInEasing))
                delay(LINE_READ_MS)
            }
            scroll.scrollTo(0)
        }
    }
    Box(modifier.heightIn(max = slot).fadeBottom(scroll).verticalScroll(scroll, enabled = false)) {
        Text(text, style = style, color = color, minLines = minLines)
    }
}

/** A line moves up in this long, then rests [LINE_READ_MS] to be read (about a line of prose at sofa distance). */
private const val LINE_GLIDE_MS = 450
private const val LINE_READ_MS = 2_500L

/** Fades the bottom [FADE] of a slot while more text waits below it. */
private fun Modifier.fadeBottom(scroll: ScrollState): Modifier = graphicsLayer {
    compositingStrategy = if (scroll.maxValue > 0) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}.drawWithCache {
    val fade = FADE.toPx().coerceAtMost(size.height)
    val from = size.height - fade
    val brush = Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = from, endY = size.height)
    onDrawWithContent {
        drawContent()
        if (scroll.value < scroll.maxValue) drawRect(brush, Offset(0f, from), Size(size.width, fade), blendMode = BlendMode.DstIn)
    }
}

/** Fades the last [FADE] of a line whose text ([textWidth] wide) runs past it. */
private fun Modifier.fadeEnd(textWidth: FloatState): Modifier = graphicsLayer {
    compositingStrategy = if (textWidth.floatValue > size.width) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}.drawWithCache {
    val fade = FADE.toPx().coerceAtMost(size.width)
    val from = size.width - fade
    val brush = Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = from, endX = size.width)
    onDrawWithContent {
        drawContent()
        if (textWidth.floatValue > size.width) drawRect(brush, Offset(from, 0f), Size(fade, size.height), blendMode = BlendMode.DstIn)
    }
}

/**
 * Quiet indeterminate progress: a short accent segment gliding along a hairline track.
 * With reduced motion the segment stays put and breathes instead.
 */
@Composable
fun ActivityLine(modifier: Modifier = Modifier, width: Dp = 160.dp) {
    val reduced = LocalReducedMotion.current
    val t by rememberInfiniteTransition(label = "activity").animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(if (reduced) 1600 else 1100, easing = LinearEasing), if (reduced) RepeatMode.Reverse else RepeatMode.Restart),
        label = "t",
    )
    Box(
        modifier
            .size(width, 3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Palette.track)
            .drawWithContent {
                val seg = size.width * 0.3f
                if (reduced) drawRect(Palette.accent.copy(alpha = 0.35f + 0.5f * t), size = size)
                else drawRect(
                    Palette.accent,
                    topLeft = androidx.compose.ui.geometry.Offset(-seg + (size.width + seg) * t, 0f),
                    size = size.copy(width = seg),
                )
            },
    )
}

/** The app's mark (screen with the accent play triangle), as on the launcher banner. */
@Composable
fun Mark(modifier: Modifier = Modifier, size: Dp = 88.dp) {
    Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = modifier.size(size, size * 0.86f))
}

/** A panel entry: a label, its current value at the right, and a check when it is the selected option. */
@Composable
fun PanelRow(
    label: String,
    value: String? = null,
    checked: Boolean = false,
    focus: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .pressable(onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        FitText(label, style = Type.headline, active = focused, modifier = Modifier.weight(1f))
        // A value that changes in place ("Refreshing…" → "Up to date") cross-fades.
        if (value != null) AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
            label = "value",
        ) { Text(it, style = Type.body, maxLines = 1) }
        if (checked) CheckMark(Palette.accent)
    }
}

/** Focusable and pressed with OK, lifting toward the viewer while focused. */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    return focusLift(focused).clickable(interaction, indication = null, onClick = onClick)
}

/** A selected option's mark: a drawn check, one stroke weight with the app's other marks (never a typed glyph). */
@Composable
fun CheckMark(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(CHECK_SIZE)) {
        val w = size.width
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.18f, w * 0.52f)
            lineTo(w * 0.40f, w * 0.74f)
            lineTo(w * 0.82f, w * 0.28f)
        }
        drawPath(
            path, color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

private val CHECK_SIZE = 18.dp
private val DOT_RADIUS = 4.dp

/** A remote key drawn as a small cap, so instructions point at the button itself. */
@Composable
fun KeyCap(label: String) {
    Text(
        label,
        style = Type.caption.copy(color = Palette.textPrimary),
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.chip))
            .background(Palette.raised)
            .border(1.dp, Palette.edge, RoundedCornerShape(Radius.chip))
            .padding(horizontal = Space.s, vertical = 2.dp),
    )
}

/**
 * A title page's or Up next's button: a quiet translucent pill that turns solid light when focused, so
 * the one OK will press reads from across the room. A [selected] one is outlined in the accent; a [small] one is a chip;
 * a [dot] is drawn before the label (Live TV's red).
 */
@Composable
fun PageButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, small: Boolean = false, dot: Color? = null) {
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lift = animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "lift")
    val press by pressScale(interaction)
    Text(
        label,
        style = (if (small) Type.body else Type.headline).copy(color = if (focused) Palette.ink else Palette.textPrimary),
        maxLines = 1,
        modifier = modifier
            .graphicsLayer {
                val s = (if (reduced) 1f else 1f + 0.06f * lift.value) * press
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                val p = lift.value.coerceIn(0f, 1f)
                val r = CornerRadius(Radius.row.toPx())
                drawRoundRect(lerp(Palette.material, Palette.textPrimary, p), cornerRadius = r)
                if (p < 1f) drawRoundRect(if (selected) Palette.accent else Palette.edge, cornerRadius = r, style = Stroke((if (selected) 2 else 1).dp.toPx()), alpha = 1f - p)
                if (dot != null) drawCircle(dot, DOT_RADIUS.toPx(), Offset((Space.l + DOT_RADIUS).toPx(), size.height / 2))
            }
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(start = if (dot != null) Space.l + DOT_RADIUS * 2 + Space.s else if (small) Space.l else Space.xl, end = if (small) Space.l else Space.xl)
            .padding(vertical = if (small) Space.s else Space.m),
    )
}
