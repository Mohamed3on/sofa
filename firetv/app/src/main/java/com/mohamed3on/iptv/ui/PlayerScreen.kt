package com.mohamed3on.iptv.ui

import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.system.Check
import com.mohamed3on.iptv.data.ChannelIdentity
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.GuideDb
import com.mohamed3on.iptv.player.LiveQuality
import com.mohamed3on.iptv.ui.theme.Badges
import com.mohamed3on.iptv.ui.theme.Edge
import com.mohamed3on.iptv.ui.theme.reveal
import com.mohamed3on.iptv.ui.theme.slideFade
import com.mohamed3on.iptv.update.updateRow
import com.mohamed3on.iptv.data.displayName
import com.mohamed3on.iptv.data.NowNext
import com.mohamed3on.iptv.data.cleanTitle
import com.mohamed3on.iptv.data.fixtureOf
import com.mohamed3on.iptv.data.groupName
import com.mohamed3on.iptv.data.liveNow
import com.mohamed3on.iptv.data.RouteKind
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.player.AudioState
import com.mohamed3on.iptv.player.AudioTracks
import com.mohamed3on.iptv.player.ExoStreamPlayer
import com.mohamed3on.iptv.player.MultiView
import com.mohamed3on.iptv.player.MultiViewState
import com.mohamed3on.iptv.player.TILE_CAP
import com.mohamed3on.iptv.player.Phase
import com.mohamed3on.iptv.player.SessionState
import com.mohamed3on.iptv.player.StreamSession
import com.mohamed3on.iptv.ui.theme.LiveMark
import com.mohamed3on.iptv.ui.theme.LocalReducedMotion
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.ProgressLine
import com.mohamed3on.iptv.ui.theme.ListScrim
import com.mohamed3on.iptv.ui.theme.Scrim
import com.mohamed3on.iptv.ui.theme.edgeFade
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.FitText
import com.mohamed3on.iptv.ui.theme.ScrollText
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.player.Health
import com.mohamed3on.iptv.player.AudioOption
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.aspectRatio
import java.util.Locale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import com.mohamed3on.iptv.player.PlayerPool
import com.mohamed3on.iptv.ui.theme.Radius
import com.mohamed3on.iptv.ui.theme.tabular
import com.mohamed3on.iptv.ui.theme.PROSE_WIDTH
import com.mohamed3on.iptv.ui.theme.bottomScrim
import com.mohamed3on.iptv.ui.theme.leftScrim
import com.mohamed3on.iptv.ui.theme.material
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val BANNER_MS = 4_000L
private const val SCREEN_ASPECT = 16f / 9f
/** Within this of 16:9 a picture fills the screen, so a rounding never leaves hairline bars. */
private const val ASPECT_SLACK = 0.03f
private val LOGO_SIZE = DpSize(128.dp, 72.dp)
private val TUNING_SIZE = 16.dp
private val PROGRESS_WIDTH = 200.dp
private val CARD_WIDTH = 560.dp
/** After launch's banner has sunk, so building the hidden list never competes with anything on screen. */
private const val LIST_BUILD_DELAY_MS = BANNER_MS + 2_000L
/** How long a previewed channel plays before the list steps back from it. */
private const val PEEK_DELAY_MS = 700L
/** How far the list fades while it steps back. */
private const val RECEDE = 0.85f
/** How long the corner says why before only its dot stays. */
private const val HEALTH_TEXT_MS = 6_000L
private const val REFRESHING = "Refreshing…"
/** Info view stats and badges leave a touch faster than they arrive. */
private const val STATS_OUT_MS = 120

/**
 * The full-screen player (R10, R14): the video, with chrome only when asked for. The channel
 * banner rises on launch and on every zap, and sinks back 4s later; OK opens the channel list (Left, on
 * its groups), Right the recent channels, and ⏩/⏪ step through Live now; a stream
 * that can't recover gets a card saying why. The remote follows [keyAction]; [session] is null
 * until the activity has started its player (the video surface is shown regardless). While
 * [tiles] has any, they replace the full-screen video (U11).
 */
