package com.mohamed3on.iptv.ui

import android.view.SurfaceView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.mohamed3on.iptv.data.FoundSubtitle
import com.mohamed3on.iptv.player.SubtitleFetch
import com.mohamed3on.iptv.player.SubtitleOption
import com.mohamed3on.iptv.player.SubtitleState
import com.mohamed3on.iptv.player.at
import com.mohamed3on.iptv.player.qualityLabels
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.tv.material3.Text
import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.player.AudioState
import com.mohamed3on.iptv.ui.theme.ActionButton
import com.mohamed3on.iptv.ui.theme.ActivityLine
import com.mohamed3on.iptv.ui.theme.Badges
import com.mohamed3on.iptv.ui.theme.FormatChips
import com.mohamed3on.iptv.ui.theme.tabular
import com.mohamed3on.iptv.ui.theme.Edge
import com.mohamed3on.iptv.ui.theme.LocalReducedMotion
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.ScrollText
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.bottomScrim
import com.mohamed3on.iptv.ui.theme.material
import com.mohamed3on.iptv.ui.theme.reveal
import com.mohamed3on.iptv.ui.theme.slideFade
import com.mohamed3on.iptv.ui.theme.KeyCap
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.player.EGYPTIAN
import com.mohamed3on.iptv.player.FilmPhase
import com.mohamed3on.iptv.player.FilmState
import com.mohamed3on.iptv.player.IdleWatch
import com.mohamed3on.iptv.player.Notice
import com.mohamed3on.iptv.player.OfferKind
import com.mohamed3on.iptv.player.SkipPart
import com.mohamed3on.iptv.ui.theme.Radius
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mohamed3on.iptv.ui.theme.PROSE_WIDTH
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mohamed3on.iptv.data.Episode
import com.mohamed3on.iptv.data.TitlesClient
import com.mohamed3on.iptv.data.posterUrl
import com.mohamed3on.iptv.data.tmdbAt
import com.mohamed3on.iptv.player.PlayOf
import com.mohamed3on.iptv.player.openFilmPlay
import com.mohamed3on.iptv.ui.theme.PanelRow
import com.mohamed3on.iptv.ui.theme.scrollbar
import com.mohamed3on.iptv.ui.theme.SlidePanel
import com.mohamed3on.iptv.ui.theme.CornerNotice
import com.mohamed3on.iptv.ui.theme.ProblemCard
import androidx.compose.foundation.layout.ColumnScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.mohamed3on.iptv.system.Check
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.KeyEvent as AndroidKeyEvent

private const val CONTROLS_MS = 4_000L
/** A seek waits this long for the next step, so a run of presses costs one reconnect, not one each. */
private const val SEEK_COMMIT_MS = 450L
private const val BUFFERING_SHOWN_MS = 400L
private const val NOTE_MS = 3_000L
private val BAR_HEIGHT = 5.dp
private val THUMB = 14.dp
private val PANEL_WIDTH = 420.dp

/** The player's own view of ExoPlayer, kept in Compose state on the main thread. */
private class MovieState {
    var playing by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var buffering by mutableStateOf(true)
}

/** The app's idle stop (R24), while the player is up; a film joins it as live TV does. */
internal val LocalIdleWatch = compositionLocalOf<IdleWatch?> { null }

/**
 * A movie full screen (v2): [of] from [from] (ms), played by a [FilmPlay] (the best copy its session finds, else
 * [fallback], the library's IPTV copy, after 4 s; the place kept and the end-of-play rules). This screen draws
 * it and forwards keys ([filmKey]). Any key shows the controls (title, the copy, progress, elapsed and remaining),
 * which hide 4s after the last key while it plays. [movie] is what the controls name; [onEnded] follows the end,
 * after the place is saved as finished.
 */
