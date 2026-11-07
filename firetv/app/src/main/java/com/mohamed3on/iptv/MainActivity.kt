package com.mohamed3on.iptv

import android.content.Intent
import android.os.Bundle
import com.mohamed3on.iptv.system.Check
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import com.mohamed3on.iptv.ui.theme.ActionButton
import com.mohamed3on.iptv.ui.theme.ActivityLine
import com.mohamed3on.iptv.ui.theme.Mark
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.FeedState
import com.mohamed3on.iptv.data.StartAction
import com.mohamed3on.iptv.data.SyncError
import com.mohamed3on.iptv.data.startAction
import com.mohamed3on.iptv.player.Cushion
import com.mohamed3on.iptv.player.ExoStreamPlayer
import com.mohamed3on.iptv.player.Health
import com.mohamed3on.iptv.player.HealthModel
import com.mohamed3on.iptv.player.HealthSample
import com.mohamed3on.iptv.player.StreamPhase
import com.mohamed3on.iptv.data.ChannelIdentity
import com.mohamed3on.iptv.data.RouteKind
import androidx.media3.common.Player
import kotlinx.coroutines.flow.map
import com.mohamed3on.iptv.player.AudioTracks
import com.mohamed3on.iptv.player.Playback
import com.mohamed3on.iptv.player.PlaybackSurface
import com.mohamed3on.iptv.player.LiveQuality
import com.mohamed3on.iptv.player.liveQuality
import kotlinx.coroutines.flow.combine
import com.mohamed3on.iptv.player.Idle
import com.mohamed3on.iptv.player.IdleWatch
import com.mohamed3on.iptv.player.Phase
import com.mohamed3on.iptv.player.PlayerPool
import com.mohamed3on.iptv.player.StreamSession
import com.mohamed3on.iptv.ui.LocalIdleWatch
import com.mohamed3on.iptv.player.MultiView
import com.mohamed3on.iptv.ui.PlayerScreen
import com.mohamed3on.iptv.ui.theme.IptvTheme
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.ProblemCard
import com.mohamed3on.iptv.ui.theme.KeyCap
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class MainActivity : ComponentActivity() {
    private val surface by lazy { SurfaceView(this).apply { keepScreenOn = true } }
    private val session = MutableStateFlow<StreamSession?>(null)
    /** The main player's playback surface: its audio tracks and picture shape. */
    private val playback = MutableStateFlow<PlaybackSurface?>(null)
    private val idle = MutableStateFlow<IdleWatch?>(null)
    /** The playing feed's measured quality (feed id to reading), for the banner's live badges. */
    private val live = MutableStateFlow<Pair<Int, LiveQuality>?>(null)
    /** How the main player's stream is doing (health dot, info view); null when there is nothing to say. */
    private val health = MutableStateFlow<Health?>(null)
    private val telemetry get() = graph.telemetry
    /** Past the first start: onCreate already asked for the last screen. */
    private var restarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A launch reopens Movies & Shows (or the title page) it was closed on; a check target wins.
        Check.request(intent.getStringExtra("open") ?: graph.prefs.get(Check.LAST_SCREEN))
        val sync = graph.sync
        // The pool lives from onStart to onStop (KTD7); multi-view takes its tiles' players from it.
        val pool = PlayerPool(this, graph.http)
        val tiles = MultiView({ pool.acquire(tile = true) }, pool::release, graph.route, lifecycleScope, SystemClock::elapsedRealtime)
        setContent {
            val state by sync.state.collectAsState()
            val playing by session.collectAsState()
            val shown by playback.collectAsState()
            val shape = shown?.aspect?.collectAsState()?.value ?: 0f
            val idleWatch by idle.collectAsState()
            val idleState = idleWatch?.state?.collectAsState()?.value
            val liveQuality by live.collectAsState()
            val streamHealth by health.collectAsState()
            IptvTheme(reducedMotion = Motion.reducedMotion(this)) {
                CompositionLocalProvider(LocalIdleWatch provides idleWatch) {
                    Root(state, playing, shown?.audio, surface, shape, tiles, liveQuality, streamHealth, idleState, onRetry = { graph.scope.launch { sync.retry() } })
                }
            }
        }
        // While visible: on resume refresh anything older than 1h, then channels every 30 min, guide every 3h.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // A newer build downloads in the background and waits as "Update ready" in the list (R15, R16).
                graph.scope.launch { graph.updater.check() }
                sync.refresh(30.minutes, 1.hours)
                while (true) {
                    delay(1.minutes)
                    sync.refresh(30.minutes, 3.hours)
                }
            }
        }
        // The last channel starts tuning here, before the first frame is drawn, from the cached feed (R9, R25).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val player = pool.acquire()
                player.exo.setVideoSurfaceView(surface)
                val s = StreamSession(player, graph.route, this, SystemClock::elapsedRealtime)
                // A zap, a reconnect or a problem keeps the display's mode; only a stopped session gives it back.
                val signal = s.state.map {
                    when (it.phase) {
                        Phase.Playing -> Playback.Playing
                        Phase.Stopped -> Playback.Stopped
                        else -> Playback.Waiting
                    }
                }
                val shown = PlaybackSurface(player.exo, window, signal, this)
                // What the stream really is, once its frame rate is measured: the banner shows it, and
                // the server keeps it in the quality cache. The session log records tunes, stalls and problems.
                val reporting = telemetry.observe(s, graph.route)
                launch {
                    combine(shown.fps, s.state) { fps, st -> fps to st }.collect { (fps, st) ->
                        val playing = st.feed?.takeIf { st.phase == Phase.Playing }
                        val q = playing?.let { liveQuality(player.exo, fps) }
                        live.value = if (playing != null && q != null && fps != null) playing.id to q else null
                        if (playing != null && q != null) telemetry.reportQuality(playing, q) { graph.scope.launch { sync.syncChannels() } }
                    }
                }
                launch { watchHealth(player, s) }
                idle.value = IdleWatch(this) {
                    tiles.close()
                    s.stop()
                }
                launch {
                    s.state.collect {
                        Log.i(TAG, "${it.phase} ${it.feed?.name ?: "-"}")
                        if (it.phase == Phase.Playing) it.requested?.let(graph.history::watched)
                    }
                }
                launch {
                    val feed = graph.lineup.applyTo(sync.state.filterIsInstance<FeedState.Ready>().first().feed)
                    startChannel(feed)?.let { s.zap(it, feed) }
                }
                // Back from another app (a trailer in SmartTube, Home and back): the screen comes back as a launch would
                // reopen it, since the player's chrome (Movies & Shows with it) is rebuilt with the new session.
                if (restarted) Check.request(graph.prefs.get(Check.LAST_SCREEN))
                restarted = true
                session.value = s
                playback.value = shown
                try {
                    awaitCancellation()
                } finally {
                    tiles.close()
                    session.value = null
                    playback.value = null
                    shown.release()
                    idle.value = null
                    reporting.cancel()
                    live.value = null
                    health.value = null
                    s.stop()
                    pool.releaseAll()
                }
            }
        }
    }

    // firetv/check.sh opens screens with `am start --activity-single-top --es open <target>`.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Check.request(intent.getStringExtra("open"))
    }

    /**
     * Reads the main player each second into [health]; each rebuffer grows its cushion and goes to
     * the session log. A feed's bitrate is reported once it has played for a minute, so it is settled.
     */
    private suspend fun watchHealth(player: ExoStreamPlayer, s: StreamSession) {
        var played: ChannelIdentity? = null
        var playingSince: Pair<Int, Long>? = null // feed id, when it started playing
        val model = HealthModel(onRebuffer = { network, stream ->
            val cushion = player.cushion.onRebuffer(SystemClock.elapsedRealtime())
            s.state.value.feed?.let { telemetry.rebuffer(it, network, stream, graph.route.effective.value, cushion) }
        })
        while (true) {
            val st = s.state.value
            val feed = st.feed
            val requested = st.requested
            if (st.phase == Phase.Playing) played = requested?.identity
            val phase = when (st.phase) {
                Phase.Playing -> StreamPhase.Playing
                Phase.Connecting -> if (requested != null && requested.identity == played) StreamPhase.Reconnecting else StreamPhase.Tuning
                else -> null // the problem card says what is wrong
            }
            val now = SystemClock.elapsedRealtime()
            playingSince = if (st.phase != Phase.Playing || feed == null) null else playingSince?.takeIf { it.first == feed.id } ?: (feed.id to now)
            val h = if (phase == null || feed == null) null else model.next(
                HealthSample(
                    at = now,
                    phase = phase,
                    feedId = feed.id,
                    buffering = player.exo.playbackState == Player.STATE_BUFFERING,
                    position = player.exo.currentPosition,
                    buffered = player.exo.totalBufferedDuration,
                    bytes = player.bytesLoaded,
                    spare = feed.id != requested?.id,
                    plainRoute = graph.route.effective.value == RouteKind.Plain,
                    cushionMs = player.cushion.resumeMs(now).takeIf { it > Cushion.BASE_MS },
                ),
            )
            health.value = h
            val stream = h?.stream
            if (stream != null && feed != null && playingSince?.let { now - it.second >= 60_000 } == true) telemetry.reportBitrate(feed, stream)
            delay(1_000)
        }
    }

    /** The last channel; with none, the first of the list's start group (until the overlay, U9). */
    private fun startChannel(feed: Feed) = when (val action = feed.startAction(graph.history.last)) {
        is StartAction.Play -> action.channel
        is StartAction.OpenList -> feed.channelsIn(action.group).firstOrNull()
    }

    // Every key resets the idle stop (R24). While it asks, any key answers and goes no further;
    // once it has stopped playback, only OK (resume) and Back (leave the app) do anything.
    // Everything else goes to the player screen's key map.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val watch = idle.value ?: return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (watch.state.value) {
            Idle.Watching -> watch.keyPressed()
            Idle.Asking -> {
                if (down) watch.keyPressed()
                return true
            }
            Idle.TimedOut -> {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                    return true
                }
                if (down && (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                    watch.resume()
                    session.value?.retry()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val TAG = "IptvPlayer"
    }
}