@Composable
fun PlayerScreen(feed: Feed, session: StreamSession?, audio: AudioTracks?, surface: SurfaceView, aspect: Float, tiles: MultiView<ExoStreamPlayer>, live: Pair<Int, LiveQuality>?, health: Health?) {
    val grid by tiles.state.collectAsState()
    var movie by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Gone, not just stopped, in multi-view and under a movie: its last frame would fight their surfaces.
        // The picture keeps its shape: a 4:3 channel is pillarboxed, never stretched; widescreen fills the screen.
        val fill = aspect <= 0f || abs(aspect - SCREEN_ASPECT) < ASPECT_SLACK
        if (grid.tiles.isEmpty() && !movie) AndroidView(factory = { surface }, modifier = Modifier.align(Alignment.Center).then(if (fill) Modifier.fillMaxSize() else Modifier.aspectRatio(aspect)))
        if (session != null) PlayerChrome(feed, session, audio, tiles, grid, live, health, onMovie = { movie = it })
    }
}

/** What the channel list was opened to pick, when not a channel to watch. */
private enum class Pick { MultiView, AddTile, ReplaceTile }

@Composable
private fun PlayerChrome(
    feed: Feed,
    session: StreamSession,
    audio: AudioTracks?,
    tiles: MultiView<ExoStreamPlayer>,
    grid: MultiViewState<ExoStreamPlayer>,
    live: Pair<Int, LiveQuality>?,
    health: Health?,
    onMovie: (Boolean) -> Unit,
) {
    val graph = LocalContext.current.graph
    val reduced = LocalReducedMotion.current
    val multiView = grid.tiles.isNotEmpty()
    var pick by remember { mutableStateOf<Pick?>(null) }
    val state by session.state.collectAsState()
    val guide by graph.sync.guide.collectAsState()
    val clock = rememberMinuteClock()
    var layer by remember { mutableStateOf(Layer.None) }
    // What Up/Down walk: a Live now tune carries its list; a tune from a group carries none (its group).
    var zapContext by remember { mutableStateOf<List<Channel>?>(null) }
    // The last zap's direction, for the banner's motion; null for a jump (Back to the previous channel).
    var down by remember { mutableStateOf<Boolean?>(true) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // The channel list's search and place, kept between openings; each opening starts afresh (ChannelOverlay).
    val memory = remember { ListMemory() }
    var opens by remember { mutableStateOf(0) }
    var openInGroups by remember { mutableStateOf(false) }
    // Until the open layer has taken focus, the player still holds it: arrow keys then wait, rather
    // than moving focus into the layer wherever they happen to point.
    var layerFocused by remember { mutableStateOf(false) }
    // A brief line in the banner for the channel it was said on ("No matches on right now").
    var note by remember { mutableStateOf<Pair<ChannelIdentity?, String>?>(null) }
    var bannerKick by remember { mutableStateOf(0) }
    // OK's info view: the banner stays up, with the stream's numbers, across zaps until Back.
    var info by remember { mutableStateOf(false) }
    // Movies rise over the open channel list, which keeps its place underneath for Back.
    var movies by remember { mutableStateOf(false) }
    val moviesShown = animateFloatAsState(if (movies) 1f else 0f, if (reduced) tween(Motion.FADE_MS) else Motion.panel(), label = "movies")
    val moviesComposed by remember { derivedStateOf { movies || moviesShown.value > 0f } }
    // firetv/check.sh: every screen but live is in Movies & Shows, which takes the target from there.
    val check by Check.open.collectAsState()
    LaunchedEffect(check) {
        val target = check ?: return@LaunchedEffect
        if (target != "live") movies = true else if (!moviesComposed) Check.open.value = null
    }

    // Until the debounced tune starts, the feed playing is still the previous channel's (KTD9).
    val tuning = state.phase == Phase.Connecting || state.feed?.family != state.requested?.family
    val problem = problemOf(state).takeIf { state.feed?.family == state.requested?.family }

    var banner by remember { mutableStateOf(true) }
    LaunchedEffect(state.requested?.identity, tuning, bannerKick) {
        banner = true
        if (!tuning) {
            delay(BANNER_MS)
            banner = false
            note = null
        }
    }
    // Focus rests on the player itself whenever nothing else holds it, so the remote reaches keyAction.
    LaunchedEffect(layer, problem?.button) {
        if (layer == Layer.None && problem?.button == null) focus.requestFocus()
    }

    // Afresh each time, even when reopened while the list is still sliding away.
    fun openList(groups: Boolean) {
        info = false
        opens++
        openInGroups = groups
        layer = Layer.ChannelList
    }

    fun perform(action: KeyAction) {
        when (action) {
            is KeyAction.Zap -> {
                down = action.down
                session.zap(action.to, feed)
            }
            is KeyAction.Previous -> {
                down = null
                session.zap(action.to, feed)
            }
            is KeyAction.OpenOverlay -> openList(groups = action.groups)
            KeyAction.ShowInfo -> info = true
            KeyAction.HideInfo -> info = false
            KeyAction.OpenPanel -> layer = Layer.QuickPanel
            KeyAction.OpenRecent -> layer = Layer.Recent
            is KeyAction.LiveZap -> scope.launch {
                val g = guide
                val now = System.currentTimeMillis() / 1000
                // The guide can be swapped (and the old file closed) mid-read after a download.
                val matches = withContext(Dispatchers.IO) {
                    runCatching { feed.liveNow(g?.airingMatches(now).orEmpty()).context }.getOrNull()
                } ?: return@launch
                val from = session.state.value.requested
                val to = stepThrough(matches, from, action.down)
                if (matches.isEmpty()) note = from?.identity to "No matches on right now"
                if (to == null) {
                    bannerKick++
                    return@launch
                }
                down = action.down
                zapContext = matches
                session.zap(to, feed)
            }
            KeyAction.Close -> {
                layer = Layer.None
                pick = null
            }
            is KeyAction.MoveTile -> tiles.select(neighbourTile(grid.selected, grid.tiles.size, action.key))
            KeyAction.Promote -> {
                down = null
                zapContext = null
                tiles.promote(session, feed)
            }
            KeyAction.Ignore, KeyAction.Pass -> {}
        }
    }

    fun pickTile(purpose: Pick) {
        pick = purpose
        openList(groups = false)
    }

    /** OK held: a fresh search, on the keyboard (which brings up the Fire TV one, mic and all). */
    fun openSearch() {
        memory.inSearch = true
        memory.searchFocus = null
        memory.query = ""
        openList(groups = false)
    }
    // OK held while watching: told by the key's repeats, acted on at its release, so the repeats never
    // reach the keyboard Search opens on. A tap keeps its steps (the info view, then the list).
    var okHeld by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            // Bubbles up from whatever is focused: an open layer's own keys never get here as player keys.
            .onKeyEvent { event ->
                val key = remoteKey(event.nativeKeyEvent.keyCode) ?: return@onKeyEvent false
                // Movies move their own focus; Back only gets here before focus has landed in them.
                if (movies) {
                    if (key == RemoteKey.Back && event.type == KeyEventType.KeyDown) movies = false
                    return@onKeyEvent key == RemoteKey.Back
                }
                if ((layer == Layer.ChannelList || layer == Layer.Recent) && !layerFocused && key != RemoteKey.Back) return@onKeyEvent true
                val action = keyAction(key, PlayerKeys(state.requested, graph.history.last, graph.history.previous, layer, zapContext, multiView, info), feed)
                if (key == RemoteKey.Ok && layer == Layer.None && !multiView && state.requested != null) {
                    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0) okHeld = true
                    if (event.type == KeyEventType.KeyUp) {
                        if (okHeld) openSearch() else perform(action)
                        okHeld = false
                    }
                    return@onKeyEvent true
                }
                if (action == KeyAction.Pass) return@onKeyEvent false
                if (event.type == KeyEventType.KeyDown) perform(action)
                true // the key-up too, so Back never reaches the activity and leaves the app
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        if (multiView) MultiViewTiles(grid, guide, clock)

        val audioSelected = audio?.state?.collectAsState()?.value?.selected
        val route by graph.route.effective.collectAsState()
        val bannerUp = (banner || info) && layer == Layer.None && state.requested != null && !multiView
        AnimatedVisibility(
            visible = bannerUp,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // The scrim fades; the banner itself rises from the bottom and sinks back.
            val shown = reveal()
            Box(Modifier.fillMaxWidth().bottomScrim { shown.value }.padding(top = Space.xxxl * 2)) {
                AnimatedContent(
                    targetState = state.requested,
                    contentKey = { it?.identity },
                    transitionSpec = {
                        val d = down
                        Motion.zapIn(d ?: true, reduced || d == null) togetherWith
                            Motion.zapOut(d ?: true, reduced || d == null) using SizeTransform(clip = false)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .slideFade({ shown.value }, Edge.Bottom, reduced)
                        .padding(Space.safe),
                    label = "banner",
                ) { channel ->
                    // Live badges once the playing feed is measured; the server's probed ones until then.
                    val here = channel?.identity == state.requested?.identity
                    val quality = live?.takeIf { here && it.first == state.feed?.id }?.second
                    if (channel != null) Banner(
                        channel, feed, guide, clock, tuning && here, quality?.badges ?: channel.badges,
                        note = note?.takeIf { it.first == channel.identity }?.second,
                        liveNow = zapContext,
                        health = health.takeIf { here },
                        info = if (info && here) {
                            val members = feed.familyMembers(channel.family)
                            StreamInfo(quality, audioSelected, health, route, members.indexOfFirst { it.id == state.feed?.id }, members.size)
                        } else null,
                    )
                }
            }
        }

        // Out of the way while the channel list is open: it would read through the list's see-through scrim.
        AnimatedContent(
            targetState = problem.takeIf { layer != Layer.ChannelList },
            transitionSpec = { fadeIn(Motion.panel()) togetherWith fadeOut(Motion.panel()) },
            modifier = Modifier.fillMaxSize(),
            label = "problem",
        ) { shown ->
            if (shown != null) ProblemCard(shown, takeFocus = layer == Layer.None) {
                if (shown.switchesRoute) session.switchRoute() else session.retry()
            }
        }

        // Trouble shows itself while nothing else is up: the dot and why, the why folding away after a moment.
        HealthCorner(
            health?.takeIf { it.corner && !bannerUp && layer == Layer.None && !multiView && problem == null },
            Modifier.align(Alignment.TopEnd).padding(Space.safe),
        )

        // The scrim fades; the list itself slides in from the left and back out to it. The list is built
        // once, unseen, after launch has settled (the banner gone), and stays built while hidden (drawing
        // nothing), so every opening is only the slide.
        val listOpen = layer == Layer.ChannelList
        val shown = animateFloatAsState(if (listOpen) 1f else 0f, if (reduced) tween(Motion.FADE_MS) else Motion.panel(), label = "list")
        var listBuilt by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(LIST_BUILD_DELAY_MS)
            listBuilt = true
        }
        // Once a previewed row plays, the list recedes so the picture shows; any key brings it back.
        var previewed by remember { mutableStateOf<ChannelIdentity?>(null) }
        var peek by remember { mutableStateOf(false) }
        LaunchedEffect(previewed, state.phase, listOpen) {
            peek = false
            if (listOpen && previewed != null && state.phase == Phase.Playing && state.requested?.identity == previewed) {
                delay(PEEK_DELAY_MS)
                peek = true
            }
        }
        val recede = animateFloatAsState(if (peek && listOpen) 1f else 0f, if (reduced) tween(Motion.FADE_MS) else Motion.panel(), label = "recede")
        if (listBuilt || opens > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    // Gone under Movies once they cover it: an alpha-0 layer isn't drawn.
                    .graphicsLayer {
                        alpha = if (shown.value > 0f) (1f - RECEDE * recede.value) * (1f - moviesShown.value.coerceIn(0f, 1f)) else 0f
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .leftScrim { shown.value }
                    // The columns run to the screen's edges; rows fade out in the safe area, across the full width.
                    .edgeFade(ListScrim, top = Space.safeY + Space.xl, bottom = Space.safeY + Space.xxxl) { shown.value }
                    .onFocusChanged { layerFocused = it.hasFocus }
                    .onPreviewKeyEvent {
                        if (peek) previewed = null
                        false
                    },
            ) {
                val selectedTile = grid.tiles.getOrNull(grid.selected)?.channel
                ChannelOverlay(
                    updateRow = updateRow(graph.updater),
                    feed = feed,
                    guide = guide,
                    clock = clock,
                    current = selectedTile ?: state.requested,
                    context = zapContext.takeIf { !multiView },
                    open = listOpen && !movies,
                    opens = opens,
                    memory = memory,
                    startInGroups = openInGroups,
                    // Watching, not picking a tile: a row tunes behind the open list first (preview).
                    onPreview = if (pick == null && !multiView) { channel, context ->
                        previewed = channel.identity
                        zapContext = context
                        down = null
                        session.zap(channel, feed)
                    } else null,
                    onPick = { channel, context ->
                        layer = Layer.None
                        val purpose = pick
                        pick = null
                        val onScreen = if (multiView) grid.tiles.map { it.channel.family } else listOfNotNull(state.requested?.family)
                        if (purpose == null) {
                            // Picking the channel already on (in any group) just closes the list; it never retunes.
                            if (channel.family != state.requested?.family) {
                                zapContext = context
                                down = null
                                session.zap(channel, feed)
                            } else {
                                zapContext = context
                            }
                        } else if (channel.family !in onScreen) { // a channel already on screen never gets a second tile
                            when (purpose) {
                                Pick.MultiView -> tiles.enter(session, channel, feed)
                                Pick.AddTile -> tiles.add(channel, feed)
                                Pick.ReplaceTile -> tiles.replace(channel, feed)
                            }
                        }
                    },
                    entrance = { shown.value },
                    // Watching, not picking a tile: Movies & Shows is among the list's groups.
                    onLibrary = if (pick == null && !multiView) ({ movies = true }) else null,
                    pickHint = pick?.let { purpose ->
                        val onScreen = if (multiView) grid.tiles.map { it.channel.family } else listOfNotNull(state.requested?.family)
                        val hint: (Channel) -> String? = { channel ->
                            if (channel.family in onScreen) "Already on screen"
                            else when (purpose) {
                                Pick.MultiView -> "Multi-view · OK to watch beside this one"
                                Pick.AddTile -> "Add tile · OK to add this channel"
                                Pick.ReplaceTile -> "Replace tile · OK to put it in the selected tile"
                            }
                        }
                        hint
                    },
                )
            }
        }

        if (moviesComposed) MoviesLayer(
            feed = feed,
            session = session,
            tiles = tiles,
            shown = { moviesShown.value },
            open = movies,
            onPlaying = onMovie,
            onClose = { movies = false },
        )

        AnimatedVisibility(
            visible = layer == Layer.Recent,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe).onFocusChanged { layerFocused = it.hasFocus },
        ) {
            val shown = reveal()
            RecentSheet(
                modifier = Modifier.slideFade({ shown.value }, Edge.Right, reduced),
                feed = feed,
                guide = guide,
                clock = clock,
                recent = remember { graph.history.recent },
                current = state.requested,
                open = layer == Layer.Recent,
                onPick = { channel ->
                    layer = Layer.None
                    if (channel.family != state.requested?.family) {
                        down = null
                        zapContext = null
                        session.zap(channel, feed)
                    }
                },
            )
        }

        // Out here, so a refresh keeps its status when the panel closes before it finishes.
        var refreshStatus by remember { mutableStateOf<String?>(null) }
        AnimatedVisibility(
            visible = layer == Layer.QuickPanel,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe),
        ) {
            val shown = reveal()
            val audioState = audio?.state?.collectAsState()?.value ?: AudioState()
            val choice by graph.route.choice.collectAsState()
            val effective by graph.route.effective.collectAsState()
            QuickPanel(
                modifier = Modifier.slideFade({ shown.value }, Edge.Right, reduced),
                open = layer == Layer.QuickPanel,
                // The audio tracks are the full-screen player's, which multi-view stops.
                audio = if (multiView) AudioState() else audioState,
                onAudio = { audio?.select(it) },
                choice = choice,
                effective = effective,
                // Route.effective catches up a moment later, so tune on the route the choice resolves to now.
                onRoute = { picked ->
                    val kind = graph.route.choose(picked)
                    session.useRoute(kind)
                    grid.tiles.forEach { it.session.useRoute(kind) }
                },
                refreshStatus = refreshStatus,
                favourite = state.requested?.takeIf { !multiView }?.let { graph.lineup.isFavourite(it.family) },
                onFavourite = { state.requested?.let { graph.lineup.toggleFavourite(it.family) } },
                onRefresh = {
                    if (refreshStatus != REFRESHING) {
                        refreshStatus = REFRESHING
                        graph.scope.launch {
                            val channels = graph.sync.syncChannels()
                            val guide = graph.sync.syncGuide()
                            refreshStatus = if (channels != null || guide != null) "Failed" else "Up to date"
                        }
                    }
                },
                views = {
                    if (!multiView) {
                        if (state.requested != null) PanelRow("Multi-view") { pickTile(Pick.MultiView) }
                    } else {
                        var full by remember { mutableStateOf(false) }
                        PanelRow("Add tile", "${grid.tiles.size} of $TILE_CAP") {
                            if (grid.tiles.size >= TILE_CAP) full = true else pickTile(Pick.AddTile)
                        }
                        PanelRow("Replace tile") { pickTile(Pick.ReplaceTile) }
                        PanelRow("Remove tile") {
                            layer = Layer.None
                            tiles.remove(session, feed)
                        }
                        if (full) Text(
                            "$TILE_CAP tiles is the most. Remove tile to make room for another.",
                            style = Type.caption,
                            modifier = Modifier.padding(start = Space.l, top = Space.xs),
                        )
                    }
                },
            )
        }
    }
}