@Composable
internal fun MoviePlayback(
    movie: Movie, of: PlayOf, from: Long, fallback: Copy?, iptvUrl: (Copy) -> String,
    onEnded: () -> Unit, onClose: () -> Unit, onNext: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val graph = context.graph
    val title = of.id
    val window = LocalActivity.current?.window
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    val ended by rememberUpdatedState(onEnded)
    val opened = remember { openFilmPlay(context, window, graph, of, from, fallback, iptvUrl, scope, { ended() }, { close() }) }
    val exo = opened.exo
    val play = opened.play
    val session = play.session
    val audio = opened.surface.audio
    val subtitles = opened.subtitles
    val fetched = opened.fetched
    val ui = remember { MovieState() }
    // Every copy failed: the film isn't available.
    val error by remember { session.state.map { it.phase == FilmPhase.Unavailable }.distinctUntilChanged() }.collectAsState(false)
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    // The picture keeps its own shape (a 2.39:1 film letterboxed), never stretched to the screen; 0 until known.
    val aspect by opened.surface.aspect.collectAsState()
    val focus = remember { FocusRequester() }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui.playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                ui.buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        }
        exo.addListener(listener)
        opened.start()
        onDispose {
            exo.removeListener(listener)
            play.close()
        }
    }
    // Paused is the session's answer (#21), whatever the player is doing.
    LaunchedEffect(Unit) { session.state.map { it.paused }.distinctUntilChanged().collect { ui.paused = it } }
    // firetv/check.sh (system/Check): each notice, and the subtitles that switch on (the copy's own or fetched).
    LaunchedEffect(Unit) {
        launch { session.state.map { it.notice }.distinctUntilChanged().collect { n -> n?.let { Check.log("notice", "kind" to it.toString().substringBefore('(')) } } }
        launch { subtitles.state.map { it.selected?.language }.distinctUntilChanged().collect { it?.let { Check.log("subtitle", "lang" to it, "src" to "own") } } }
        fetched.state.map { it.selected?.lang }.distinctUntilChanged().collect { it?.let { Check.log("subtitle", "lang" to it, "src" to "fetched") } }
    }
    // Home: the place is kept and the account's one connection let go; back in the app, it's the movies.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { play.leave() }
    // The idle stop (4 hours without a key) stops a film the same way, so a forgotten TV lets the stream go.
    val idle = LocalIdleWatch.current
    DisposableEffect(idle) {
        val leave = idle?.alsoStop { play.leave() }
        onDispose { leave?.invoke() }
    }

    var panel by remember { mutableStateOf(false) }
    LaunchedEffect(panel) { session.panel(panel) }
    var kick by remember { mutableStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf<Long?>(null) }
    val position = remember { mutableLongStateOf(from) }
    val duration = remember { mutableLongStateOf(0L) }
    val buffered = remember { mutableLongStateOf(0L) }
    var quality by remember { mutableStateOf(emptyList<String>()) }
    var note by remember { mutableStateOf(if (from > 0) "Resuming from ${playTime(from)}" else null) }
    LaunchedEffect(kick, ui.playing, target, panel) {
        controls = true
        if (ui.playing && target == null && !panel) {
            delay(CONTROLS_MS)
            controls = false
        }
    }
    LaunchedEffect(ui.playing) {
        if (!ui.playing) return@LaunchedEffect
        delay(NOTE_MS)
        note = null
    }
    val heading = remember(movie, of) { headingOf(movie, of) }
    val filmNotice by remember { session.state.map { it.notice }.distinctUntilChanged() }.collectAsState(null)
    // The controls' and the corner's heights (px), measured as they lay out; what sits above them moves by these.
    val controlsHeight = remember { mutableIntStateOf(0) }
    val cornerHeight = remember { mutableIntStateOf(0) }
    val controlsLift = animateFloatAsState(if (controls && !error) controlsHeight.intValue.toFloat() else 0f, Motion.panel(reduced), label = "lift")

    // Post-play: an episode with one after it shows that one's card over its end credits (else its last 20 s);
    // OK or the 10 s countdown plays it, Back keeps the credits (the file's end then moves on as before).
    val nextUp = remember(of) { nextUpOf(graph.titles, of) }
    val credits by remember { opened.plan.state.map { it.answer?.copies?.credits }.distinctUntilChanged() }.collectAsState(null)
    var card by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val drain = remember { Animatable(0f) }
    LaunchedEffect(onNext != null && nextUp != null, credits, dismissed) {
        card = false
        if (onNext == null || nextUp == null || dismissed) return@LaunchedEffect
        while (true) {
            val dur = exo.duration
            val at = exo.currentPosition
            card = dur > 0 && at >= creditsStart(credits, dur) && at < dur
            delay(500)
        }
    }
    // The pause screen: 8 s paused with no key, the controls give way to the title, its episode and plot over a dark
    // left and foot; any key brings the controls back (and does nothing else).
    var asleep by remember { mutableStateOf(false) }
    LaunchedEffect(kick, ui.paused, panel, error, card) {
        asleep = false
        if (!ui.paused || panel || error || card) return@LaunchedEffect
        delay(PAUSE_SCREEN_MS)
        asleep = true
        controls = false
    }
    val plot = remember(of) {
        val titles = graph.titles
        (titles.peekInfo(of.title)?.plot?.ifEmpty { null } ?: if (of.episode != null) titles.peekShowOf(of.title)?.plot?.ifEmpty { null } else null)
    }

    // firetv/check.sh: `play:<id>~<seconds>` starts that long before the end, to reach the card quickly.
    LaunchedEffect(ui.playing) {
        val back = Check.fromEnd ?: return@LaunchedEffect
        val dur = exo.duration
        if (!ui.playing || dur <= 0) return@LaunchedEffect
        Check.fromEnd = null
        session.seek((dur - back).coerceAtLeast(0))
    }
    fun playNext() {
        if (dismissed) return
        dismissed = true
        onNext?.let { next -> play.next(next, done = true) }
    }
    LaunchedEffect(card, ui.playing) {
        if (!card) return@LaunchedEffect drain.snapTo(0f)
        if (!ui.playing) return@LaunchedEffect
        // Reduced motion: the fill steps once a second instead of gliding.
        if (reduced) while (drain.value < 1f) {
            delay(1_000)
            drain.snapTo((drain.value + 1_000f / COUNTDOWN_MS).coerceAtMost(1f))
        }
        else drain.animateTo(1f, tween(((1f - drain.value) * COUNTDOWN_MS).toInt(), easing = LinearEasing))
        playNext()
    }
    // While the controls show, the bar follows the player; elapsed and remaining recompose once a second.
    LaunchedEffect(controls) {
        while (controls) {
            position.longValue = exo.currentPosition
            duration.longValue = exo.duration.coerceAtLeast(0)
            buffered.longValue = exo.bufferedPosition
            // The copy playing, by its source, then what the decoders really show (R29).
            quality = qualityLabels(exo.videoFormat, exo.audioFormat)
            delay(250)
        }
    }

    val commit = remember { arrayOfNulls<Job>(1) }
    val lastStep = remember { longArrayOf(0) }
    // Where a run of steps began, for the bubble's "+30 s".
    val seekFrom = remember { longArrayOf(0) }
    fun seekNow() {
        commit[0]?.cancel()
        val to = target ?: return
        session.seek(to)
        position.longValue = to
        target = null
    }
    fun seekBy(delta: Long) {
        val dur = exo.duration
        if (target == null) seekFrom[0] = exo.currentPosition
        val base = target ?: exo.currentPosition
        target = (base + delta).coerceIn(0, if (dur > 0) (dur - 1_000).coerceAtLeast(0) else Long.MAX_VALUE)
        commit[0]?.cancel()
        commit[0] = scope.launch {
            delay(SEEK_COMMIT_MS)
            seekNow()
        }
    }

    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(panel, error) {
        if (panel || error) return@LaunchedEffect
        repeat(30) {
            withFrameNanos {}
            if (focused) return@LaunchedEffect
            runCatching { focus.requestFocus() }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.video)
            .onKeyEvent { e ->
                val native = e.nativeKeyEvent
                val down = e.type == KeyEventType.KeyDown
                val keys = FilmKeys(
                    error = error, panel = panel, seeking = target != null, sessionBack = session.takesBack, controls = controls, playing = !session.state.value.paused,
                    skip = session.state.value.notice is Notice.Skip, card = card, asleep = asleep,
                )
                val action = filmKey(native.keyCode, down, native.repeatCount, native.eventTime - native.downTime, native.eventTime - lastStep[0], keys)
                when (action) {
                    FilmAction.Pass -> return@onKeyEvent false
                    FilmAction.Leave -> play.leave()
                    FilmAction.ClosePanel -> panel = false
                    FilmAction.OpenPanel -> panel = true
                    FilmAction.DropSeek -> {
                        commit[0]?.cancel()
                        target = null
                    }
                    FilmAction.SessionBack -> session.back()
                    FilmAction.HideControls -> controls = false
                    FilmAction.CommitSeek -> seekNow()
                    FilmAction.Play -> play.resume()
                    FilmAction.Pause -> play.pause()
                    is FilmAction.Step -> {
                        lastStep[0] = native.eventTime
                        seekBy(action.by)
                    }
                    FilmAction.Accept -> session.accept()
                    FilmAction.NextEpisode -> playNext()
                    FilmAction.DismissCard -> dismissed = true
                    FilmAction.Wake -> {}
                    FilmAction.Swallow -> {}
                }
                if (down && !keys.panel && !keys.error && action != FilmAction.HideControls) kick++
                true
            }
            .onFocusChanged { focused = it.isFocused }
            .focusRequester(focus)
            .focusable(),
    ) {
        AndroidView(
            factory = { SurfaceView(it).apply { keepScreenOn = true }.also(exo::setVideoSurfaceView) },
            modifier = Modifier.align(Alignment.Center).then(if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize()),
        )
        // A fetched file's line at the player's place, ten times a second; a new timing applies at once.
        val fetchedState by fetched.state.collectAsState()
        var line by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(fetchedState.cues, fetchedState.offset) {
            val (lines, offset) = fetchedState.cues to fetchedState.offset
            if (lines.isEmpty()) line = null
            else while (true) {
                line = lines.at(exo.currentPosition, offset)
                delay(100)
            }
        }
        // Subtitles rise above whatever sits at the foot (the controls, the corner's card or Skip), on the same spring.
        val density = LocalDensity.current
        val cornerShown = !panel && !error && (card || filmNotice is Notice.Skip)
        val subtitleLift = animateFloatAsState(
            with(density) {
                val controlsTop = if (controls && !error) controlsHeight.intValue.toFloat() else 0f
                val cornerTop = if (cornerShown) controlsTop + cornerHeight.intValue - Space.safeY.toPx() else 0f
                (maxOf(controlsTop, cornerTop) + Space.l.toPx() - SUBTITLE_BOTTOM.toPx()).coerceAtLeast(0f)
            },
            Motion.panel(reduced),
            label = "subtitles",
        )
        // The pause screen's words take the foot; a paused line would sit under them.
        if (!asleep) Subtitles(cues, line) { subtitleLift.value }

        var spinner by remember { mutableStateOf(false) }
        LaunchedEffect(ui.buffering, error) {
            spinner = false
            if (ui.buffering && !error) {
                delay(BUFFERING_SHOWN_MS)
                spinner = true
            }
        }
        val spin by animateFloatAsState(if (spinner) 1f else 0f, Motion.panel(), label = "buffering")
        if (spin > 0f) ActivityLine(Modifier.align(Alignment.Center).graphicsLayer { alpha = spin })

        AnimatedVisibility(
            visible = controls && !error,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val shown = reveal()
            Box(Modifier.fillMaxWidth().bottomScrim { shown.value }.padding(top = Space.xxxl * 2)) {
                Controls(
                    heading, ui.paused, note, quality,
                    position = { target ?: position.longValue },
                    duration = { duration.longValue },
                    buffered = { buffered.longValue },
                    seeking = target != null,
                    seekFrom = { seekFrom[0] },
                    modifier = Modifier.onSizeChanged { controlsHeight.intValue = it.height }.slideFade({ shown.value }, Edge.Bottom, reduced).padding(Space.safe),
                )
            }
        }

        AnimatedVisibility(visible = asleep && !error && !panel, enter = EnterTransition.None, exit = ExitTransition.None, modifier = Modifier.fillMaxSize()) {
            val shown = reveal()
            PauseScreen(heading, plot, { shown.value }, reduced)
        }

        // The corner: the next episode's card or a Skip button, above the controls while they show (on the panel spring).
        val skip = (filmNotice as? Notice.Skip)?.takeIf { !panel && !error }
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(0, -controlsLift.value.roundToInt()) }
                .padding(Space.safe)
                .onSizeChanged { cornerHeight.intValue = it.height },
            horizontalAlignment = Alignment.End,
        ) {
            AnimatedVisibility(visible = card && !panel && !error && nextUp != null, enter = EnterTransition.None, exit = ExitTransition.None) {
                val shown = reveal()
                nextUp?.let { NextCard(it, { drain.value }, Modifier.slideFade({ shown.value }, Edge.Right, reduced)) }
            }
            AnimatedVisibility(visible = skip != null && !card, enter = EnterTransition.None, exit = ExitTransition.None) {
                val shown = reveal()
                // Kept through the fade out, after the notice has gone.
                var part by remember { mutableStateOf(SkipPart.Intro) }
                skip?.let { part = it.part }
                SkipButton(if (part == SkipPart.Recap) "Skip recap" else "Skip intro", Modifier.slideFade({ shown.value }, Edge.Right, reduced))
            }
        }

        SlidePanel(Unit.takeIf { panel }) { _, _, m ->
            val audioState by audio.state.collectAsState()
            val subtitleState by subtitles.state.collectAsState()
            val filmState by session.state.collectAsState()
            val prepared by opened.plan.state.collectAsState()
            var dubFirst by remember { mutableStateOf(graph.copyChoices.dubFirst) }
            MoviePanel(
                audioState,
                subtitleState,
                fetchedState,
                version = filmState.copy?.let { copyLine(it, prepared.answer, debridName()) },
                onSubtitle = {
                    fetched.select(null)
                    subtitles.select(it)
                },
                onFetched = fetched::select,
                onShift = fetched::shift,
                open = panel,
                onAudio = { audio.select(it) },
                dubFirst = dubFirst,
                onDubFirst = {
                    dubFirst = !dubFirst
                    graph.copyChoices.dubFirst = dubFirst
                    opened.plan.rerank()
                    // On: the Arabic track now, when this file has one.
                    if (dubFirst) audioState.options.let { o -> o.firstOrNull { it.language == EGYPTIAN } ?: o.firstOrNull { it.language == "ar" } }?.let(audio::select)
                },
                onFromStart = {
                    panel = false
                    target = null
                    play.fromStart()
                    position.longValue = 0
                },
                // Its place is kept here; leaving then saves nothing, so Continue watching names the next episode.
                onNext = onNext?.let { next -> { play.next(next) } },
                modifier = m,
            ) { first ->
                Versions(
                    title, VersionsData(prepared, filmState.copy?.id), first,
                    onChoose = {
                        panel = false
                        session.choose(it.id)
                    },
                    onChanged = opened.plan::rerank,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // The session's notices, one at a time in the corner; never over the controls' text or a panel.
        if (!panel && !error) NoticeCorner(session.state, Modifier.align(Alignment.TopEnd).padding(Space.safe))

        if (error) Unavailable(onBack = { play.leave() })
    }
}

/** The [heading] (a film's title and year, a show's name and episode), what plays as format chips, a passing [note], then elapsed, the bar and what's left; the pause mark while paused. */
@Composable
private fun Controls(
    heading: Heading,
    paused: Boolean,
    note: String?,
    quality: List<String>,
    position: () -> Long,
    duration: () -> Long,
    buffered: () -> Long,
    seeking: Boolean,
    seekFrom: () -> Long,
    modifier: Modifier,
) {
    val elapsed by remember { derivedStateOf { playTime(position()) } }
    val left by remember { derivedStateOf { duration().takeIf { it > 0 }?.let { "−" + playTime((it - position()).coerceAtLeast(0)) } ?: "" } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            heading.badge?.let { Badges(remember(it) { listOf(it) }) }
            Text(heading.name, style = Type.title, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
            heading.detail?.let { ScrollText(it, style = Type.body, lines = 1, modifier = Modifier.weight(1f, fill = false)) }
            Box(Modifier.weight(1f))
        }
        if (quality.isNotEmpty()) FormatChips(quality)
        if (note != null) Text(note, style = Type.body)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
            // The pause mark stands where the time stopped, not alone at the title row's end.
            AnimatedContent(
                targetState = paused,
                transitionSpec = { fadeIn(Motion.focus()) togetherWith fadeOut(Motion.focus()) },
                label = "paused",
            ) { p ->
                if (p) PauseMark()
            }
            Text(elapsed, style = TIME_STYLE.copy(color = Palette.textPrimary))
            SeekBar(position, duration, buffered, seeking, seekFrom, Modifier.weight(1f))
            Text(left, style = TIME_STYLE)
        }
    }
}

