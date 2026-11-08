package com.mohamed3on.iptv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.em
import com.mohamed3on.iptv.data.RouteChoice
import com.mohamed3on.iptv.data.TraktStatus
import com.mohamed3on.iptv.data.TraktSync
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mohamed3on.iptv.data.RouteKind
import com.mohamed3on.iptv.player.AudioOption
import com.mohamed3on.iptv.player.AudioState
import com.mohamed3on.iptv.player.audioName
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.material
import com.mohamed3on.iptv.ui.theme.PanelRow

private val PANEL_WIDTH = 420.dp

private enum class Page { Main, Audio, Connection, Trakt }

/**
 * The Menu sheet beside the still-playing video: audio track (R13), connection route with the
 * effective route shown (R4), guide refresh. [views] is the slot for entries that open another view
 * (multi-view, U11, fills it with [PanelRow]s).
 * Back closes a nested list here; closing the panel itself is the player's Back (KeyMap).
 */
@Composable
fun QuickPanel(
    audio: AudioState,
    onAudio: (AudioOption) -> Unit,
    choice: RouteChoice,
    effective: RouteKind,
    onRoute: (RouteChoice) -> Unit,
    /** Fetches the channels and guide now; [refreshStatus] is how the last press went, null before one. */
    onRefresh: () -> Unit,
    refreshStatus: String?,
    /** Whether the channel playing is a favourite; null hides the row (nothing playing, multi-view). */
    favourite: Boolean? = null,
    onFavourite: () -> Unit = {},
    modifier: Modifier = Modifier,
    open: Boolean = true,
    /** The playing stream's numbers (picture, sound, feed, buffer, bitrate), under Connection's routes. */
    stream: @Composable ColumnScope.() -> Unit = {},
    views: @Composable ColumnScope.() -> Unit = {},
) {
    val stay by rememberUpdatedState(open)
    var page by remember { mutableStateOf(Page.Main) }
    // The row a nested list was opened from gets focus back when it closes.
    var openedFrom by remember { mutableStateOf<Page?>(null) }
    val audioRow = remember { FocusRequester() }
    val connectionRow = remember { FocusRequester() }
    val favouriteRow = remember { FocusRequester() }
    val traktRow = remember { FocusRequester() }
    val trakt = LocalContext.current.graph.trakt
    val traktStatus by trakt.status.collectAsState()
    LaunchedEffect(Unit) { trakt.refresh() }

    Column(
        modifier
            .fillMaxHeight()
            .width(PANEL_WIDTH)
            .material()
            .onKeyEvent {
                if (page == Page.Main || remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) {
                    openedFrom = page
                    page = Page.Main
                }
                true
            }
            // Focus stays in the panel while it is open: Left/Right never land on the video underneath.
            // Once it closes, focus may leave at once (to the list that Multi-view opens, say).
            .focusProperties { onExit = { if (stay) cancelFocusChange() } }
            .focusGroup()
            .padding(Space.xl),
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { fadeIn(Motion.panel()) togetherWith fadeOut(Motion.panel()) },
            label = "page",
        ) { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.fillMaxHeight()) {
                Text(
                    when (shown) {
                        Page.Main -> "Options"
                        Page.Audio -> "Audio"
                        Page.Connection -> "Connection"
                        Page.Trakt -> "Trakt"
                    },
                    style = Type.title,
                    modifier = Modifier.padding(start = Space.l, bottom = Space.l),
                )
                when (shown) {
                    Page.Main -> {
                        val hasAudio = audio.options.size > 1
                        LaunchedEffect(Unit) {
                            when (openedFrom) {
                                Page.Audio -> audioRow
                                Page.Connection -> connectionRow
                                Page.Trakt -> traktRow
                                else -> if (favourite != null) favouriteRow else if (hasAudio) audioRow else connectionRow
                            }.requestFocus()
                        }
                        // First, so ☰ then OK favourites the channel playing.
                        if (favourite != null) PanelRow(if (favourite) "Remove from Favourites" else "Add to Favourites", focus = favouriteRow, onClick = onFavourite)
                        if (hasAudio) {
                            PanelRow("Audio", audio.selected?.let(::languageName), focus = audioRow) { page = Page.Audio }
                        }
                        PanelRow("Connection", routeValue(choice, effective), focus = connectionRow) { page = Page.Connection }
                        // Multi-view (U11).
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xs), content = views)
                        PanelRow("Refresh guide", refreshStatus, onClick = onRefresh)
                        PanelRow("Trakt", traktValue(traktStatus), focus = traktRow) { page = Page.Trakt }
                    }
                    Page.Audio -> {
                        val selected = remember { FocusRequester() }
                        LaunchedEffect(Unit) { selected.requestFocus() }
                        audio.options.forEachIndexed { i, option ->
                            val isSelected = option == audio.selected || (audio.selected == null && i == 0)
                            PanelRow(
                                audioLabel(option),
                                checked = option == audio.selected,
                                focus = if (isSelected) selected else null,
                            ) { onAudio(option) }
                        }
                    }
                    Page.Connection -> {
                        val selected = remember { FocusRequester() }
                        LaunchedEffect(Unit) { selected.requestFocus() }
                        for (option in RouteChoice.entries) {
                            PanelRow(
                                routeName(option),
                                value = if (option == RouteChoice.Auto) "Now ${routeName(effective)}" else null,
                                checked = option == choice,
                                focus = if (option == choice) selected else null,
                            ) { onRoute(option) }
                        }
                        Text(
                            "Auto follows the VPN on this device.",
                            style = Type.caption,
                            modifier = Modifier.padding(start = Space.l, top = Space.m),
                        )
                        Column(Modifier.padding(start = Space.l, top = Space.xl), content = stream)
                    }
                    Page.Trakt -> TraktPage(trakt, traktStatus)
                }
            }
        }
    }
}