@Composable
private fun Root(
    state: FeedState,
    session: StreamSession?,
    audio: AudioTracks?,
    surface: SurfaceView,
    aspect: Float,
    tiles: MultiView<ExoStreamPlayer>,
    live: Pair<Int, LiveQuality>?,
    health: Health?,
    idle: Idle?,
    onRetry: () -> Unit,
) {
    when (state) {
        FeedState.Loading -> Loading()
        is FeedState.Failed -> SyncFailed(state.error, onRetry)
        is FeedState.Ready -> Box(Modifier.fillMaxSize()) {
            val lineup = LocalContext.current.graph.lineup
            val arranged by lineup.version.collectAsState()
            val feed = remember(state.feed, arranged) { lineup.applyTo(state.feed) }
            PlayerScreen(feed, session, audio, surface, aspect, tiles, live, health)
            // Fades in over the picture and out again, as the problem card does.
            AnimatedContent(
                targetState = idle?.takeIf { it != Idle.Watching },
                transitionSpec = { fadeIn(Motion.panel()) togetherWith fadeOut(Motion.panel()) },
                modifier = Modifier.fillMaxSize(),
                label = "idle",
            ) { shown ->
                when (shown) {
                    Idle.Asking -> StillWatching()
                    Idle.TimedOut -> IdleStopped()
                    else -> {}
                }
            }
        }
    }
}