/** Elapsed and left tick each second: tabular, so the bar between them never twitches. */
private val TIME_STYLE = Type.body.tabular

/**
 * Played (accent), buffered ahead (faint) and the rest, with a thumb at the place; while [seeking], a material bubble
 * above the thumb names where the run of steps lands and how far from [seekFrom] ("1:02:40 · +30 s"). All read while drawing.
 */
@Composable
private fun SeekBar(position: () -> Long, duration: () -> Long, buffered: () -> Long, seeking: Boolean, seekFrom: () -> Long, modifier: Modifier) {
    val grow by animateFloatAsState(if (seeking) 1f else 0f, Motion.focus(), label = "thumb")
    val measurer = rememberTextMeasurer()
    // The bubble's last words stay while it fades out, after the seek has gone in.
    val said = remember { arrayOfNulls<TextLayoutResult>(1) }
    Box(
        modifier.height(THUMB).drawWithCache {
            if (seeking) {
                val to = position()
                said[0] = measurer.measure("${playTime(to)} · ${jump(to - seekFrom())}", BUBBLE_STYLE)
            }
            val pad = Offset(Space.m.toPx(), Space.xs.toPx())
            val gap = Space.s.toPx()
            val edge = 1.dp.toPx()
            val r = CornerRadius(Radius.chip.toPx())
            onDrawBehind {
                val dur = duration().toFloat()
                val f = if (dur > 0) (position() / dur).coerceIn(0f, 1f) else 0f
                val b = if (dur > 0) (buffered() / dur).coerceIn(f, 1f) else 0f
                val h = BAR_HEIGHT.toPx()
                val top = (size.height - h) / 2
                val bar = CornerRadius(h / 2)
                drawRoundRect(Palette.track, topLeft = Offset(0f, top), size = Size(size.width, h), cornerRadius = bar)
                drawRoundRect(Palette.track, topLeft = Offset(0f, top), size = Size(size.width * b, h), cornerRadius = bar)
                drawRoundRect(Palette.accent, topLeft = Offset(0f, top), size = Size(size.width * f, h), cornerRadius = bar)
                val thumb = (THUMB.toPx() * (0.6f + 0.4f * grow)) / 2
                drawCircle(Palette.textPrimary, radius = thumb, center = Offset(size.width * f, size.height / 2))
                val text = said[0] ?: return@onDrawBehind
                if (grow <= 0f) return@onDrawBehind
                val box = Size(text.size.width + pad.x * 2, text.size.height + pad.y * 2)
                // Centred on the thumb, kept inside the bar's ends.
                val x = (size.width * f - box.width / 2).coerceIn(0f, (size.width - box.width).coerceAtLeast(0f))
                val at = Offset(x, -box.height - gap)
                drawRoundRect(Palette.material, topLeft = at, size = box, cornerRadius = r, alpha = grow)
                drawRoundRect(Palette.edge, topLeft = at, size = box, cornerRadius = r, style = Stroke(edge), alpha = grow)
                drawText(text, topLeft = at + pad, alpha = grow)
            }
        },
    )
}