/**
 * Logo, name and badges, now and next, and where Up/Down move from: Live now when it was tuned from
 * there ([liveNow], the zap context), else its group. Above that, a passing [note].
 */
@Composable
private fun Banner(
    channel: Channel, feed: Feed, guide: GuideDb?, clock: State<Long>, tuning: Boolean, badges: List<String>, note: String?, liveNow: List<Channel>?,
    health: Health?, info: StreamInfo?,
) {
    val inLiveNow = liveNow?.takeIf { list -> list.any { it.family == channel.family } }
    val list = inLiveNow ?: feed.channelsIn(channel.group)
    val position = list.indexOfFirst { it.identity == channel.identity }.takeIf { it >= 0 } ?: list.indexOfFirst { it.family == channel.family }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xl), verticalAlignment = Alignment.CenterVertically) {
        LogoTile(channel, LOGO_SIZE)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                // Event slots are named for the event, so a long name gets a second line rather than an ellipsis.
                ScrollText(remember(channel.name) { displayName(channel.name) }, style = Type.title, lines = 2, modifier = Modifier.weight(1f, fill = false))
                AnimatedVisibility(info == null, enter = fadeIn(tween(Motion.FADE_MS)), exit = fadeOut(tween(STATS_OUT_MS))) { Badges(badges) }
                if (tuning) Tuning()
            }
            Guide(channel, guide, clock, describe = info != null)
            // The stats unfold under the guide and fold away, rather than the banner jumping a line.
            val reduced = LocalReducedMotion.current
            val kept = remember { arrayOfNulls<StreamInfo>(1) }
            if (info != null) kept[0] = info
            AnimatedVisibility(
                visible = info != null,
                enter = fadeIn(tween(Motion.FADE_MS)) + if (reduced) EnterTransition.None else expandVertically(Motion.panel()),
                exit = fadeOut(tween(STATS_OUT_MS)) + if (reduced) ExitTransition.None else shrinkVertically(Motion.panel()),
            ) {
                kept[0]?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        StreamStats(it, badges)
                        KeyHints()
                    }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (note != null) Text(note, style = Type.headline, maxLines = 1)
            if (health != null) HealthLine(health)
            Text(if (inLiveNow != null) "Live now" else groupName(channel.group), style = Type.caption, maxLines = 1)
            if (position >= 0) Text("${position + 1} of ${list.size}", style = Type.caption.tabular)
        }
    }
}

