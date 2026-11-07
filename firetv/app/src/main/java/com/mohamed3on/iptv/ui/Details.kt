package com.mohamed3on.iptv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.data.Programme
import com.mohamed3on.iptv.data.cleanTitle
import com.mohamed3on.iptv.data.displayName
import com.mohamed3on.iptv.ui.theme.Badges
import com.mohamed3on.iptv.ui.theme.LiveMark
import com.mohamed3on.iptv.ui.theme.FitText
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.ScrollText
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.tabular
import com.mohamed3on.iptv.ui.theme.edgeFade
import com.mohamed3on.iptv.ui.theme.focusLift
import com.mohamed3on.iptv.ui.theme.material
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import android.view.KeyEvent as AndroidKeyEvent

internal val DETAILS_WIDTH = 300.dp
private val TIME_WIDTH = 56.dp

private sealed interface Line {
    val key: String
}
private data class Day(val label: String) : Line {
    override val key get() = "day|$label"
}
private data class Slot(val programme: Programme) : Line {
    override val key get() = "p|${programme.start}"
}

/**
 * A channel's schedule from now to the end of the guide (R6), beside the channel column. Synthetic
 * filler is left out (it only repeats the channel's name); with nothing left, it says "No guide".
 * Left closes it, as Back does (the overlay handles Back). [stay] keeps focus inside while open.
 * The focused programme opens to its description; OK on any of them watches the channel ([onWatch]).
 */
@Composable
internal fun Details(channel: Channel, guide: GuideDb?, clock: State<Long>, stay: () -> Boolean, onClose: () -> Unit, onWatch: () -> Unit, modifier: Modifier = Modifier) {
    val schedule by produceState<List<Programme>?>(null, channel.tvgId, guide) {
        snapshotFlow { clock.value }.collectLatest { now ->
            value = withContext(Dispatchers.IO) {
                guide?.takeIf { channel.tvgId.isNotEmpty() }
                    // The guide can be swapped (and the old file closed) mid-read after a download.
                    ?.let { runCatching { it.schedule(channel.tvgId, now) }.getOrNull() }
                    ?.filterNot { it.synthetic }
                    .orEmpty()
            }
        }
    }
    Column(
        modifier
            .width(DETAILS_WIDTH)
            .fillMaxHeight()
            .material()
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode != AndroidKeyEvent.KEYCODE_DPAD_LEFT) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) onClose()
                true
            }
            .focusProperties { onExit = { if (stay()) cancelFocusChange() } }
            .focusGroup()
            .padding(vertical = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Column(Modifier.padding(horizontal = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            ScrollText(remember(channel.name) { displayName(channel.name) }, style = Type.title, lines = 2)
            if (channel.badges.isNotEmpty()) {
                Badges(channel.badges)
            }
        }
        val programmes = schedule ?: return@Column
        if (programmes.isEmpty()) {
            Text("No guide", style = Type.body, modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.m))
            // Something in the pane must hold focus, or Left and Back would have nowhere to come from.
            FocusHere(Modifier.padding(horizontal = Space.xl))
            return@Column
        }
        val lines = remember(programmes) { withDays(programmes) }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            runCatching { first.requestFocus() }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).edgeFade(Palette.material),
            contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
        ) {
            items(lines, key = { it.key }, contentType = { it::class }) { line ->
                when (line) {
                    is Day -> Text(line.label, style = Type.caption, modifier = Modifier.padding(start = Space.m, top = Space.l, bottom = Space.xs))
                    is Slot -> ScheduleRow(line.programme, clock, if (line.programme == programmes.first()) first else null, onWatch)
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(programme: Programme, clock: State<Long>, focus: FocusRequester?, onWatch: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val airing = programme.start <= clock.value
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .focusLift(focused)
            .clickable(interaction, indication = null, onClick = onWatch)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(clockTime(programme.start), style = Type.caption.tabular, modifier = Modifier.width(TIME_WIDTH))
            if (airing && programme.match) LiveMark()
            FitText(
                remember(programme) { cleanTitle(programme.title) },
                style = if (airing) Type.body.copy(color = Palette.textPrimary) else Type.body,
                active = focused,
            )
        }
        // Lined up under the title, past the time.
        if (focused && programme.descr.isNotBlank()) {
            ScrollText(programme.descr, style = Type.caption, lines = 5, modifier = Modifier.padding(start = TIME_WIDTH + Space.s))
        }
    }
}

/** A quiet focus anchor for a pane with nothing else to focus. */
@Composable
private fun FocusHere(modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        runCatching { focus.requestFocus() }
    }
    Box(modifier.focusRequester(focus).focusable())
}

/** The schedule with a heading where a new day starts ("Tomorrow", then weekday names). */
private fun withDays(programmes: List<Programme>): List<Line> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    var day = today
    return buildList {
        for (p in programmes) {
            val date = Instant.ofEpochSecond(p.start).atZone(zone).toLocalDate()
            if (date.isAfter(day)) {
                day = date
                add(Day(if (date == today.plusDays(1)) "Tomorrow" else date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)))
            }
            add(Slot(p))
        }
    }
}
