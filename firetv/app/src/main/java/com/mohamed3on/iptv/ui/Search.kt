package com.mohamed3on.iptv.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.displayName
import com.mohamed3on.iptv.ui.theme.ActivityLine
import com.mohamed3on.iptv.ui.theme.LiveMark
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Radius
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.FitText
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.focusLift
import kotlinx.coroutines.delay
import com.mohamed3on.iptv.data.SearchHit
import com.mohamed3on.iptv.data.fixtureOf
import com.mohamed3on.iptv.data.groupName
import java.time.Instant
import java.time.ZoneId
import android.view.KeyEvent as AndroidKeyEvent

/** "Today 21:00" or "Tomorrow 15:30": search looks a day ahead at most. */
internal fun startLabel(startSec: Long, nowSec: Long): String {
    val zone = ZoneId.systemDefault()
    val day = { s: Long -> Instant.ofEpochSecond(s).atZone(zone).toLocalDate() }
    return "${if (day(startSec) == day(nowSec)) "Today" else "Tomorrow"} ${clockTime(startSec)}"
}

private val KEY_HEIGHT = 44.dp
private val CARET = DpSize(2.dp, 22.dp)
/** ActivityLine's own height. */
private val ACTIVITY_HEIGHT = 3.dp
private const val SPACE = "Space"
/** Focus away from the search box longer than this, then back, is arriving at Search. */
private const val ARRIVAL_MS = 50L
private const val DELETE = "Delete"
private const val VOICE = "Voice search"
/**
 * Three rows of 13 cells: a–m, n–z, then 0–9 with Space and Delete taking a cell and a half each;
 * then Voice search across the width.
 */
private val KEYS: List<List<String>> =
    listOf("abcdefghijklm", "nopqrstuvwxyz", "0123456789").map { row -> row.map(Char::toString) }
        .let { it.dropLast(1) + listOf(it.last() + listOf(SPACE, DELETE), listOf(VOICE)) }

/**
 * The query (with a caret) over a compact on-screen keyboard, so typing never covers the video with
 * the system IME. Left from the first column goes back to the groups (the overlay handles it); Down
 * from the last row calls [onDown]. [entry] focuses the key last used. [busy] shows a quiet line
 * under the query while a search runs; its height is always kept, so the keys never move.
 *
 * Voice search hands the query to the Fire TV keyboard: apps never get the remote's mic, but that
 * keyboard does ("Speak to search"), and what it hears is typed into the query. It opens on arriving
 * at Search and from the Voice search key; its Search action, Back or moving off the query hands
 * focus back to the keys.
 */