/**
 * First run only: with a cached feed, Ready arrives within a frame or two. The screen waits
 * 400ms before fading in, so a quick load never flashes it.
 */
@Composable
private fun Loading() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(400); shown = true }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, Motion.panel(), label = "loading")
    Centered(Modifier.graphicsLayer { this.alpha = alpha }) {
        Mark()
        Text("Loading channels…", style = Type.body, modifier = Modifier.padding(top = Space.xl))
        ActivityLine(Modifier.padding(top = Space.l))
    }
}

/** First run with no cached feed: says what went wrong in plain words, with one way forward. */
@Composable
private fun SyncFailed(error: SyncError, onRetry: () -> Unit) {
    val (title, body) = when (error) {
        SyncError.Offline -> "Can’t reach your server" to "Check that the Fire TV is online, then try again."
        is SyncError.Rejected -> "The server didn’t accept this app" to "Its app token may have changed. Install the latest build."
        SyncError.Malformed -> "The channel list didn’t load" to "The server sent something unexpected. Try again in a minute."
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Centered {
        Mark(size = 64.dp)
        Text(title, style = Type.title, modifier = Modifier.padding(top = Space.xl))
        Text(body, style = Type.body, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.s).widthIn(max = 520.dp))
        if (error is SyncError.Rejected) Text("HTTP ${error.status}", style = Type.caption, modifier = Modifier.padding(top = Space.xs))
        ActionButton("Try again", onRetry, Modifier.padding(top = Space.xl).focusRequester(focus))
    }
}

/** The R24 prompt. Its button is only the visible answer: MainActivity takes any key as one. */
@Composable
private fun StillWatching() {
    ProblemCard("Still watching?", "Playback stops in a minute unless you press a button.") {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        ActionButton("Keep watching", onClick = {}, Modifier.padding(top = Space.m).focusRequester(focus))
    }
}

@Composable
private fun IdleStopped() {
    Centered {
        Text("Stopped after 4 hours with no activity", style = Type.title)
        Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Press", style = Type.body)
            KeyCap("OK")
            Text("to resume", style = Type.body)
        }
    }
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().background(Palette.ink).padding(Space.safe),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