private val BUBBLE_STYLE = Type.caption.tabular.copy(color = Palette.textPrimary)

/** A jump in words: "+30 s", "−1 min 10 s", "+1 h 5 min". */
private fun jump(ms: Long): String {
    val sign = if (ms < 0) "−" else "+"
    val s = kotlin.math.abs(ms) / 1000
    return sign + when {
        s >= 3600 -> "${s / 3600} h" + (s % 3600 / 60).let { if (it > 0) " $it min" else "" }
        s >= 60 -> "${s / 60} min" + (s % 60).let { if (it > 0) " $it s" else "" }
        else -> "$s s"
    }
}

/**
 * Skip intro / Skip recap: a page button drawn as focused (solid light, ink label), since it is what OK presses
 * while it shows; the player keeps the focus, so it never moves.
 */
@Composable
private fun SkipButton(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        style = Type.headline.copy(color = Palette.ink),
        maxLines = 1,
        modifier = modifier
            .drawBehind { drawRoundRect(Palette.textPrimary, cornerRadius = CornerRadius(Radius.row.toPx())) }
            .padding(horizontal = Space.xl, vertical = Space.m),
    )
}

/** A film paused this long without a key shows the pause screen. */
private const val PAUSE_SCREEN_MS = 8_000L

/** Ink behind the pause screen's words: dense at the left and the foot, the picture still there to the right and above. */
private val PAUSE_LEFT = Brush.horizontalGradient(0f to Palette.ink.copy(alpha = 0.92f), 0.45f to Palette.ink.copy(alpha = 0.8f), 0.8f to Color.Transparent)
private val PAUSE_FOOT = Brush.verticalGradient(0.35f to Color.Transparent, 1f to Palette.ink.copy(alpha = 0.85f))