/**
 * Now and next, with now's description when [describe] (the info view). Synthetic rows (name-as-title
 * filler) show nothing: event slot names outrun the guide.
 */
@Composable
private fun Guide(channel: Channel, guide: GuideDb?, clock: State<Long>, describe: Boolean) {
    // Re-read each minute; recomposes only when the programmes themselves change.
    val nowNext by remember(channel.tvgId, guide) {
        derivedStateOf {
            // The guide can be swapped (and the old file closed) mid-read after a download.
            guide?.takeIf { channel.tvgId.isNotEmpty() }?.let { runCatching { it.nowNext(channel.tvgId, clock.value) }.getOrNull() }
        }
    }
    val (now, next) = nowNext ?: NowNext(null, null)
    if (now == null && next == null) {
        Text("No guide", style = Type.body)
        return
    }
    if (now?.synthetic == true) return
    if (now != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (now.match) LiveMark()
            Text(remember(now) { if (now.match) fixtureOf(now.title) else cleanTitle(now.title) }, style = Type.headline, maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Text("${clockTime(now.start)} – ${clockTime(now.stop)}", style = Type.caption.tabular)
            ProgressLine(
                fraction = { (clock.value - now.start).toFloat() / (now.stop - now.start).coerceAtLeast(1) },
                modifier = Modifier.widthIn(max = PROGRESS_WIDTH),
            )
        }
        if (describe && now.descr.isNotBlank()) ScrollText(now.descr, style = Type.prose, lines = 2, modifier = Modifier.widthIn(max = PROSE_WIDTH))
    }
    if (next != null && !next.synthetic) {
        Text("Next · ${clockTime(next.start)} ${if (next.match) fixtureOf(next.title) else cleanTitle(next.title)}", style = Type.body, maxLines = 2)
    }
}

