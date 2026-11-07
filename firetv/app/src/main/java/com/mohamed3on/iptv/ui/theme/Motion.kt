package com.mohamed3on.iptv.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.launch
import kotlin.math.PI
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.focus.onFocusChanged

/**
 * Motion is springs, described the way Apple does: a damping ratio (1 = no overshoot)
 * and a response in seconds (how quickly it gets there; not a duration). Springs start
 * from wherever the value is on screen, so a key pressed mid-animation redirects it
 * smoothly instead of waiting. Nothing here bounces: on a remote no gesture carries
 * momentum, so overshoot would only distract from the picture.
 *
 * Every transition leaves the way it came ([slideFade]): the channel list slides in from the left
 * and out to the left, the banner rises from the bottom and sinks back. Panels move without
 * offscreen layers, so the stick's GPU draws each frame of a transition in one pass.
 */
object Motion {
    /** Stiffness for a unit-mass spring that settles in about [response] seconds. */
    private fun stiffness(response: Float) = (2 * PI / response).let { (it * it).toFloat() }

    fun <T> spring(response: Float = 0.35f): FiniteAnimationSpec<T> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness(response))

    /** Focus lift and press feedback: quick, so it lands on the same beat as the key. */
    fun <T> focus(): FiniteAnimationSpec<T> = spring(0.22f)
    /** Panels and overlays. */
    fun <T> panel(): FiniteAnimationSpec<T> = spring(0.35f)

    /** Focused rows and tiles grow this much toward the viewer. */
    const val FOCUS_SCALE = 1.03f
    /** Reduced-motion cross-fade. */
    private const val FADE_MS = 160
    /** A poster or still fades in as it loads (Coil's crossfade); Coil skips it for images already in memory. */
    const val IMAGE_FADE_MS = 150

    /** A short fade: reduced motion's stand-in for movement, and a value changing in place. */
    fun <T> fade(): FiniteAnimationSpec<T> = tween(FADE_MS)
    /** A fade out a touch faster than [fade] in: the info view's stats, a groups-column row leaving. */
    fun <T> quickFade(): FiniteAnimationSpec<T> = tween(120)
    /** A backdrop arriving behind its page: slow enough that it never pops. */
    fun <T> slowFade(): FiniteAnimationSpec<T> = tween(400)
    /** [panel], or with reduced motion a [fade]. */
    fun <T> panel(reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) fade() else panel()
    /** One turn a second, steadily: the tuning spinner. */
    fun spin(): InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(1_000, easing = LinearEasing))

    /**
     * Banner content on a zap moves in the direction of travel: zapping down brings the
     * next channel up from below, so the motion hints where you are in the list.
     */
    fun zapIn(down: Boolean, reduced: Boolean): EnterTransition =
        if (reduced) fadeIn(fade())
        else slideInVertically(spring<IntOffset>(0.3f)) { if (down) it / 3 else -it / 3 } + fadeIn(spring(0.3f))

    fun zapOut(down: Boolean, reduced: Boolean): ExitTransition =
        if (reduced) fadeOut(fade())
        else slideOutVertically(spring<IntOffset>(0.3f)) { if (down) -it / 3 else it / 3 } + fadeOut(spring(0.2f))

    /** Android's "Remove animations" (Accessibility) sets the animator scale to 0. */
    fun reducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/** Where a panel comes in from, and leaves back to. */
enum class Edge { Left, Right, Bottom }

/**
 * An AnimatedVisibility's own enter/exit as a number, 0 hidden → 1 shown, on the panel spring (a
 * short fade's timing with reduced motion). Pair it with `enter = EnterTransition.None, exit =
 * ExitTransition.None`: the content stays until this settles.
 */
@Composable
fun AnimatedVisibilityScope.reveal(): State<Float> {
    val reduced = LocalReducedMotion.current
    return transition.animateFloat(
        transitionSpec = { Motion.panel(reduced) },
        label = "reveal",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
}

/**
 * Slides in from [from] by a third of its size and fades in as [progress] goes 0 → 1, and back out
 * the same way. The alpha is applied to each draw rather than through an offscreen copy, which the
 * stick's GPU can't afford for a full-height column every frame. Reduced motion only fades.
 */
fun Modifier.slideFade(progress: () -> Float, from: Edge, reduced: Boolean): Modifier = graphicsLayer {
    val p = progress().coerceIn(0f, 1f)
    alpha = p
    compositingStrategy = CompositingStrategy.ModulateAlpha
    if (!reduced) when (from) {
        Edge.Left -> translationX = -(1f - p) * size.width / 3f
        Edge.Right -> translationX = (1f - p) * size.width / 3f
        Edge.Bottom -> translationY = (1f - p) * size.height / 2f
    }
}

/**
 * Moves to a new place in its parent on the panel spring instead of jumping there: a line pushed up when
 * text arrives below it glides up. Only placement changes each frame, never layout. Reduced motion jumps.
 */
@Composable
fun Modifier.glide(): Modifier {
    if (LocalReducedMotion.current) return this
    val scope = rememberCoroutineScope()
    val shift = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val last = remember { arrayOfNulls<Offset>(1) }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            val now = coordinates?.positionInParent()
            val before = last[0]
            if (now != null) last[0] = now
            if (now != null && before != null && now != before) {
                // Drawn where it was this frame too, then on to its new place.
                val from = shift.value + (before - now)
                placeable.place(from.round())
                scope.launch {
                    shift.snapTo(from)
                    shift.animateTo(Offset.Zero, Motion.panel())
                }
            } else placeable.place(shift.value.round())
        }
    }
}

/** [progress] delayed by [lag] of its course: a second column that follows the first in, and leaves before it. */
fun staggered(progress: Float, lag: Float = 0.15f): Float = ((progress - lag) / (1f - lag)).coerceIn(0f, 1f)

/**
 * A panel at the right edge (Options, Recent, Versions) that slides in from the right and back out to it. A
 * non-null [value] shows it; while it leaves, [content] keeps the last value. [content] gets whether the panel is
 * still open (passed on, so focus can leave a closing panel) and the modifier for the panel.
 */
@Composable
fun <T : Any> BoxScope.SlidePanel(value: T?, modifier: Modifier = Modifier, content: @Composable (value: T, open: Boolean, modifier: Modifier) -> Unit) {
    val reduced = LocalReducedMotion.current
    val focusManager = LocalFocusManager.current
    var last by remember { mutableStateOf(value) }
    val holdsFocus = remember { booleanArrayOf(false) }
    SideEffect {
        if (value != null) last = value
        // A closing panel lets focus go at once, so the page's own "focus is back with me" check moves it where it belongs.
        else if (holdsFocus[0]) focusManager.clearFocus(force = true)
    }
    AnimatedVisibility(
        visible = value != null,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
        modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe).onFocusChanged { holdsFocus[0] = it.hasFocus }.then(modifier),
    ) {
        val shown = reveal()
        (value ?: last)?.let { content(it, value != null, Modifier.slideFade({ shown.value }, Edge.Right, reduced)) }
    }
}