/**
 * The pause screen: low left at the safe inset, the title in a title page's type, the episode (or the year) and up to
 * three lines of plot, over ink that darkens the paused picture; [progress] 0 → 1 fades and slides it in, read while drawing.
 */
@Composable
private fun PauseScreen(heading: Heading, plot: String?, progress: () -> Float, reduced: Boolean) {
    Box(Modifier.fillMaxSize().drawBehind {
        val a = progress().coerceIn(0f, 1f)
        drawRect(PAUSE_LEFT, alpha = a)
        drawRect(PAUSE_FOOT, alpha = a)
    }) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(Space.safe).padding(bottom = Space.xxl).width(PROSE_WIDTH).slideFade(progress, Edge.Bottom, reduced),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(heading.name, style = Type.pageTitle, maxLines = 3)
            heading.detail?.let { Text(it, style = Type.headline.copy(color = Palette.textSecondary)) }
            plot?.let { ScrollText(it, style = Type.prose, lines = 3, modifier = Modifier.padding(top = Space.s)) }
        }
    }
}

/** What the controls (and the pause screen) name: a film's title and year, or a show's name and "S2 E3 · Title". */
private class Heading(val name: String, val detail: String?, val badge: String?)

private fun headingOf(movie: Movie, of: PlayOf): Heading {
    val e = of.episode ?: return Heading(movie.title.title, movie.title.year, movie.title.badge)
    val show = of.title.title
    return Heading(show.title, listOfNotNull(e.code, e.title.takeUnless { it.isEmpty() || GENERIC_EPISODE.matches(it) }).joinToString(" · "), show.badge)
}