/** A small, calm sign that the channel is still tuning; the old picture stays until the new one arrives. */
@Composable
private fun Tuning() {
    val reduced = LocalReducedMotion.current
    val angle = rememberInfiniteTransition(label = "tuning")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(1_000, easing = LinearEasing)), label = "angle")
    Canvas(Modifier.size(TUNING_SIZE)) {
        val stroke = 2.dp.toPx()
        rotate(if (reduced) 0f else angle.value) {
            drawArc(
                Palette.textTertiary, startAngle = -90f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** What an error card says (R14, KTD8), with at most one [button]: retry, or the route switch. */
private data class Problem(val title: String, val body: String, val button: String? = null, val switchesRoute: Boolean = false)

private fun problemOf(state: SessionState): Problem? = when (val phase = state.phase) {
    Phase.Busy -> Problem("Your account is in use", "Another device is playing. Trying again every 5 seconds.")
    Phase.GaveUp -> Problem("Your account is in use", "Still in use after 10 minutes.", "Try again")
    is Phase.WrongRoute -> {
        val route = if (phase.switchTo == RouteKind.Vpn) "VPN route" else "plain route"
        Problem("Wrong connection for your VPN", "This stream needs the $route.", "Switch to $route", switchesRoute = true)
    }
    Phase.Offline -> Problem("Channel unavailable", "None of this channel's feeds are responding.", "Try again")
    Phase.Stopped, Phase.Connecting, Phase.Playing -> null
}

@Composable
private fun ProblemCard(problem: Problem, takeFocus: Boolean, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Scrim), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = CARD_WIDTH).material().padding(Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(problem.title, style = Type.title)
            Text(problem.body, style = Type.body)
            val label = problem.button ?: return@Column
            val button = remember { FocusRequester() }
            LaunchedEffect(takeFocus) { if (takeFocus) button.requestFocus() }
            Text(
                label,
                style = Type.headline,
                modifier = Modifier
                    .padding(top = Space.m)
                    .focusRequester(button)
                    .pressable(onAction)
                    .padding(horizontal = Space.xl, vertical = Space.m),
            )
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

internal fun clockTime(epochSec: Long) = timeFormat.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))

/** Epoch seconds, ticking on each minute: the one clock behind every progress line. */
@Composable
private fun rememberMinuteClock(): State<Long> = produceState(System.currentTimeMillis() / 1000) {
    while (true) {
        delay(60_000 - System.currentTimeMillis() % 60_000)
        value = System.currentTimeMillis() / 1000
    }
}

/** What the info view shows about the playing stream; [quality] is null until the player has measured it. */
private class StreamInfo(
    val quality: LiveQuality?, val audio: AudioOption?, val health: Health?, val route: RouteKind, val feedAt: Int, val feeds: Int,
)

/**
 * The info view's row, colour before words: the picture as measured (its height too, which the
 * badges leave out below 1080p), sound, route, which of the family's feeds plays as dots, then the
 * buffer as a gauge in the health dot's colour and the bitrate. The line's speed shows only when it
 * falls behind, in the health line's reason.
 */
@Composable
private fun StreamStats(info: StreamInfo, badges: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
        val q = info.quality
        if (q != null) {
            Pill(pictureLabel(q), if (q.height >= 1080) Palette.good else if (q.height >= 720) Palette.textPrimary else Palette.warning)
            if (q.atmos) Pill("Atmos", Palette.textPrimary)
        } else Badges(badges)
        info.audio?.let { a -> channelLayout(a.channels)?.let { Pill(it, Palette.textSecondary) } }
        if (info.route == RouteKind.Vpn) Pill("VPN", Palette.accent) else Pill("Plain", Palette.textSecondary)
        if (info.feeds > 1 && info.feedAt >= 0) Row(horizontalArrangement = Arrangement.spacedBy(FEED_DOT)) {
            repeat(info.feeds) { i ->
                Box(Modifier.size(FEED_DOT).clip(CircleShape).background(if (i == info.feedAt) Palette.textPrimary else Palette.track))
            }
        }
        // Last, so their numbers changing width each second moves nothing after them.
        info.health?.let { h ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                val color = levelColor(h.level)
                val fill = (h.buffered * 1000 / PlayerPool.MAX_BUFFER_MS).coerceIn(0f, 1f)
                Canvas(Modifier.size(GAUGE_WIDTH, GAUGE_HEIGHT)) {
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(Palette.track, cornerRadius = r)
                    drawRoundRect(color, size = size.copy(width = size.width * fill), cornerRadius = r)
                }
                Text("${h.buffered.roundToInt()} s", style = Type.caption.tabular, maxLines = 1)
            }
            h.stream?.let { Text("${mbps(it)} Mbps", style = Type.caption.tabular, maxLines = 1) }
        }
    }
}

