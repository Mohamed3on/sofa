package com.mohamed3on.iptv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.ScrollText
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.material
import com.mohamed3on.iptv.ui.theme.tabular
import com.mohamed3on.iptv.ui.theme.PanelRow
import com.mohamed3on.iptv.ui.theme.pressable

/**
 * ☰ on a title page, a card or an episode row: [rows] (Mark watched, Versions…), Rate (when [onRate] is given), whose page
 * picks 1–10 with Left/Right and OK, or clears the rating, then [after]. Back peels one page; ☰ closes it.
 */
@Composable
internal fun MarksPanel(
    heading: String, rows: List<Pair<String, () -> Unit>>, rating: Int?, onRate: ((Int?) -> Unit)?, onClose: () -> Unit, modifier: Modifier = Modifier,
    after: List<Pair<String, () -> Unit>> = emptyList(), open: Boolean = true,
) {
    // Focus stays in while [open]; a closing panel, still sliding out, lets it go back to the page at once.
    val stay by rememberUpdatedState(open)
    var rate by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(rate) {
        withFrameNanos {}
        runCatching { first.requestFocus() }
    }
    Column(
        modifier
            .width(MARKS_WIDTH)
            .material()
            .onKeyEvent {
                when (remoteKey(it.nativeKeyEvent.keyCode)) {
                    RemoteKey.Back -> if (it.type == KeyEventType.KeyDown) { if (rate) rate = false else onClose() }
                    RemoteKey.Menu -> if (it.type == KeyEventType.KeyDown) onClose()
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusProperties { onExit = { if (stay) cancelFocusChange() } }
            .focusGroup()
            .padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        ScrollText(if (rate) "Rate $heading" else heading, style = Type.title, lines = 2, modifier = Modifier.padding(start = Space.l, bottom = Space.l))
        if (!rate) {
            rows.forEachIndexed { i, (label, onClick) -> PanelRow(label, focus = if (i == 0) first else null, onClick = onClick) }
            if (onRate != null) PanelRow("Rate", rating?.let { "$it/10" }, focus = if (rows.isEmpty()) first else null) { rate = true }
            after.forEachIndexed { i, (label, onClick) -> PanelRow(label, focus = if (i == 0 && rows.isEmpty() && onRate == null) first else null, onClick = onClick) }
        } else if (onRate != null) {
            RatingRow(rating ?: 7, first, onRate)
            if (rating != null) PanelRow("Clear rating") { onRate(null) }
            Text("Left and right to choose, OK to save", style = Type.caption, modifier = Modifier.padding(start = Space.l, top = Space.m))
        }
    }
}

/** 1–10 as ten segments, the chosen ones in the score colour; Left/Right move it, OK saves it. */
@Composable
private fun RatingRow(initial: Int, focus: FocusRequester, onPick: (Int) -> Unit) {
    var value by remember { mutableIntStateOf(initial) }
    Row(
        Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onPreviewKeyEvent {
                val step = when (remoteKey(it.nativeKeyEvent.keyCode)) {
                    RemoteKey.Left -> -1
                    RemoteKey.Right -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (it.type == KeyEventType.KeyDown) value = (value + step).coerceIn(1, 10)
                true
            }
            .pressable { onPick(value) }
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        val color = Palette.outOf10(value.toDouble())
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val gap = 4.dp.toPx()
            val w = (size.width - gap * 9) / 10
            for (i in 0 until 10) drawRoundRect(
                if (i < value) color else Palette.track, topLeft = Offset(i * (w + gap), 0f), size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2),
            )
        }
        Text("$value/10", style = Type.headline.tabular.copy(color = color), maxLines = 1)
    }
}

private val MARKS_WIDTH = 420.dp