/** The next episode's card counts down this long before playing it. */
private const val COUNTDOWN_MS = 10_000L
/** Without known credits, the card comes this long before the end. */
private const val CREDITS_FALLBACK_MS = 20_000L
private val CARD_WIDTH = 400.dp
private val STILL = DpSize(128.dp, 72.dp)
private val STILL_SHAPE = RoundedCornerShape(Radius.row)
/** "Episode 4" says nothing a number doesn't. */
private val GENERIC_EPISODE = Regex("""(?i)episode\s*\d+""")

/**
 * Where the card comes: the end credits' start when it falls in this file's last 30% (the box's times are for the
 * episode, and a different cut can move them), else 20 s before the end.
 */
private fun creditsStart(credits: Long?, duration: Long): Long =
    credits?.takeIf { it in (duration * 7 / 10) until duration } ?: (duration - CREDITS_FALLBACK_MS)

/** The episode after the one playing, as its card shows it. */
private class NextUp(val episode: Episode, val line: String, val caption: String?, val still: String?)

/** From the show's episodes the app already holds: none for a film, the last episode, or a show not loaded. */
private fun nextUpOf(titles: TitlesClient, of: PlayOf): NextUp? {
    val now = of.episode ?: return null
    val show = titles.peekShowOf(of.title) ?: return null
    val i = show.episodes.indexOfFirst { if (now.titleId.isNotEmpty()) it.titleId == now.titleId else it.id == now.id }
    val next = show.episodes.getOrNull(i + 1)?.takeIf { i >= 0 } ?: return null
    val name = next.title.takeUnless { it.isEmpty() || GENERIC_EPISODE.matches(it) }
    val still = next.image.ifEmpty { null }?.let { tmdbAt(it, "w300") }
        ?: show.backdrop.ifEmpty { null }?.let { tmdbAt(it, "w300") }
        ?: of.title.poster.ifEmpty { null }?.let(::posterUrl)
    return NextUp(next, listOfNotNull(next.code, name).joinToString(" · "), if (next.season != now.season) "Starts season ${next.season}" else null, still)
}

/** The next episode's card: its still, "S2 E4 · Title", a new season's word, and Next episode counting down ([drain] 0 → 1). */
@Composable
private fun NextCard(next: NextUp, drain: () -> Float, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val request = remember(next.still) {
        next.still?.let { with(density) { ImageRequest.Builder(context).data(it).size(STILL.width.roundToPx(), STILL.height.roundToPx()).crossfade(Motion.IMAGE_FADE_MS).build() } }
    }
    Column(modifier.width(CARD_WIDTH).material().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(STILL).graphicsLayer { shape = STILL_SHAPE; clip = true }.drawBehind { drawRect(Palette.raised) }, contentAlignment = Alignment.Center) {
                if (request != null) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("${next.episode.episode}", style = Type.title.copy(color = Palette.textSecondary))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                ScrollText(next.line, style = Type.headline, lines = 2)
                next.caption?.let { Text(it, style = Type.caption, maxLines = 1) }
            }
        }
        CountdownButton("Next episode", drain, Modifier.fillMaxWidth())
    }
}

/**
 * A focused page button whose light fill drains right to left as the countdown runs ([drain] read while drawing),
 * over a dimmer light, so its ink label reads all the way.
 */
@Composable
private fun CountdownButton(label: String, drain: () -> Float, modifier: Modifier = Modifier) {
    Text(
        label,
        style = Type.headline.copy(color = Palette.ink, textAlign = TextAlign.Center),
        maxLines = 1,
        modifier = modifier
            .drawBehind {
                val r = CornerRadius(Radius.row.toPx())
                drawRoundRect(Palette.textSecondary, cornerRadius = r)
                val w = size.width * (1f - drain().coerceIn(0f, 1f))
                if (w > 0f) clipRect(right = w) { drawRoundRect(Palette.textPrimary, cornerRadius = r) }
            }
            .padding(horizontal = Space.xl, vertical = Space.m),
    )
}

@Composable
private fun PauseMark() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(2) { Box(Modifier.size(5.dp, 18.dp).background(Palette.textPrimary, androidx.compose.foundation.shape.RoundedCornerShape(1.dp))) }
    }
}

private enum class PanelPage { Main, Version, Audio, Subtitles }

/**
 * Menu while a movie plays: the version playing ([versions], R11), audio track (when there is a choice),
 * subtitles (when there are any), Arabic dub first (the owner's setting, every title), a show's Next episode and Play from beginning.
 */