private fun traktValue(s: TraktStatus?) = when (s?.state) {
    null -> null
    "on" -> s.user.ifEmpty { "Connected" }
    "reconnect" -> "Reconnect"
    "waiting" -> "Waiting for code"
    else -> "Not connected"
}

/**
 * Trakt's sign-in (KTD19), focus on each state's one action: Connect; the code to enter and Cancel while it
 * waits (the box is asked every few seconds until it is entered); Try again once it expired or was declined;
 * the account and Disconnect; Reconnect when Trakt signed the app out.
 */
@Composable
private fun TraktPage(trakt: TraktSync, status: TraktStatus?) {
    val scope = rememberCoroutineScope()
    val action = remember { FocusRequester() }
    val state = status?.state
    val s = status ?: TraktStatus()
    LaunchedEffect(state) {
        runCatching { action.requestFocus() }
        while (state == "waiting") {
            delay(3_000)
            trakt.refresh()
        }
    }
    val (message, label, verb) = when (state) {
        null -> Triple("The box didn't answer.", "Try again", null)
        "waiting" -> Triple("On your phone, go to ${s.url.substringAfter("://")} and enter", "Cancel", "cancel")
        "expired" -> Triple("The code expired before it was entered.", "Try again", "connect")
        "denied" -> Triple("Trakt sign-in was declined.", "Try again", "connect")
        "on" -> Triple("Signed in as ${s.user}. Films and episodes you finish are added to your Trakt history.", "Disconnect", "disconnect")
        "reconnect" -> Triple("Trakt signed this app out. Finished plays wait here until you connect again.", "Reconnect", "connect")
        else -> Triple("Add the films and episodes you finish to your Trakt history.", "Connect", "connect")
    }
    Text(message, style = Type.body, modifier = Modifier.padding(horizontal = Space.l))
    if (state == "waiting") Text(
        s.code,
        style = Type.display.copy(letterSpacing = 0.08.em).tabular,
        modifier = Modifier.padding(Space.l),
    )
    Spacer(Modifier.size(Space.s))
    PanelRow(label, focus = action) { scope.launch { if (verb == null) trakt.refresh() else trakt.act(verb) } }
}

private fun routeName(kind: RouteKind) = if (kind == RouteKind.Vpn) "VPN" else "Plain"

private fun routeName(choice: RouteChoice) = when (choice) {
    RouteChoice.Auto -> "Auto"
    RouteChoice.Plain -> "Plain"
    RouteChoice.Vpn -> "VPN"
}

/** "Auto · VPN"; a one-press wrong-route switch can make the route differ from the choice for a while. */
private fun routeValue(choice: RouteChoice, effective: RouteKind) =
    if (choice == RouteChoice.Auto || routeName(choice) != routeName(effective)) "${routeName(choice)} · ${routeName(effective)}"
    else routeName(choice)

internal fun languageName(option: AudioOption): String =
    option.language?.takeIf { it.isNotBlank() && it != "und" }?.let(::audioName) ?: "Unknown language"

/** "English · AC3 · 5.1". */
internal fun audioLabel(option: AudioOption): String =
    listOfNotNull(languageName(option), option.codec, channelLayout(option.channels)).joinToString(" · ")

/** "5.1", "Stereo"; null when unknown. */
internal fun channelLayout(channels: Int): String? = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> if (channels > 0) "$channels ch" else null
}