/** The remote's keys, under the info view's stats: the ones nothing else on screen points at. */
@Composable
private fun KeyHints() {
    Column(Modifier.padding(top = Space.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in KEY_HINTS) FitText(line, style = Type.caption)
    }
}

private val KEY_HINTS = listOf(
    "OK again: channel list · Left: groups · Right: recent · Hold OK: search · Menu: options",
    "Rewind, Fast-forward: the previous or next live match",
    "In the list: Right: schedule · Menu: favourite or move · Rewind, Fast-forward: top, bottom",
)

/** "720p50", "1080p25", "4K50": the measured picture in one word. */
private fun pictureLabel(q: LiveQuality): String {
    val height = when {
        q.height >= 2160 -> "4K"
        q.height >= 1080 -> "1080p"
        q.height >= 720 -> "720p"
        else -> "${q.height}p"
    }
    return height + (q.fps?.roundToInt()?.toString() ?: "")
}

/** A word on a tint of its own [color]: the info view's stats, read by colour first. */
@Composable
private fun Pill(text: String, color: Color) = Text(
    text,
    style = Type.caption.tabular.copy(color = color),
    maxLines = 1,
    modifier = Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(Radius.chip)).padding(horizontal = Space.s, vertical = 1.dp),
)

private val GAUGE_WIDTH = 48.dp
private val GAUGE_HEIGHT = 5.dp
private val FEED_DOT = 6.dp