@Composable
private fun MoviePanel(
    audio: AudioState, subtitles: SubtitleState, fetched: SubtitleFetch.State, version: String?, onSubtitle: (SubtitleOption?) -> Unit,
    onFetched: (FoundSubtitle) -> Unit, onShift: (Long) -> Unit, open: Boolean,
    onAudio: (com.mohamed3on.iptv.player.AudioOption) -> Unit, dubFirst: Boolean, onDubFirst: () -> Unit, onFromStart: () -> Unit, onNext: (() -> Unit)?, modifier: Modifier,
    versions: @Composable ColumnScope.(first: FocusRequester) -> Unit,
) {
    val stay by rememberUpdatedState(open)
    var page by remember { mutableStateOf(PanelPage.Main) }
    Column(
        modifier
            .fillMaxHeight()
            .width(PANEL_WIDTH)
            .material()
            .onKeyEvent {
                if (page == PanelPage.Main || remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) page = PanelPage.Main
                true
            }
            .focusProperties { onExit = { if (stay) cancelFocusChange() } }
            .focusGroup()
            .padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            when (page) {
                PanelPage.Main -> "Options"
                PanelPage.Version -> "Versions"
                else -> page.name
            },
            style = Type.title, modifier = Modifier.padding(start = Space.l, bottom = Space.l),
        )
        val first = remember(page) { FocusRequester() }
        LaunchedEffect(page) {
            withFrameNanos {}
            runCatching { first.requestFocus() }
        }
        when (page) {
            PanelPage.Main -> {
                // Focus starts on the first row there is.
                var firstTaken = false
                fun take() = (if (firstTaken) null else first).also { firstTaken = true }
                PanelRow("Version", version ?: "Finding…", focus = take()) { page = PanelPage.Version }
                if (audio.options.size > 1) PanelRow("Audio", audio.selected?.let(::languageName), focus = take()) { page = PanelPage.Audio }
                val searching = fetched.status == SubtitleFetch.Status.Searching
                if (subtitles.options.isNotEmpty() || fetched.subs.isNotEmpty() || searching) PanelRow(
                    "Subtitles",
                    fetched.selected?.let { "${fetchedName(it.lang)} · OpenSubtitles" } ?: subtitles.selected?.let(::subtitleName) ?: if (searching) "Searching…" else "Off",
                    focus = take(),
                ) { page = PanelPage.Subtitles }
                PanelRow("Arabic dub first", if (dubFirst) "On" else "Off", focus = take(), onClick = onDubFirst)
                if (onNext != null) PanelRow("Next episode", focus = take(), onClick = onNext)
                PanelRow("Play from beginning", focus = take(), onClick = onFromStart)
            }
            PanelPage.Version -> versions(first)
            PanelPage.Audio -> audio.options.forEachIndexed { i, option ->
                val selected = option == audio.selected || (audio.selected == null && i == 0)
                PanelRow(audioLabel(option), checked = option == audio.selected, focus = if (selected) first else null) { onAudio(option) }
            }
            PanelPage.Subtitles -> {
                // OpenSubtitles' state on top, then the timing of a fetched file, then every choice; scrolls when long.
                fetchedNote(fetched)?.let { Text(it, style = Type.caption, modifier = Modifier.padding(start = Space.l, bottom = Space.s)) }
                val list = rememberLazyListState()
                LazyColumn(Modifier.weight(1f).scrollbar(list), state = list, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    if (fetched.selected != null) item {
                        // ◀ ▶ move the lines 0.1 s; a held key repeats.
                        PanelRow(
                            "Timing  ◀ ▶", "%+.1f s".format(java.util.Locale.ROOT, fetched.offset / 1000.0),
                            modifier = Modifier.onKeyEvent {
                                val by = when (it.nativeKeyEvent.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> -SHIFT_MS
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> SHIFT_MS
                                    else -> return@onKeyEvent false
                                }
                                if (it.type == KeyEventType.KeyDown) onShift(by)
                                true
                            },
                        ) {}
                    }
                    val off = subtitles.selected == null && fetched.selected == null
                    item { PanelRow("Off", checked = off, focus = if (off) first else null) { onSubtitle(null) } }
                    items(subtitles.options.size) { i ->
                        val option = subtitles.options[i]
                        val on = fetched.selected == null && option == subtitles.selected
                        PanelRow(subtitleName(option), checked = on, focus = if (on) first else null) { onSubtitle(option) }
                    }
                    items(fetched.subs.size) { i ->
                        val sub = fetched.subs[i]
                        PanelRow("${fetchedName(sub.lang)} · ${if (sub.hash) "matches this file" else sub.release}", checked = sub == fetched.selected, focus = if (sub == fetched.selected) first else null) { onFetched(sub) }
                    }
                }
            }
        }
    }
}

/** One step of a fetched subtitle file's timing. */
private const val SHIFT_MS = 100L

@Composable
private fun Unavailable(onBack: () -> Unit) {
    val shows = LocalLibrary.current == LibraryKind.Shows
    ProblemCard(
        if (shows) "This episode isn’t available right now" else "This movie isn’t available right now",
        "No copy of it would play. Try another, or this one again later.",
    ) {
        val button = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos {}
            runCatching { button.requestFocus() }
        }
        ActionButton(if (shows) "Back to shows" else "Back to movies", onBack, Modifier.padding(top = Space.m).focusRequester(button))
    }
}

/** "1:02:13", "42:10". */
internal fun playTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "English", "English · SDH"; the file's own label when it has no language. */
private fun subtitleName(option: SubtitleOption): String {
    val language = option.language?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { it } }
    return listOfNotNull(language, option.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }).joinToString(" · ").ifEmpty { "Unknown language" }
}