@Composable
internal fun SearchBox(
    query: String, busy: Boolean, onQuery: (String) -> Unit, entry: FocusRequester, onFocused: () -> Unit, onDown: () -> Unit, onSpoken: (String) -> Unit,
    /**
     * Whether focus is nowhere on the screen, so the keys may take it back when the Fire TV keyboard closes
     * itself; the screen knows, the box doesn't. Null: the screen places focus itself (the channel list).
     */
    stray: (() -> Boolean)? = null,
    /** Whether focus landing here now counts as arriving at Search (which opens the Fire TV keyboard). */
    arrive: () -> Boolean = { true },
) {
    var at by remember { mutableStateOf(0 to 0) }
    val focusManager = LocalFocusManager.current
    var listening by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue()) }
    var settled by remember { mutableStateOf("") }
    val voice = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Back to the Voice search key before the field goes, so focus never drops out of the list.
    // Set while the Fire TV keyboard is up; cleared when the app closes it itself (which places focus).
    val wasListening = remember { booleanArrayOf(false) }
    fun stopListening() {
        wasListening[0] = false
        runCatching { entry.requestFocus() }
        keyboard?.hide()
        listening = false
    }
    // Arriving at Search from outside it opens the Fire TV keyboard, so the mic is ready at once; moving
    // between the query and the keys, or coming back from that keyboard, doesn't.
    var inside by remember { mutableStateOf(false) }
    val leftAt = remember { longArrayOf(0) }
    LaunchedEffect(listening) {
        if (!listening) {
            // The Fire TV keyboard can close taking focus with it (its own Back): the field goes, and focus
            // would be nowhere. Back to the keys, once the field has gone.
            if (!wasListening[0]) return@LaunchedEffect
            wasListening[0] = false
            withFrameNanos {}
            if (!inside && stray?.invoke() == true) {
                inside = true // a return, not an arrival: the keyboard stays closed
                runCatching { entry.requestFocus() }
            }
            return@LaunchedEffect
        }
        wasListening[0] = true
        field = TextFieldValue(query, TextRange(query.length))
        settled = query
        withFrameNanos {}
        runCatching { voice.requestFocus() }
        keyboard?.show()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.m)
            .onFocusChanged {
                val now = SystemClock.uptimeMillis()
                // An arrival, not a blip: inside a grid (Movies' search), moving between key rows leaves the box and
                // comes back in the same frame, which must not open the keyboard.
                if (it.hasFocus && !inside && now - leftAt[0] > ARRIVAL_MS && arrive()) listening = true
                if (!it.hasFocus && inside) leftAt[0] = now
                inside = it.hasFocus
            },
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (listening) {
                    var held by remember { mutableStateOf(false) }
                    BasicTextField(
                        field,
                        onValueChange = {
                            // Dictation lands as words at once, typing as one letter: done speaking, so on to the results.
                            // Measured from the last settled text, so it holds however the keyboard composes it.
                            val spoken = it.composition == null && it.text.length - settled.length > 1
                            if (it.composition == null) settled = it.text
                            field = it
                            onQuery(it.text)
                            if (spoken) {
                                stopListening()
                                onSpoken(it.text)
                            }
                        },
                        textStyle = Type.title,
                        singleLine = true,
                        cursorBrush = SolidColor(Palette.accent),
                        // No suggestions, so typed letters arrive one by one rather than as a finished word.
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { stopListening() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(voice)
                            .onFocusChanged { if (it.isFocused) held = true else if (held) listening = false }
                            .onKeyEvent {
                                when (it.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_BACK -> if (it.type == KeyEventType.KeyUp) stopListening()
                                    // The Fire TV keyboard eats the Back that hides it, leaving focus on this unseen
                                    // field: the remote's next arrow or OK hands it back to the keys.
                                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_DPAD_LEFT,
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_DPAD_CENTER ->
                                        if (it.type == KeyEventType.KeyDown) stopListening()
                                    else -> return@onKeyEvent false
                                }
                                true
                            },
                    )
                } else {
                    Text(query, style = Type.title, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f, fill = false))
                    Box(Modifier.padding(start = 2.dp).size(CARET).clip(RoundedCornerShape(1.dp)).background(Palette.accent))
                }
            }
            val shown by animateFloatAsState(if (busy) 1f else 0f, Motion.panel(), label = "busy")
            Box(Modifier.height(ACTIVITY_HEIGHT)) {
                if (shown > 0f) ActivityLine(Modifier.graphicsLayer { alpha = shown })
            }
        }
        Column(
            Modifier.onKeyEvent {
                val (r, c) = at
                val down = it.type == KeyEventType.KeyDown
                when (it.nativeKeyEvent.keyCode) {
                    // The overlay sends Left and Right to the groups and the schedule, so the keys move themselves.
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> (c > 0).also { inside -> if (inside && down) focusManager.moveFocus(FocusDirection.Left) }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true.also { if (down && c < KEYS[r].lastIndex) focusManager.moveFocus(FocusDirection.Right) }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> r == 0
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> (r == KEYS.lastIndex).also { last -> if (last && down) onDown() }
                    else -> false
                }
            },
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            KEYS.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    row.forEachIndexed { c, key ->
                        val word = key.length > 1
                        KeyCap(
                            key,
                            style = if (word) Type.caption else Type.headline,
                            modifier = Modifier.weight(if (word) 1.5f else 1f),
                            focus = if (at == r to c) entry else null,
                            onFocused = { at = r to c; onFocused() },
                        ) {
                            if (key == VOICE) {
                                listening = true
                                return@KeyCap
                            }
                            onQuery(
                                when (key) {
                                    DELETE -> query.dropLast(1)
                                    SPACE -> if (query.isEmpty() || query.endsWith(" ")) query else "$query "
                                    else -> query + key
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, style: TextStyle, modifier: Modifier, focus: FocusRequester?, onFocused: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .height(KEY_HEIGHT)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusLift(focused, Radius.chip)
            .background(Palette.tile, RoundedCornerShape(Radius.chip))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Idle keys recede; the focused one reads at full strength (colour only, never weight).
        Text(label, style = style, color = if (focused) Palette.textPrimary else Palette.textSecondary, maxLines = 1)
    }
}

/**
 * A programme found by search: its title, then the channel (with progress when live, else when it
 * starts), then the competition or group. OK tunes a live one; on an upcoming one it only says when.
 * [watchHint] says, on the playing row, that OK goes full screen.
 */
@Composable
internal fun HitRow(hit: SearchHit, clock: State<Long>, playing: Boolean, watchHint: Boolean, focus: FocusRequester?, onFocused: () -> Unit, onPick: () -> Unit) {
    val p = hit.programme
    val live by remember(p) { derivedStateOf { p.start <= clock.value } }
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hint) {
        if (hint) {
            delay(2_500)
            hint = false
        }
    }
    val title = remember(p.title) { fixtureOf(p.title) }
    val channel = remember(hit.tune.name) { displayName(hit.tune.name) }
    RowFrame(hit.tune, focus, onFocused, onClick = { if (live) onPick() else hint = true }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            if (playing) PlayingDot()
            if (live && p.match) LiveMark()
            FitText(title, style = Type.headline)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FitText(
                if (live) channel else "$channel · ${startLabel(p.start, clock.value)}",
                style = Type.body, modifier = Modifier.weight(1f),
            )
            if (live) Progress(p, clock)
        }
        if (hint) Hint("Starts at ${clockTime(p.start)}")
        else LastLine(watchHint) { FitText(hit.comp ?: groupName(hit.tune.group), style = Type.caption) }
    }
}