private fun mbps(value: Float) = String.format(Locale.US, "%.1f", value)

/** The health dot, with why when it is not good. */
@Composable
private fun HealthLine(health: Health) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        health.reason?.let { Text(it, style = Type.caption, maxLines = 1) }
        HealthDot(health.level)
    }
}

@Composable
private fun HealthDot(level: Health.Level) = Box(Modifier.size(HEALTH_DOT).clip(CircleShape).background(levelColor(level)))

private fun levelColor(level: Health.Level) = when (level) {
    Health.Level.Good -> Palette.good
    Health.Level.Warn -> Palette.warning
    Health.Level.Bad -> Palette.live
}

/** Unasked trouble in the corner: the dot and why, then just the dot until it clears; it fades out once fine. */
@Composable
private fun HealthCorner(health: Health?, modifier: Modifier) {
    val shown = animateFloatAsState(if (health != null) 1f else 0f, Motion.panel(), label = "health")
    val kept = remember { arrayOfNulls<Health>(1) }
    if (health != null) kept[0] = health
    val h = kept[0] ?: return
    if (health == null && shown.value == 0f) return
    var expanded by remember { mutableStateOf(true) }
    // A new kind of trouble says why again; its numbers changing does not.
    LaunchedEffect(h.level, h.reason?.substringBefore(" ·")?.filterNot(Char::isDigit)) {
        expanded = true
        delay(HEALTH_TEXT_MS)
        expanded = false
    }
    Row(
        modifier
            .graphicsLayer { alpha = shown.value }
            .material(Radius.panel)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (expanded && h.reason != null) Text(h.reason, style = Type.caption.copy(color = Palette.textPrimary), maxLines = 1)
        HealthDot(h.level)
    }
}

private val HEALTH_DOT = 10.dp