/** "English", "Arabic": a fetched file's language. */
private fun fetchedName(lang: String) = java.util.Locale.forLanguageTag(lang).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { lang }

/** What OpenSubtitles gave, over the Subtitles page: the search, nothing, today's limit, or how far the lines are moved. */
private fun fetchedNote(f: SubtitleFetch.State) = when (f.status) {
    SubtitleFetch.Status.Searching -> "Searching OpenSubtitles…"
    SubtitleFetch.Status.None -> "None found on OpenSubtitles"
    SubtitleFetch.Status.NoneToday -> "No subtitles today"
    else -> f.selected?.let { if (f.offset == 0L) "From OpenSubtitles" else "From OpenSubtitles · moved %+.1f s".format(java.util.Locale.ROOT, f.offset / 1000.0) }
}

/** The film's subtitles over the picture: text cues low and centred, picture cues (PGS) where the file puts them, and a fetched file's [line]; those in the lower half rise by [lift] (px, read while placing). */
@Composable
private fun Subtitles(cues: List<Cue>, line: String?, lift: () -> Float) {
    if (cues.isEmpty() && line == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val text = cues.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) } + listOfNotNull(line)
        for (cue in cues) {
            val bitmap = cue.bitmap ?: continue
            val w = maxWidth * (if (cue.size != Cue.DIMEN_UNSET) cue.size else 0.5f)
            val h = if (cue.bitmapHeight != Cue.DIMEN_UNSET) maxHeight * cue.bitmapHeight else w * bitmap.height / bitmap.width
            val x = maxWidth * (if (cue.position != Cue.DIMEN_UNSET) cue.position else 0.5f) - when (cue.positionAnchor) {
                Cue.ANCHOR_TYPE_MIDDLE -> w / 2
                Cue.ANCHOR_TYPE_END -> w
                else -> 0.dp
            }
            val y = if (cue.line != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION) maxHeight * cue.line - when (cue.lineAnchor) {
                Cue.ANCHOR_TYPE_MIDDLE -> h / 2
                Cue.ANCHOR_TYPE_END -> h
                else -> 0.dp
            } else maxHeight - h - SUBTITLE_BOTTOM
            val low = y + h / 2 > maxHeight / 2
            androidx.compose.foundation.Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.offset(x, y).offset { IntOffset(0, if (low) -lift().roundToInt() else 0) }.size(w, h))
        }
        if (text.isNotEmpty()) Text(
            text.joinToString("\n"),
            style = Type.subtitle,
            modifier = Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, -lift().roundToInt()) }.padding(start = Space.xxxl, end = Space.xxxl, bottom = SUBTITLE_BOTTOM),
        )
    }
}

private val SUBTITLE_BOTTOM = 56.dp

/**
 * The film session's notice in the top corner, where the health dot sits for live TV: an offer with
 * ▲ to take it, or a short word on what changed; during a trial, that Back goes back. Fades in and
 * out; never pauses or covers the picture's middle.
 */
@Composable
private fun NoticeCorner(state: StateFlow<FilmState>, modifier: Modifier) {
    val film by state.collectAsState()
    val service = debridName()
    // A Skip is the corner button at the foot, not a notice up here.
    val now = film.notice?.let { noticeText(it, service) } ?: if (film.trial && film.notice !is Notice.Skip) NoticeText("Trying this version · Back returns to the last one") else null
    CornerNotice(now, modifier.widthIn(max = NOTICE_WIDTH)) { n ->
        ScrollText(n.text, style = Type.caption.copy(color = Palette.textPrimary), lines = 2, modifier = Modifier.weight(1f, fill = false))
        n.action?.let {
            KeyCap("▲")
            Text(it, style = Type.caption.copy(color = Palette.textPrimary), maxLines = 1)
        }
    }
}

private class NoticeText(val text: String, val action: String? = null)

/** "TorBox · 4K · HDR10": enough to tell copies apart in a line. */
private fun short(c: Copy, service: String) = (listOf(sourceName(c.source, service)) + copyLabels(c).take(2)).joinToString(" · ")

private fun noticeText(n: Notice, service: String): NoticeText? = when (n) {
    is Notice.Offer -> when (n.kind) {
        OfferKind.Better -> NoticeText("Better version ready: ${short(n.copy, service)}", "Switch")
        OfferKind.Untested -> NoticeText("Untested here: ${short(n.copy, service)}", "Try it")
        OfferKind.Lighter -> NoticeText("Stalling. Lighter version: ${short(n.copy, service)}", "Switch")
    }
    is Notice.SteppedDown -> NoticeText("Stalls: now on ${short(n.copy, service)} · Versions can reverse it")
    is Notice.PinCleared -> NoticeText("Your pinned version won’t play here, so it’s unpinned")
    is Notice.CantStart -> NoticeText("${short(n.copy, service)} can’t start now · still on this version")
    is Notice.TrialResult -> NoticeText(if (n.works) "${short(n.copy, service)} works here" else "${short(n.copy, service)} didn’t play here · back to the last version")
    is Notice.Subtitles -> NoticeText("${fetchedName(n.language)} subtitles from OpenSubtitles · timing in Options")
    is Notice.Skip -> null
    is Notice.PremiumEnded -> NoticeText("${n.service} premium has ended")
}

private val NOTICE_WIDTH = 420.dp
