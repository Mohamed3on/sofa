package com.mohamed3on.iptv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.SideEffect
import com.mohamed3on.iptv.ui.theme.reveal
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import coil3.PlatformContext
import coil3.request.allowHardware
import coil3.toBitmap
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import coil3.SingletonImageLoader
import coil3.request.SuccessResult
import com.mohamed3on.iptv.data.tmdbAt
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.FlowRow
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mohamed3on.iptv.ui.theme.LocalStill
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.mohamed3on.iptv.data.Episode
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.episodeUrl
import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.data.MovieInfo
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import com.mohamed3on.iptv.data.Catalog
import com.mohamed3on.iptv.data.CatalogRow
import com.mohamed3on.iptv.data.History
import com.mohamed3on.iptv.data.NextEpisode
import com.mohamed3on.iptv.data.resume
import com.mohamed3on.iptv.data.LibTitle
import com.mohamed3on.iptv.data.Person
import com.mohamed3on.iptv.data.Scored
import com.mohamed3on.iptv.data.isShow
import com.mohamed3on.iptv.data.isTmdb
import com.mohamed3on.iptv.data.known
import com.mohamed3on.iptv.data.tmdbImage
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.mohamed3on.iptv.data.Resume
import com.mohamed3on.iptv.data.TitleId
import com.mohamed3on.iptv.data.SyncError
import com.mohamed3on.iptv.data.groupLabel
import com.mohamed3on.iptv.data.movieUrl
import com.mohamed3on.iptv.data.posterUrl
import com.mohamed3on.iptv.graph
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.system.Check
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.player.PlayOf
import com.mohamed3on.iptv.player.ExoStreamPlayer
import com.mohamed3on.iptv.player.FilmSession
import com.mohamed3on.iptv.player.Prepared
import com.mohamed3on.iptv.player.EGYPTIAN
import com.mohamed3on.iptv.player.audioChoices
import com.mohamed3on.iptv.player.audioName
import com.mohamed3on.iptv.player.Support
import com.mohamed3on.iptv.player.Phase
import com.mohamed3on.iptv.player.StreamSession
import com.mohamed3on.iptv.ui.theme.ActionButton
import com.mohamed3on.iptv.ui.theme.ActivityLine
import com.mohamed3on.iptv.ui.theme.glide
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.CompositingStrategy
import com.mohamed3on.iptv.ui.theme.Badges
import com.mohamed3on.iptv.ui.theme.FormatChips
import com.mohamed3on.iptv.ui.theme.PROSE_WIDTH
import com.mohamed3on.iptv.ui.theme.Edge
import com.mohamed3on.iptv.ui.theme.LocalReducedMotion
import com.mohamed3on.iptv.ui.theme.Motion
import com.mohamed3on.iptv.ui.theme.Palette
import com.mohamed3on.iptv.ui.theme.ProgressLine
import com.mohamed3on.iptv.ui.theme.Radius
import com.mohamed3on.iptv.ui.theme.Space
import com.mohamed3on.iptv.ui.theme.FitText
import com.mohamed3on.iptv.ui.theme.ScrollText
import com.mohamed3on.iptv.ui.theme.Type
import com.mohamed3on.iptv.ui.theme.edgeFade
import com.mohamed3on.iptv.ui.theme.material
import com.mohamed3on.iptv.ui.theme.slideFade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import android.view.KeyEvent as AndroidKeyEvent

private val CARD = DpSize(132.dp, 198.dp)
private const val CARD_SCALE = 1.08f
/** A poster fades in as it loads; Coil skips the fade for posters already in memory. */
private const val POSTER_FADE_MS = 150
private val CARD_SHAPE = RoundedCornerShape(Radius.row)
private val RING = 3.dp
private val EDGE = 48.dp
private val HERO_HEIGHT = 128.dp
/** Type.headline's line: a row's title above its cards. */
private val ROW_TITLE = 27.dp
/** Where a card's top sits inside its row: under the title and the row's lift room. */
private val CARD_TOP = ROW_TITLE + Space.s + Space.m
private val KEYBOARD_WIDTH = 620.dp
/** Rows slide under the hero through a short fade, never a hard edge; the focused row sits just below it. */
private val FADE = Space.l
/** Focus rests this long before the hero changes, so holding a key never churns it. */
private const val HERO_REST_MS = 90L
private const val FOCUS_FRAMES = 30
/** Title pages and Up next set the name larger than anywhere else: the title is the screen's subject. */
private val BIG_TITLE = Type.display.copy(fontSize = 36.sp, lineHeight = 42.sp)
/** Up next's and a film page's text column: to 84% of the screen, where the backdrop's dense scrim reaches, so meta lines and plots rarely scroll. */
private val PAGE_WIDTH = 760.dp
private val SHOW_INFO_WIDTH = 420.dp
private val PROGRESS_WIDTH = 200.dp
/** Up next's cards: 16:9, so a backdrop fits whole. */
private val WIDE = DpSize(184.dp, 104.dp)
private val STILL = DpSize(128.dp, 72.dp)
/** Room kept at the hero's right for the Search button. */
private val SEARCH_ROOM = 120.dp
/** Prefs key: "1" while the rows show watched titles (they leave them out by default). */
private const val SHOW_WATCHED = "movies.showWatched"
/** Backdrops decode at 720p: darkened behind text, more pixels show nothing and cost the stick's memory. */
private val BACKDROP_PX = 1280 to 720
private const val BACKDROP_FADE_MS = 400
/** How tall the backdrop's fade into the ground is, above where the rows start. */
private val SOLID_BAND = 72.dp
/** A dark band along the top, so Search reads over a white sky. */
private val TOP_SCRIM = 120.dp
/** How far the backdrop sits back behind the rows page's hero. */
private const val HERO_DIM = 0.35f

/** What plays: a film, or a show's episode (Movies & Shows browses both). */
enum class LibraryKind(val title: String, val noun: String) {
    Movies("Movies & Shows", "films and shows"),
    Shows("Shows", "shows"),
}

/** What is on screen: Movies & Shows while browsing; a film or an episode while one plays. */
internal val LocalLibrary = staticCompositionLocalOf { LibraryKind.Movies }

/** A page over the browsing screen: a title's, or a person's titles. Back takes the top one off. */
private sealed interface LibraryPage {
    data class Title(val movie: Movie, val versions: Boolean = false) : LibraryPage
    data class Cast(val person: Person) : LibraryPage
}

/** Pages kept under the top one, so Back lands where each was left; past this the oldest goes. */
private const val MAX_PAGES = 6

/** A show's episode on screen: the show, its episodes, and which one plays. */
private data class ShowPlay(val show: Movie, val episodes: List<Episode>, val index: Int) {
    val episode get() = episodes[index]
}

/**
 * What plays: the movie (for a show, the episode as one), its [title] id and where it starts (ms);
 * [fallback] is the library's IPTV copy, for when the box can't say what else there is.
 */
private data class Playing(val movie: Movie, val from: Long, val title: TitleId, val fallback: Copy?, val show: ShowPlay? = null)

/** The library's own copy of a film or episode (provider stream [id]); none for a title the provider lacks. */
private fun libraryCopy(id: Int) = if (id > 0) Copy("iptv:$id", Capacity.Source.Iptv) else null

/** How long focus rests on an Up next card or an episode row before its title is prepared, so passing over one costs nothing. */
private const val WARM_REST_MS = 400L
/** Search asks once typing pauses this long. */
private const val SEARCH_DEBOUNCE_MS = 250L
/** The next episode is prepared this long after one starts, so its start has the line to itself. */
private const val NEXT_WARM_MS = 15_000L

/**
 * Movies & Shows: one title-first library of films and shows over the still-playing live channel, which
 * stops only once something plays (the account allows one stream) and resumes when the screen is left with
 * Back. It opens on Up next (what you were watching, Resume focused) when there is any, else on the
 * library's rows. Any title opens its title page (a film's, or a show's with its episodes), whose cast and
 * More like this open further pages; the next episode plays when one ends. [shown] is its entrance (0 → 1);
 * [open] is false while it leaves. [onPlaying] says whether a film or episode is on screen, so the live
 * picture steps aside for it.
 */
@Composable
fun MoviesLayer(
    feed: Feed,
    session: StreamSession,
    tiles: MultiView<ExoStreamPlayer>,
    shown: () -> Float,
    open: Boolean,
    onPlaying: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val sync = graph.titles.library
    /** Where a title was left: films and shows keep separate places (a show's names its episode). */
    fun storeOf(title: TitleId) = if (title.isShow) graph.showResume else graph.resume
    val history by graph.titles.history.items.collectAsState()
    // Watched marks: the stick's own, Trakt's, Letterboxd's and IMDb's together (U16).
    val watched by graph.marks.watched.collectAsState()
    val library by sync.items.collectAsState()
    val filmPlaces by graph.resume.entries.collectAsState()
    val showPlaces by graph.showResume.entries.collectAsState()
    val resume = remember(filmPlaces, showPlaces) { (filmPlaces + showPlaces).sortedByDescending { it.at } }
    val episodesHere by graph.watchedEpisodes.ids.collectAsState()
    val dismissed by graph.dismissed.ids.collectAsState()
    // Followed shows' next episodes (R31): none for a show part-way through (that episode shows instead), nor one watched here or taken out.
    val nextUp = remember(history, showPlaces, episodesHere, dismissed) {
        history?.next.orEmpty().filter { n -> showPlaces.none { it.title == n.show.titleId } && n.episode !in episodesHere && n.episode !in dismissed }
    }
    val live by rememberUpdatedState(session)
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf<Playing?>(null) }
    // The pages on screen over the browsing screen, the top one last.
    val pages = remember { mutableStateListOf<LibraryPage>() }
    var failed by remember { mutableStateOf<SyncError?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val refreshed = remember { booleanArrayOf(false) }
    LaunchedEffect(attempt) {
        failed = null
        // The history too: from disk at once, then asked again beside the library.
        launch {
            graph.titles.history.loadCached()
            graph.syncHistory()
        }
        // The copy on disk shows at once; the box is asked whether it changed (a 304 when not), on every open.
        sync.loadCached()
        val error = sync.sync(Duration.ZERO)
        refreshed[0] = true
        Check.log("movies_refreshed", "ok" to (error == null))
        if (sync.items.value == null) failed = error ?: SyncError.Malformed
        // The provider's films: Play's IPTV copy when the box can't list a film's copies (KTD13).
        graph.titles.movies.loadCached()
        graph.titles.movies.sync(12.hours)
    }
    // Back on the rows from a title page or a film, the box is asked again too, so scores TrueScore just learnt show.
    val browsing = open && playing == null && pages.isEmpty()
    LaunchedEffect(browsing) { if (browsing && refreshed[0]) sync.sync(Duration.ZERO) }
    fun push(page: LibraryPage) {
        if (pages.size >= MAX_PAGES) pages.removeAt(0)
        pages.add(page)
    }
    // Old marks move to title ids at launch; if that failed (offline), opening Movies tries again.
    LaunchedEffect(Unit) { graph.migration.run(graph.titles::titleIds) }
    // The setup reader is built on the main thread (its callbacks land there) before copies are ranked off it.
    remember { graph.setup }
    // While an episode plays, the next one is prepared, so Next episode and the end of this one start at once (KTD13).
    LaunchedEffect(playing) {
        val s = playing?.show ?: return@LaunchedEffect
        delay(NEXT_WARM_MS)
        s.episodes.getOrNull(s.index + 1)?.titleId?.ifEmpty { null }?.let(graph.films::warm)
    }

    // However the layer goes, the live picture comes back.
    DisposableEffect(Unit) { onDispose { onPlaying(false) } }

    // Live TV stays stopped while Movies & Shows is open, even when the app starts it again (Home and back): the
    // provider's one connection and the bandwidth are the films' (KTD20). Leaving re-tunes the same channel.
    LaunchedEffect(session, open) {
        if (!open) {
            if (session.state.value.phase == Phase.Stopped) session.retry()
            return@LaunchedEffect
        }
        session.state.collect {
            if (it.phase == Phase.Stopped) return@collect
            tiles.close()
            session.stop()
        }
    }

    fun leave() = onClose()

    fun play(next: Playing) {
        tiles.close()
        live.stop()
        playing = next
        onPlaying(true)
        // Closing the app mid-film reopens it playing from its saved place (a show: its episode up next).
        graph.prefs.put(Check.LAST_SCREEN, "play:${next.show?.show?.titleId ?: next.title}")
    }

    fun episode(show: Movie, episodes: List<Episode>, index: Int, from: Long): Playing {
        val e = episodes[index]
        val name = listOf(show.title.title, e.code, e.title).filter(String::isNotEmpty).joinToString(" · ")
        val movie = Movie(id = e.id, name = name, poster = show.poster, group = show.group, ext = e.ext)
        return Playing(movie, from, e.titleId, libraryCopy(e.id), ShowPlay(show, episodes, index))
    }

    /** An IPTV copy's stream: the container the box named, else the library's. */
    fun iptvUrl(p: Playing): (Copy) -> String = { c ->
        val id = c.id.removePrefix("iptv:").toInt()
        val ext = graph.films.state(p.title).value.answer?.ext(c.id) ?: p.movie.ext
        if (p.show != null) feed.route.episodeUrl(graph.route.effective.value, id, ext) else feed.route.movieUrl(graph.route.effective.value, id, ext)
    }

    /** The library's IPTV copy of a film: its own provider id, else the provider's film list's (none for a title it lacks). */
    fun fallbackOf(movie: Movie) = libraryCopy(movie.id.takeIf { it > 0 } ?: graph.titles.movies.items.value?.findTitle(movie.titleId)?.id ?: 0)

    /** A film from its saved place, or from the start. */
    fun playFilm(movie: Movie, fromStart: Boolean) =
        play(Playing(movie, if (fromStart) 0 else graph.resume.startAt(movie.titleId), movie.titleId, fallbackOf(movie)))

    /** A show's episode [index], from its saved place or the start. */
    fun playEpisode(show: Movie, episodes: List<Episode>, index: Int, fromStart: Boolean) =
        play(episode(show, episodes, index, if (fromStart) 0 else graph.showResume.startAt(show.titleId, episodes[index].titleId)))

    /** Up next's buttons: a film at its place; a show's episode up next (its episodes are kept a while, so this is quick). */
    fun resumeTitle(movie: Movie, fromStart: Boolean) {
        if (!movie.titleId.isShow) return playFilm(movie, fromStart)
        scope.launch {
            val eps = graph.titles.showOf(movie).getOrNull()?.episodes?.takeIf { it.isNotEmpty() }
            val seen = graph.marks.watched.value
            val place = graph.showResume.find(movie.titleId) ?: nextUp.firstOrNull { it.show.titleId == movie.titleId }?.place()
            if (eps == null) push(LibraryPage.Title(movie)) else playEpisode(movie, eps, upNextIndex(eps, place, seen), fromStart)
        }
    }

    /** The episode after [s] plays, if there is one. */
    fun nextEpisode(s: ShowPlay?): Boolean {
        val next = s?.takeIf { it.index < it.episodes.lastIndex } ?: return false
        // Continue watching moves on to the next episode even if it is left in its first minute.
        graph.showResume.save(next.show, 0, 0, next.episodes[next.index + 1].titleId, next.episodes[next.index + 1].code)
        playing = episode(next.show, next.episodes, next.index + 1, 0)
        return true
    }

    fun stopped() {
        playing = null
        onPlaying(false)
        graph.prefs.put(Check.LAST_SCREEN, (pages.lastOrNull() as? LibraryPage.Title)?.let { "title:${it.movie.titleId}" } ?: "movies")
    }

    // firetv/check.sh (system/Check): the browsing screen is shown (two frames: its rows build a frame late), the
    // debrid account's state, and the screen an open target names, once the library is in.
    LaunchedEffect(library != null) {
        if (library == null) return@LaunchedEffect
        repeat(2) { withFrameNanos {} }
        Check.log("movies_shown", "from" to if (refreshed[0]) "box" else "disk")
    }
    LaunchedEffect(Unit) { graph.debrid.status.collect { Check.log("debrid", "service" to it.name, "status" to it.state) } }
    val check by Check.open.collectAsState()
    LaunchedEffect(check, library) {
        val target = check ?: return@LaunchedEffect
        val verb = target.substringBefore(':')
        if (verb == "live") return@LaunchedEffect leave().also { Check.open.value = null }
        val lib = library ?: return@LaunchedEffect
        if (verb != "search") Check.open.value = null // MoviesBrowse takes a search
        if (verb != "play" && playing != null) stopped()
        pages.clear()
        val id = target.substringAfter(':').substringBefore('@')
        // play:<titleId>@<copyId> pins that copy first, as choosing it in Versions does.
        target.substringAfter('@', "").ifEmpty { null }?.let { copy -> graph.copyChoices.pin(id, copy).also { graph.films.rerank(id) } }
        val title = lib.find(id) ?: Movie(id = -1, name = id, group = "", titleId = id)
        when (verb) {
            "title", "versions" -> push(LibraryPage.Title(title, versions = verb == "versions"))
            "play" -> resumeTitle(title, fromStart = false)
        }
    }

    CompositionLocalProvider(LocalLibrary provides LibraryKind.Movies) {
        Box(Modifier.fillMaxSize()) {
            MoviesBrowse(
                library = library,
                resume = resume,
                history = history,
                nextUp = nextUp,
                watched = watched,
                failed = failed,
                onRetry = { attempt++ },
                onRemove = {
                    storeOf(it.titleId).remove(it.titleId)
                    // Its next episode stays out too, until a later one airs.
                    history?.next?.firstOrNull { n -> n.show.titleId == it.titleId }?.let { n -> graph.dismissed.mark(n.episode) }
                },
                active = browsing,
                hidden = playing != null || pages.isNotEmpty(),
                shown = shown,
                // A person found by search opens their page; anything else its title page.
                onOpen = { m -> m.titleId.removePrefix("person:").takeIf { m.titleId.startsWith("person:") }?.toIntOrNull()?.let { push(LibraryPage.Cast(Person(it, m.name))) } ?: push(LibraryPage.Title(m)) },
                onResume = { resumeTitle(it, fromStart = false) },
                onStartOver = { resumeTitle(it, fromStart = true) },
                onBack = ::leave,
            )
            pages.forEachIndexed { i, page ->
                key(i, page) {
                    val top = i == pages.lastIndex
                    val active = open && playing == null && top
                    val hidden = playing != null || !top
                    val back = { pages.removeAt(pages.lastIndex).also { if (pages.isEmpty()) graph.prefs.put(Check.LAST_SCREEN, "movies") } }
                    when (page) {
                        is LibraryPage.Cast -> PersonPage(page.person, watched, active, hidden, onOpen = { push(LibraryPage.Title(it)) }, onBack = { back() })
                        is LibraryPage.Title -> {
                            val title = page.movie
                            val saved = resume.firstOrNull { it.title == title.titleId } ?: nextUp.firstOrNull { it.show.titleId == title.titleId }?.place()
                            if (!title.titleId.isShow) FilmPage(
                                film = title,
                                openVersions = page.versions,
                                resume = saved,
                                watched = watched,
                                active = active,
                                hidden = hidden,
                                onPlay = { fromStart -> playFilm(title, fromStart) },
                                onOpen = { push(LibraryPage.Title(it)) },
                                onPerson = { push(LibraryPage.Cast(it)) },
                                onBack = { back() },
                            ) else ShowPage(
                                show = title,
                                resume = saved,
                                watched = watched,
                                active = active,
                                hidden = hidden,
                                onPlay = { episodes, e, fromStart -> playEpisode(title, episodes, e, fromStart) },
                                onOpen = { push(LibraryPage.Title(it)) },
                                onPerson = { push(LibraryPage.Cast(it)) },
                                onBack = { back() },
                            )
                        }
                    }
                }
            }
            playing?.let { p ->
                key(p) {
                    val s = p.show
                    // The player words its states for a film or an episode.
                    CompositionLocalProvider(LocalLibrary provides if (s != null) LibraryKind.Shows else LibraryKind.Movies) {
                        MoviePlayback(
                            movie = p.movie,
                            of = if (s != null) PlayOf(s.show, s.episode) else PlayOf(p.movie),
                            from = p.from,
                            fallback = p.fallback,
                            iptvUrl = iptvUrl(p),
                            onEnded = { if (!nextEpisode(s)) stopped() },
                            onClose = ::stopped,
                            onNext = s?.takeIf { it.index < it.episodes.lastIndex }?.let { { nextEpisode(it) } },
                        )
                    }
                }
            }
        }
    }
}

/** A title the provider doesn't carry, as its saved place names it: no provider id (negative, never asked about), so only its copies play it. */
private fun placeTitle(r: Resume) =
    Movie(id = -1 - (r.title.hashCode() and Int.MAX_VALUE), name = listOfNotNull(r.name, r.year?.let { "($it)" }).joinToString(" "), poster = r.poster, group = "", titleId = r.title)

/** A show's episode up next: the one in progress, else the first not yet watched, else the first. */
private fun upNextIndex(episodes: List<Episode>, resume: Resume?, watched: Set<TitleId>): Int =
    episodes.indexOfFirst { it.titleId == resume?.episode }.takeIf { it >= 0 }
        ?: episodes.indexOfFirst { it.titleId !in watched }.takeIf { it >= 0 }
        ?: 0

/** A next episode as Up next's place: the show and its episode, nothing played yet ([Resume.at] 0). */
private fun NextEpisode.place() = Resume(show.titleId, show.title.title, show.poster, show.title.year, 0, 0, 0, episode, label)

/** A row of the library: one group's titles. [progress] is every saved place, by title id. */
private const val CONTINUE_ROW = "h|continue"
/** Titles a row shows once watched ones are out (the box sends each row long enough for that). */
private const val ROW_SHOWN = 60
/** What was played (or a followed show's episode aired) within this long leads the screen as Up next; older is a Continue watching row. */
private const val RECENT_MS = 7 * 86_400_000L
/** The library's genre rows (api/_library.ts GENRES): one Genres row of tiles stands for them, last. */
private val GENRE_ROWS = setOf("action", "comedy", "drama", "thriller", "crime", "scifi", "horror", "romance", "animation", "documentary")
private const val GENRES_ROW = "h|genres"

/** [genres]: on the Genres row, each tile's genre (its row's name) by the card key of the title that pictures it. */
private class MovieRow(val key: String, val title: String, val badge: String?, val movies: List<Movie>, val progress: Map<TitleId, Float>, val genres: Map<String, String>? = null)

/** A Genres tile a genre's titles were opened from: its card's movie id and its place in the row. */
private class GenreTile(val id: Int, val col: Int)

/**
 * The owner's Watchlist when it has titles (R26), then the library's rows (TrueScore's, trending, new, languages; the
 * provider's groups when TMDB's are missing), then one Genres row; what is part-way through lives in Up next, not here.
 * [hideWatched] leaves watched titles out of the library's rows (a row left empty goes); each shows its first 60; the
 * Watchlist keeps everything. A genre is a tile pictured by its best title not already picturing another; OK opens its
 * titles. What is part-way through but not recent ([continuing]) is a plain Continue watching row after TrueScore's.
 */
private fun rowsOf(library: Catalog?, resume: List<Resume>, history: History?, watched: Set<TitleId>, hideWatched: Boolean, continuing: List<Movie>): List<MovieRow> {
    library ?: return emptyList()
    val progress = resume.filter { it.dur > 0 }.associate { it.title to it.pos.toFloat() / it.dur }
    // The library's own card for a title it has (its genres), else the history's.
    val mine = listOfNotNull(
        history?.watchlist?.map { library.find(it.titleId) ?: it }?.takeIf { it.isNotEmpty() }?.let { MovieRow("h|watchlist", "Watchlist", null, it, progress) },
    )
    val genres = LinkedHashMap<String, String>()
    val tiles = ArrayList<Movie>()
    val rows = library.rows.mapNotNull { row ->
        val label = groupLabel(row.name)
        val movies = rowTitles(row, watched, hideWatched).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        if (row.id in GENRE_ROWS) {
            movies.firstOrNull { it.key !in genres }?.let { genres[it.key] = label.name; tiles += it }
            return@mapNotNull null
        }
        MovieRow("r|${row.id}", label.name, label.badge, movies, progress)
    }
    val at = rows.indexOfLast { it.key.startsWith("r|ts-") } + 1
    val later = continuing.takeIf { it.isNotEmpty() }?.let { MovieRow(CONTINUE_ROW, "Continue watching", null, it, progress) }
    val genreRow = tiles.takeIf { it.isNotEmpty() }?.let { MovieRow(GENRES_ROW, "Genres", null, it, progress, genres) }
    return mine + rows.take(at) + listOfNotNull(later) + rows.drop(at) + listOfNotNull(genreRow)
}

/** A library row's titles as the screen shows them: watched ones out when [hideWatched], the first 60. */
private fun rowTitles(row: CatalogRow, watched: Set<TitleId>, hideWatched: Boolean) =
    (if (hideWatched) row.movies.filter { it.titleId !in watched } else row.movies).take(ROW_SHOWN)

/** Which part of the browsing screen is up. */
private enum class BrowsePage { UpNext, Rows, Search }

/** Where focus sits on the browsing screen, so a page change knows when its own target has it. */
private enum class FocusAt { UpNext, Rows, Search, SearchButton, Other }

/**
 * The browsing screen over the focused title's backdrop: Up next (what you were watching, Resume
 * focused), the library's rows (Down from Up next), or search. While a title page or a film is up it
 * stays composed but undrawn ([hidden]), so Back lands where you were. [active] is when it should hold focus.
 */
@Composable
private fun MoviesBrowse(
    library: Catalog?,
    resume: List<Resume>,
    history: History?,
    nextUp: List<NextEpisode>,
    watched: Set<TitleId>,
    failed: SyncError?,
    onRetry: () -> Unit,
    onRemove: (Movie) -> Unit,
    active: Boolean,
    hidden: Boolean,
    shown: () -> Float,
    onOpen: (Movie) -> Unit,
    onResume: (Movie) -> Unit,
    onStartOver: (Movie) -> Unit,
    onBack: () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val prefs = LocalContext.current.graph.prefs
    val scope = rememberCoroutineScope()
    var hideWatched by remember { mutableStateOf(prefs.get(SHOW_WATCHED) == null) }
    // ☰ on a card: its actions panel, with Remove from Up next ([Actions.removable]) and the rows' Hide watched setting.
    var actions by remember { mutableStateOf<Actions?>(null) }
    // Each saved place with its title as last played (its provider id, for its episodes and IPTV copy) and as the
    // library has it now (tier and score), newest first; one played only from Real-Debrid from its own name and poster (R21).
    // Then followed shows' next episodes, latest aired first: one takes the hero only when nothing is part-way through.
    // Only what was played, or aired, in the last week is Up next; the rest is the Continue watching row.
    val (upNext, older) = remember(library, resume, nextUp) {
        if (library == null) return@remember emptyList<Pair<Movie, Resume>>() to emptyList<Movie>()
        val since = System.currentTimeMillis() - RECENT_MS
        val sinceDay = java.time.LocalDate.now().minusDays(RECENT_MS / 86_400_000L).toString()
        val all = resume.map { r ->
            val now = library.find(r.title)
            Triple(r.iptv?.let { if (now != null) it.copy(lib = now.lib, group = now.group) else it } ?: now ?: placeTitle(r), r, r.at >= since)
        } + nextUp.map { n -> Triple(library.find(n.show.titleId) ?: n.show, n.place(), n.air >= sinceDay) }
        all.filter { it.third }.map { it.first to it.second } to all.filterNot { it.third }.map { it.first }
    }
    val rows = remember(library, resume, history, watched, hideWatched, older) { rowsOf(library, resume, history, watched, hideWatched, older) }
    val newShows = remember(nextUp) { nextUp.filter { it.new }.mapTo(HashSet()) { it.show.titleId } }
    var page by remember { mutableStateOf(BrowsePage.UpNext) }
    var beforeSearch by remember { mutableStateOf(BrowsePage.UpNext) }
    // Up next only while there is something to resume.
    val shownPage = if (page == BrowsePage.UpNext && upNext.isEmpty()) BrowsePage.Rows else page
    // The title the hero and the backdrop describe.
    val focused = remember { mutableStateOf<Movie?>(null) }
    var query by remember { mutableStateOf("") }
    var hasFocus by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(FocusAt.Other) }
    val content = remember { FocusRequester() }
    val resumeButton = remember { FocusRequester() }
    val results = remember { FocusRequester() }
    val keys = remember { FocusRequester() }
    val searchButton = remember { FocusRequester() }
    // The page is built a frame after the rest, so neither frame of the opening is long enough to drop.
    var built by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        built = true
    }
    val kind = when {
        library != null -> if (built) 2 else -1
        failed != null -> 1
        else -> 0
    }

    // Focus goes to the page's own target whenever the page changes or the screen comes back (after a
    // film or a title page); leaving search goes back to its button.
    val leftSearch = remember { booleanArrayOf(false) }
    // The search result a title page was opened from: back from it, focus returns to that card, not the keyboard.
    var openedResult by remember { mutableStateOf<Int?>(null) }
    val resultBack = remember { FocusRequester() }
    LaunchedEffect(active, kind, shownPage) {
        if (!active) return@LaunchedEffect
        val (target, part) = when {
            kind != 2 -> content to FocusAt.Other
            leftSearch[0] -> searchButton to FocusAt.SearchButton
            shownPage == BrowsePage.Search -> results to FocusAt.Search
            shownPage == BrowsePage.UpNext -> resumeButton to FocusAt.UpNext
            else -> content to FocusAt.Rows
        }
        leftSearch[0] = false
        val card = openedResult?.takeIf { shownPage == BrowsePage.Search }
        try {
            repeat(FOCUS_FRAMES) { i ->
                withFrameNanos {}
                val landed = if (card != null && i < FOCUS_FRAMES / 2) focused.value?.id == card else at == part || part == FocusAt.Other
                if (hasFocus && landed) return@LaunchedEffect
                // The opened card first; gone (a new search), the page's own target.
                runCatching { (if (card != null && i < FOCUS_FRAMES / 2) resultBack else target).requestFocus() }
            }
        } finally {
            if (card != null) openedResult = null
        }
    }
    // A library row named like a query ("comedy", "arabic"), as the rows show it: search answers it at once, offline too.
    val rowNamed: (String) -> List<Movie>? = { q -> library?.rows?.firstOrNull { it.name.equals(q.trim(), ignoreCase = true) }?.let { rowTitles(it, watched, hideWatched) } }
    // The Genres tile whose titles search shows: Back from them returns to it.
    val genreFrom = remember { mutableStateOf<GenreTile?>(null) }
    var inGenre by remember { mutableStateOf(false) }
    fun openSearch() {
        beforeSearch = shownPage
        focused.value = null
        inGenre = false
        page = BrowsePage.Search
    }
    fun openGenre(name: String, from: GenreTile) {
        openSearch()
        inGenre = true
        genreFrom.value = from
        query = name
        // Focus goes to its first title, as on coming back to a result: the Fire TV keyboard stays shut.
        openedResult = rowNamed(name)?.firstOrNull()?.id
    }
    fun closeSearch() {
        leftSearch[0] = !inGenre
        if (inGenre) query = ""
        inGenre = false
        focused.value = null
        page = beforeSearch
    }
    // firetv/check.sh's `search:<query>` (system/Check), once the rows are built.
    val check by Check.open.collectAsState()
    LaunchedEffect(check, kind) {
        val q = check?.takeIf { it.startsWith("search:") && kind == 2 }?.removePrefix("search:") ?: return@LaunchedEffect
        Check.open.value = null
        if (page != BrowsePage.Search) openSearch()
        query = q
    }

    val rested = rememberRested(focused)
    val heroInfo = rememberInfo(rested)
    Box(
        Modifier
            .fillMaxSize()
            // Undrawn under a title page or a film: an alpha-0 layer costs the GPU nothing.
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .onFocusChanged { hasFocus = it.hasFocus }
            .onKeyEvent {
                if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) when {
                    shownPage == BrowsePage.Search -> closeSearch()
                    shownPage == BrowsePage.Rows && upNext.isNotEmpty() -> page = BrowsePage.UpNext
                    else -> onBack()
                }
                true // the key-up too, so Back never reaches the player or leaves the app
            }
            .focusGroup(),
    ) { CompositionLocalProvider(LocalStill provides hidden) {
        Backdrop(backdropOf(rested, heroInfo), solidFrom = if (shownPage == BrowsePage.UpNext) null else Space.safeY + HERO_HEIGHT, modifier = Modifier.graphicsLayer { alpha = shown().coerceIn(0f, 1f) })
        Box(Modifier.fillMaxSize().slideFade(shown, Edge.Bottom, reduced)) {
            when {
                kind == 1 -> Box(Modifier.padding(top = Space.xxxl)) { Unavailable(content, onRetry) }
                kind == 0 -> Box(Modifier.padding(top = Space.xxxl)) { LoadingMovies(content) }
                kind != 2 -> {}
                else -> AnimatedContent(
                    targetState = shownPage == BrowsePage.UpNext,
                    transitionSpec = {
                        when {
                            reduced -> fadeIn(tween(Motion.FADE_MS)) togetherWith fadeOut(tween(Motion.FADE_MS))
                            // Back to Up next: the rows sink back under the band.
                            targetState -> fadeIn(Motion.spring(0.3f)) togetherWith
                                (slideOutVertically(Motion.panel()) { it / 3 } + fadeOut(Motion.panel()))
                            // Down to the rows: they rise with the band behind them (Backdrop's cover, the same spring).
                            else -> (slideInVertically(Motion.panel()) { it / 3 } + fadeIn(Motion.panel())) togetherWith
                                fadeOut(Motion.spring(0.2f))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "browse",
                ) { upNextShown ->
                    if (upNextShown) UpNextPage(
                        entries = upNext,
                        newShows = newShows,
                        resumeButton = resumeButton,
                        onFocused = { focused.value = it },
                        onResume = onResume,
                        onStartOver = onStartOver,
                        onOpen = onOpen,
                        onMenu = { actions = Actions(it, removable = true) },
                        onDown = { page = BrowsePage.Rows },
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) at = FocusAt.UpNext },
                    ) else Column(Modifier.fillMaxSize().padding(top = Space.safeY)) {
                        val search = shownPage == BrowsePage.Search
                        Hero(
                            if (search && focused.value == null) null else rested, heroInfo, resume,
                            placeholder = if (search) "Search ${LocalLibrary.current.noun}" else LocalLibrary.current.title,
                            modifier = Modifier.fillMaxWidth().height(HERO_HEIGHT).padding(start = EDGE, end = EDGE + SEARCH_ROOM),
                        )
                        if (search) Box(Modifier.onFocusChanged { if (it.hasFocus) at = FocusAt.Search }) {
                            SearchGrid(
                                library!!, query, { query = it }, results, keys, rowNamed, onFocused = { focused.value = it },
                                back = openedResult?.let { it to resultBack },
                                stray = { !hasFocus },
                                // Coming back to a result is no arrival at Search: the Fire TV keyboard stays shut.
                                arrive = { openedResult == null },
                                onPlay = {
                                    openedResult = it.id
                                    focused.value = null // so "back on the card" is only true once it is
                                    onOpen(it)
                                },
                                onMenu = { actions = Actions(it) },
                            )
                        } else Box(Modifier.onFocusChanged { if (it.hasFocus) at = FocusAt.Rows }) {
                            Rows(
                                rows, watched, content, active,
                                onFocused = { focused.value = it },
                                onOpen = onOpen,
                                onMenu = { m, continuing -> actions = Actions(m, removable = continuing, rows = true) },
                                // Up from the first row: back to Up next, else to Search.
                                onTop = { if (upNext.isNotEmpty()) page = BrowsePage.UpNext else runCatching { searchButton.requestFocus() } },
                                onGenre = ::openGenre,
                                returnTo = genreFrom,
                            )
                        }
                    }
                }
            }
            if (kind == 2 && shownPage != BrowsePage.Search) PageButton(
                "Search", ::openSearch,
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = Space.safeY, end = EDGE)
                    .focusRequester(searchButton)
                    .onFocusChanged { if (it.isFocused) at = FocusAt.SearchButton },
            )
        }
        SlidePanel(actions) { a, open, m ->
            TitleActions(
                a.movie,
                onClose = {
                    actions = null
                    // Back where it was: the card (or its row's, when it left), the result, or Resume.
                    scope.launch {
                        withFrameNanos {}
                        runCatching { (if (shownPage == BrowsePage.Search) results else if (shownPage == BrowsePage.UpNext) resumeButton else content).requestFocus() }
                    }
                },
                onRemove = if (a.removable) ({ onRemove(a.movie) }) else null,
                // Watched titles in the library's rows, or not (hidden at first; the Watchlist, Up next and search keep them); kept across launches.
                hideWatched = if (a.rows) hideWatched else null,
                onHideWatched = {
                    hideWatched = !hideWatched
                    prefs.put(SHOW_WATCHED, if (hideWatched) null else "1")
                },
                modifier = m,
                open = open,
            )
        }
    } }
}

/** ☰'s panel on the browsing screen: for [movie], from Up next or Continue watching ([removable]) or the rows ([rows]). */
private class Actions(val movie: Movie, val removable: Boolean = false, val rows: Boolean = false)

/**
 * ☰ on a card or a title page: Add to / Remove from Watchlist (not a title only Letterboxd's list holds), Mark watched or
 * unwatched, Rate, then Remove from Up next ([onRemove]) and the rows' Hide watched titles setting ([hideWatched]) where they apply.
 */
@Composable
private fun TitleActions(
    movie: Movie, onClose: () -> Unit, onRemove: (() -> Unit)? = null, hideWatched: Boolean? = null, onHideWatched: () -> Unit = {}, modifier: Modifier = Modifier,
    open: Boolean = true,
) {
    val graph = LocalContext.current.graph
    val id = movie.titleId
    val onList = id in graph.watchlist.ids.collectAsState().value
    val letterboxdOnly = onList && id in graph.watchlist.letterboxd.collectAsState().value
    val seen = id in graph.marks.watched.collectAsState().value
    val markable = graph.marks.canMark(id)
    MarksPanel(
        movie.title.title,
        listOfNotNull(
            if (graph.watchlist.canHold(id) && !letterboxdOnly) (if (onList) "Remove from Watchlist" else "Add to Watchlist") to { graph.watchlist.toggle(id); onClose() } else null,
            if (markable) (if (seen) "Mark unwatched" else "Mark watched") to { graph.marks.setWatched(id, !seen); onClose() } else null,
        ),
        graph.marks.ratings.collectAsState().value[id],
        onRate = if (markable) ({ graph.marks.rate(id, it); onClose() }) else null,
        onClose = onClose,
        modifier = modifier,
        after = listOfNotNull(
            onRemove?.let { "Remove from Up next" to { it(); onClose() } },
            hideWatched?.let { "Hide watched titles: ${if (it) "On" else "Off"}" to { onHideWatched(); onClose() } },
        ),
        open = open,
    )
}

/** [focused] once it has rested for [HERO_REST_MS], so holding a key never churns what depends on it. */
@Composable
private fun rememberRested(focused: State<Movie?>): Movie? {
    var rested by remember { mutableStateOf(focused.value) }
    LaunchedEffect(Unit) {
        snapshotFlow { focused.value }.collectLatest {
            delay(HERO_REST_MS)
            rested = it
        }
    }
    return rested
}

/**
 * A title's plot, backdrop, runtime, genres and cast: TMDB's for a title it knows (/api/app/title, with its
 * people and More like this), else the provider's (a film's /api/app/movie, a show's episodes call). All are
 * kept for the app session, so a title seen before answers on its first frame; a title page ([fresh]) asks again
 * when that copy is over 2 minutes old and updates in place. A TMDB title answered without scores is asked once
 * more 3 s later, by when the box has usually fetched them.
 */
@Composable
private fun rememberInfo(movie: Movie?, fresh: Boolean = false): MovieInfo? {
    val sync = LocalContext.current.graph.titles
    val peek = movie?.let(sync::peekInfo)
    return produceState(peek, movie?.titleId, movie?.id) {
        val m = movie ?: return@produceState
        value = sync.info(m, fresh).getOrNull() ?: value
        if (m.titleId.isTmdb && value?.known == false) {
            delay(3_000)
            value = sync.info(m).getOrNull() ?: value
        }
    }.value
}

/** A title's backdrop: its page's, else the library's (known before the page answers). */
private fun backdropOf(movie: Movie?, info: MovieInfo?): String? =
    info?.backdrop?.ifEmpty { null } ?: movie?.lib?.b?.ifEmpty { null }?.let { tmdbImage(it, "w1280") }

/**
 * The focused title's backdrop over the whole screen, darkened from the left and the bottom where text
 * sits; with [solidFrom], it fades to the plain ground there, so rows below sit on ink (and their edge
 * fades, drawn in ink, match it). A new backdrop is loaded before it cross-fades in,
 * so the screen never flashes to ink between titles; a title with none fades to the plain ground.
 * The scrims are baked into the still ([bakedBackdrop]), so a frame draws one or two pictures and no
 * offscreen layer: compositing the layered crossfade cost the stick's GPU ~50 ms a frame.
 */
@Composable
private fun Backdrop(url: String?, solidFrom: Dp?, modifier: Modifier = Modifier) {
    val context = LocalPlatformContext.current
    val topShare = TOP_SCRIM.value / LocalConfiguration.current.screenHeightDp
    // [shown] fades in over [under] (drawn whole), or, when null, [under] fades out to ink.
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    var under by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    val lastSolid = remember { arrayOf(0.dp) }
    val drawn = remember { booleanArrayOf(false) }
    LaunchedEffect(url) {
        val next = if (url.isNullOrEmpty()) null else bakedBackdrop(context, url, topShare) ?: return@LaunchedEffect
        if (next == shown) return@LaunchedEffect
        // The first picture is there at once: fading it in made a title page's opening frames drop.
        if (next != null && !drawn[0]) {
            drawn[0] = true
            shown = next
            return@LaunchedEffect
        }
        under = shown
        shown = next
        fade.snapTo(0f)
        fade.animateTo(1f, tween(BACKDROP_FADE_MS))
        under = null
    }
    // Up next to the rows: the ground rises from the bottom to [solidFrom].
    val cover = animateFloatAsState(if (solidFrom != null) 1f else 0f, Motion.panel(), label = "cover")
    val solid = solidFrom ?: lastSolid[0]
    if (solidFrom != null) lastSolid[0] = solidFrom
    Box(
        modifier.fillMaxSize().drawWithCache {
            val ink = Palette.ink
            val band = SOLID_BAND.toPx()
            val whole = IntSize(size.width.toInt(), size.height.toInt())
            onDrawBehind {
                val p = fade.value
                val top = shown
                val base = under
                if (top == null || (base == null && p < 1f)) drawRect(ink)
                if (top == null) {
                    base?.let { drawImage(it, dstSize = whole, alpha = 1f - p) }
                } else {
                    base?.let { drawImage(it, dstSize = whole) }
                    drawImage(top, dstSize = whole, alpha = p)
                }
                val c = cover.value.coerceIn(0f, 1f)
                // The rows page sets a wider hero over the picture: it sits back further.
                if (c > 0f) {
                    drawRect(ink, alpha = HERO_DIM * c)
                    val y = size.height - (size.height - solid.toPx()) * c
                    drawRect(Brush.verticalGradient(listOf(ink.copy(alpha = 0f), ink), startY = y - band, endY = y), topLeft = Offset(0f, y - band), size = Size(size.width, band))
                    drawRect(ink, topLeft = Offset(0f, y), size = Size(size.width, size.height - y))
                }
            }
        },
    )
}

/** The last few baked backdrops, so a title page opened from its hero, or Back to the hero, starts at once. */
private val baked = LruCache<String, ImageBitmap>(3)

/**
 * [url]'s still at 720p with the scrims drawn in, on the CPU off the main thread, and handed to the GPU
 * early: ink across the text column, dense enough to the left (~75% of the width) that plot text
 * clears 4.5:1 over a white sky, then off by 95%; a bottom scrim; a top band ([topShare] of the height) so Search
 * reads. Null when it doesn't load.
 */
private suspend fun bakedBackdrop(context: PlatformContext, url: String, topShare: Float): ImageBitmap? {
    baked.get(url)?.let { return it }
    val request = ImageRequest.Builder(context).data(tmdbAt(url, "w1280")).size(BACKDROP_PX.first, BACKDROP_PX.second).allowHardware(false).build()
    val src = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    val out = withContext(Dispatchers.Default) {
        val (w, h) = BACKDROP_PX
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        // Centre-cropped to the screen's 16:9.
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = w / scale
        val ch = h / scale
        val from = Rect(((src.width - cw) / 2).toInt(), ((src.height - ch) / 2).toInt(), ((src.width + cw) / 2).toInt(), ((src.height + ch) / 2).toInt())
        canvas.drawColor(Palette.ink.toArgb())
        canvas.drawBitmap(src, from, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        fun ink(alpha: Float) = Palette.ink.copy(alpha = alpha).toArgb()
        val paint = Paint()
        fun scrim(x1: Float, y1: Float, colors: IntArray, stops: FloatArray, bottom: Float = h.toFloat()) {
            paint.shader = LinearGradient(0f, 0f, x1, y1, colors, stops, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w.toFloat(), bottom, paint)
        }
        scrim(w.toFloat(), 0f, intArrayOf(ink(0.94f), ink(0.86f), ink(0f)), floatArrayOf(0f, 0.75f, 0.95f))
        scrim(0f, h.toFloat(), intArrayOf(ink(0f), ink(0.96f)), floatArrayOf(0.4f, 1f))
        scrim(0f, h * topShare, intArrayOf(ink(0.7f), ink(0f)), floatArrayOf(0f, 1f), bottom = h * topShare)
        bitmap.setHasAlpha(false)
        // A hardware copy uploads here, off the main and render threads: drawn fresh, the upload cost a title page's second frame ~20 ms.
        (bitmap.copy(Bitmap.Config.HARDWARE, false)?.also { bitmap.recycle() } ?: bitmap.apply { prepareToDraw() }).asImageBitmap()
    }
    baked.put(url, out)
    return out
}

/**
 * Up next: the title you were watching (or the card focused in the strip) told large over its
 * backdrop, Resume focused, then the rest of what is part-way through as wide cards. OK on a card
 * opens its title page; Menu takes it out; Down goes on to the library's rows.
 */
@Composable
private fun UpNextPage(
    entries: List<Pair<Movie, Resume>>,
    newShows: Set<TitleId>,
    resumeButton: FocusRequester,
    onFocused: (Movie) -> Unit,
    onResume: (Movie) -> Unit,
    onStartOver: (Movie) -> Unit,
    onOpen: (Movie) -> Unit,
    onMenu: (Movie) -> Unit,
    onDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The hero's title: the card focused last in the strip, else the newest.
    var currentId by remember { mutableStateOf<String?>(null) }
    val (movie, saved) = entries.firstOrNull { it.first.key == currentId } ?: entries.first()
    val shows = movie.titleId.isShow
    val films = LocalContext.current.graph.films
    LaunchedEffect(movie.key) {
        onFocused(movie)
        // Resume here skips the title page, so the copies are prepared once focus rests on a title (KTD13).
        delay(WARM_REST_MS)
        (if (shows) saved.episode else saved.title)?.ifEmpty { null }?.let(films::warm)
    }
    val info = rememberInfo(movie)
    val focusManager = LocalFocusManager.current
    Column(modifier.fillMaxSize().padding(bottom = Space.safeY), verticalArrangement = Arrangement.Bottom) {
        // A fixed set of lines, so the buttons never move as the hero changes title.
        AnimatedContent(
            targetState = Triple(movie, saved, info),
            contentKey = { it.first.key to (it.third != null) },
            transitionSpec = { fadeIn(Motion.spring(0.3f)) togetherWith fadeOut(Motion.spring(0.2f)) },
            label = "upnext",
        ) { (m, r, i) ->
            Column(Modifier.padding(start = EDGE).widthIn(max = PAGE_WIDTH), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                FitText(m.title.title, style = BIG_TITLE, active = true)
                MetaLine(m, i)
                Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(r.label, r.takeIf { it.dur > 0 && it.pos > 0 }?.let { leftLabel(it.dur - it.pos) }).joinToString(" · "), style = Type.headline, maxLines = 1)
                    if (r.dur > 0) ProgressLine({ r.pos.toFloat() / r.dur }, Modifier.width(PROGRESS_WIDTH))
                    if (r.at == 0L && m.titleId in newShows) Text(NEW_EPISODE, style = NEW_STYLE, maxLines = 1)
                }
                ScrollText(i?.plot.orEmpty(), style = Type.prose, lines = 4, minLines = 4, modifier = Modifier.padding(top = Space.xs).widthIn(max = PROSE_WIDTH))
            }
        }
        val alone = entries.size == 1
        Row(
            Modifier
                .padding(start = EDGE, top = Space.l)
                // With one title there is no strip below: Down goes on to the rows, and Menu opens its actions, from here.
                .onKeyEvent {
                    if (!alone || it.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (it.nativeKeyEvent.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> onDown()
                        AndroidKeyEvent.KEYCODE_MENU -> onMenu(movie)
                        else -> return@onKeyEvent false
                    }
                    true
                },
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            // A next episode (no place saved) plays from its start.
            PageButton(if (shows) "${if (saved.at == 0L) "Play" else "Resume"} ${saved.label.orEmpty()}".trim() else "Resume", { onResume(movie) }, Modifier.focusRequester(resumeButton))
            if (shows) PageButton("Episodes", { onOpen(movie) }) else {
                PageButton("Start over", { onStartOver(movie) })
                PageButton("Details", { onOpen(movie) })
            }
        }
        if (alone) {
            Text("☰ More · ↓ All ${LocalLibrary.current.noun}", style = Type.caption, maxLines = 1, modifier = Modifier.padding(start = EDGE, top = Space.l))
            return@Column
        }
        Row(Modifier.padding(start = EDGE, top = Space.xl), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next", style = Type.headline, maxLines = 1)
            Text("☰ More · ↓ All ${LocalLibrary.current.noun}", style = Type.caption, maxLines = 1)
        }
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .onKeyEvent {
                    val code = it.nativeKeyEvent.keyCode
                    val down = it.type == KeyEventType.KeyDown
                    when (code) {
                        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (down) onDown()
                            true
                        }
                        // The focused title's actions (Remove from Up next among them).
                        AndroidKeyEvent.KEYCODE_MENU -> {
                            if (down && it.nativeKeyEvent.repeatCount == 0) onMenu(movie)
                            true
                        }
                        else -> false
                    }
                }
                .focusGroup(),
            contentPadding = PaddingValues(start = EDGE, top = Space.m, bottom = Space.s, end = EDGE),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            items(entries, key = { it.first.key }) { (m, r) ->
                WideCard(m, r, new = r.at == 0L && m.titleId in newShows, onFocused = { currentId = m.key }, onClick = { onOpen(m) })
            }
        }
    }
}

/**
 * The picture in a word, read by colour first: Best (green), High (bright), Medium (plain), Low (amber).
 * From resolution and codec-adjusted bits per pixel (server, api/_quality-labels.ts).
 */
@Composable
internal fun TierChip(tier: String) {
    val color = tierColor(tier)
    Text(
        tier,
        style = Type.caption.copy(color = color, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(Radius.chip)).padding(horizontal = Space.s, vertical = 1.dp),
    )
}


private fun tierColor(tier: String) = when (tier) {
    "Best" -> Palette.good
    "High" -> Palette.textPrimary
    "Low" -> Palette.warning
    else -> Palette.textSecondary
}

private val SCORE_STYLE = Type.body.copy(color = Palette.textPrimary, fontWeight = FontWeight.SemiBold)

/** A followed show's episode that aired after it was last watched (R31): state, so in the accent, like "Watched". */
private const val NEW_EPISODE = "New episode"
private val NEW_STYLE = Type.caption.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold)

/**
 * IMDb's and Letterboxd's averages (or TrueScore's) and the owner's rating ([scoresLine]), then year, running time and genres (the provider's rating when
 * no average is known); the group when nothing names genres.
 */
@Composable
private fun MetaLine(movie: Movie, info: MovieInfo?, extra: String? = null, modifier: Modifier = Modifier) {
    val genre = info?.genre?.split(',', '/')?.map(String::trim)?.filter(String::isNotEmpty)?.take(2)?.joinToString(", ")?.ifEmpty { null }
    val runtime = (info?.secs?.takeIf { it > 0 }?.let { it * 1000L } ?: movie.lib?.rt?.takeIf { it > 0 }?.let { it * 60_000L })?.let(::runtimeLabel)
    val scored: Scored? = movie.lib?.takeIf { it.known } ?: info
    val rating = info?.rating?.ifEmpty { null }?.takeIf { scored?.lb == null && scored?.ir == null }?.let { "$it/10" }
    val yours = LocalContext.current.graph.marks.ratings.collectAsState().value[movie.titleId]
    // Without TrueScore's own ratio, the title page's histogram share stands in (TrueScore's line, no recent trend).
    val net = info?.takeIf { it.hn != null && scored?.tr == null }?.let { it.copy(tr = it.hn, s = it.s ?: scored?.s, ir = it.ir ?: scored?.ir, iv = it.iv ?: scored?.iv, lb = it.lb ?: scored?.lb, lv = it.lv ?: scored?.lv) }
    val scores = remember(scored, yours, net) { scoresLine(net ?: scored, yours, onImdb = net?.hs == "i") }
    // The facts go under the scores when both don't fit on one line (beside a show's episodes); only then does a line scroll.
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.m), itemVerticalAlignment = Alignment.CenterVertically) {
        if (scores != null) Text(scores, style = SCORE_STYLE, maxLines = 1, inlineContent = SCORE_LOGOS)
        movie.title.badge?.let { Badges(remember(it) { listOf(it) }) }
        val group = movie.group.takeIf { movie.lib != null } ?: groupLabel(movie.group).name
        FitText(listOfNotNull(movie.title.year, extra, runtime, info?.language?.ifEmpty { null }, genre ?: group.ifEmpty { null }, rating).joinToString(" · "), style = Type.body, active = true)
    }
}

/**
 * "TrueScore 7,735 · ♥ 23% → 27% · [IMDb] 8.8 · [Letterboxd] 8.4 · You 9/10": TrueScore's score and its all-time then
 * recent net-loved share behind a heart (the score in the colour of the latest; "… on IMDb" [onImdb] when the share is IMDb histogram's and
 * IMDb's average isn't known to say so), then IMDb's and Letterboxd's averages out of 10 (Letterboxd's doubled) behind their
 * logos ([SCORE_LOGOS]), with vote counts when TrueScore's line isn't there to carry them, then the owner's rating. Each number
 * in its colour on the review-score ramp, the rest text-secondary; null when none is known.
 */
private fun scoresLine(scored: Scored?, yours: Int?, onImdb: Boolean = false): AnnotatedString? {
    val all = scored?.tr?.let(::pct)
    val averages = listOfNotNull(scored?.ir?.let { Triple(IMDB, it, scored.iv) }, scored?.lb?.let { Triple(LETTERBOXD, it * 2, scored.lv) })
    if (all == null && averages.isEmpty() && yours == null) return null
    return buildAnnotatedString {
        withStyle(SpanStyle(color = Palette.textSecondary)) {
            var first = true
            fun part() { if (!first) append(" · "); first = false }
            if (all != null) {
                part()
                val recent = scored.tq?.let(::pct)
                append("TrueScore ")
                withStyle(SpanStyle(color = Palette.share(recent ?: all))) { append("%,d".format(Locale.US, scored.ta ?: scored.s ?: 0)) }
                append(" · ♥ $all%")
                if (recent != null) append(" → $recent%")
                if (onImdb && scored.ir == null) append(" on IMDb")
            }
            for ((logo, value, votes) in averages) {
                part()
                appendInlineContent(logo, logo)
                append(" ")
                withStyle(SpanStyle(color = Palette.outOf10(value))) { append("%.1f".format(Locale.ROOT, value)) }
                if (votes != null && all == null) append(" (${compactCount(votes)})")
            }
            if (yours != null) {
                part()
                append("You ")
                withStyle(SpanStyle(color = Palette.outOf10(yours.toDouble()))) { append("$yours/10") }
            }
        }
    }
}

private const val IMDB = "IMDb"
private const val LETTERBOXD = "Letterboxd"

/** The scores line's IMDb badge (black on its yellow) and Letterboxd's three dots, in place of their names. */
private val SCORE_LOGOS = mapOf(
    IMDB to InlineTextContent(Placeholder(2.3.em, 1.1.em, PlaceholderVerticalAlign.Center)) {
        Box(Modifier.fillMaxSize().background(Color(0xFFF5C518), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
            Text("IMDb", style = Type.caption.copy(color = Color.Black, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.em))
        }
    },
    LETTERBOXD to InlineTextContent(Placeholder(1.45.em, 0.6.em, PlaceholderVerticalAlign.Center)) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.height / 2
            listOf(Color(0xFFFF8000), Color(0xFF00E054), Color(0xFF40BCF4)).forEachIndexed { i, c -> drawCircle(c, r, Offset(r + i * (size.width / 2 - r), r)) }
        }
    },
)

/** A 0–1 ratio as a whole percentage. */
private fun pct(ratio: Double) = Math.round(ratio * 100).toInt()

/** 2.9M, 41K, 830. */
private fun compactCount(n: Int) = when {
    n >= 999_500 -> "%.1fM".format(Locale.ROOT, n / 1e6).replace(".0M", "M")
    n >= 1_000 -> "${(n + 500) / 1000}K"
    else -> "$n"
}

private fun yoursLabel(rating: Int) = "You $rating/10"

/**
 * A card's corner: TrueScore's recent (else all-time) net-loved share for a film in its store, else the combined score out
 * of 10, in its colour ([scored]'s when a title page learnt it); null when neither is known.
 */
private fun cardChip(scored: Scored): AnnotatedString? {
    val share = (scored.tq ?: scored.tr)?.let(::pct)
    val score = scored.s10
    if (share == null && score == null) return null
    return buildAnnotatedString {
        // An arrow when the recent share is 10 points or more off the all-time one.
        val drift = if (share != null && scored.tq != null && scored.tr != null) share - pct(scored.tr!!) else 0
        if (share != null) withStyle(SpanStyle(color = Palette.share(share))) { append("$share%" + if (drift >= 10) " ↑" else if (drift <= -10) " ↓" else "") }
        else if (score != null) withStyle(SpanStyle(color = Palette.outOf10(score))) { append("%.1f".format(Locale.ROOT, score)) }
    }
}

/** Dark enough under the chip's 13 sp text to clear 4.5:1 over any poster. */
private val CHIP_GROUND = Color(0xE00B0E12)
/** Nested in the card's corner at a 4 dp inset: its radius is the card's less the inset. */
private val CHIP_SHAPE = RoundedCornerShape(Radius.row - Space.xs)
private val CHIP_TEXT = Type.caption.copy(color = Palette.textPrimary)

/** A [PageButton]'s height: a headline line and its padding. */
private val BUTTON_HEIGHT = 21.dp + Space.m * 2

/** A toggle on a title only Letterboxd's watchlist holds: there is no API to take it off. */

/** "Trailer" on a title page when TMDB names one ([key], a YouTube video): OK plays it in SmartTube, else YouTube; Back comes back here. */
@Composable
private fun TrailerButton(key: String?) {
    if (key.isNullOrEmpty()) return
    val context = LocalContext.current
    PageButton("Trailer", { playTrailer(context, key) }, Modifier.appear())
}

/** Ad-free SmartTube first, then Amazon's YouTube app, then whatever opens YouTube links. */
private val TRAILER_APPS = listOf("org.smarttube.stable", "com.amazon.firetv.youtube", null)

private fun playTrailer(context: Context, key: String) {
    val uri = Uri.parse("https://www.youtube.com/watch?v=$key")
    TRAILER_APPS.firstOrNull { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(it)) }.isSuccess }
}

/**
 * The audio languages a film's copies have, as flagged chips (🇬🇧 English, 🇪🇬 Egyptian, 🇸🇦 Arabic; only with two or more): the
 * chosen one (else the default: "Arabic dub first" or the original) is outlined; OK on another makes Play open its best copy on that
 * track, for this film from then on.
 */
@Composable
private fun AudioChips(title: TitleId, prepared: Prepared) {
    val graph = LocalContext.current.graph
    val copies = prepared.answer?.copies ?: return
    val langs = audioChoices(copies.copies, copies.originalLanguage)
    if (langs.isEmpty()) return
    val chosen = prepared.ranking?.language ?: copies.originalLanguage
    Row(Modifier.appear(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        for (l in langs) PageButton(listOfNotNull(FLAGS[l], audioName(l)).joinToString(" "), {
            graph.copyChoices.language(title, l)
            graph.films.rerank(title)
        }, selected = l == chosen, small = true)
    }
}

/** A language's flag, for the chips: the country most of its films come from. */
private val FLAGS = mapOf(
    "en" to "🇬🇧", EGYPTIAN to "🇪🇬", "ar" to "🇸🇦", "fr" to "🇫🇷", "es" to "🇪🇸", "de" to "🇩🇪", "it" to "🇮🇹", "pt" to "🇵🇹",
    "ja" to "🇯🇵", "ko" to "🇰🇷", "zh" to "🇨🇳", "hi" to "🇮🇳", "ru" to "🇷🇺", "tr" to "🇹🇷", "nl" to "🇳🇱", "da" to "🇩🇰", "sv" to "🇸🇪", "no" to "🇳🇴",
)

/**
 * A title page's or Up next's button: a quiet translucent pill that turns solid light when focused, so
 * the one OK will press reads from across the room. A [selected] one is outlined in the accent; a [small] one is a chip.
 */
@Composable
private fun PageButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, small: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val lift = animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "lift")
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, Motion.focus(), label = "press")
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
            }
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (small) Space.l else Space.xl, vertical = if (small) Space.s else Space.m),
    )
}

/**
 * A title part-way through as a 16:9 card: its backdrop (else its poster), name and where it is
 * ("S1 E3"), how far along. Focus lifts it with the accent ring, like a poster.
 */
@Composable
private fun WideCard(movie: Movie, saved: Resume, new: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val info = rememberInfo(movie)
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val lift = animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "lift")
    val image = backdropOf(movie, info)?.let { tmdbAt(it, "w780") } ?: movie.poster.ifEmpty { null }?.let(::posterUrl)
    val request = remember(image) {
        image?.let { with(density) { ImageRequest.Builder(context).data(it).size(WIDE.width.roundToPx(), WIDE.height.roundToPx()).crossfade(POSTER_FADE_MS).build() } }
    }
    Box(
        Modifier
            .size(WIDE)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(interaction, indication = null, onClick = onClick)
            .graphicsLayer {
                val s = if (reduced) 1f else 1f + (CARD_SCALE - 1f) * lift.value
                scaleX = s
                scaleY = s
                shape = CARD_SHAPE
                clip = true
            }
            .drawWithContent {
                drawRect(Palette.raised)
                drawContent()
                val p = lift.value.coerceIn(0f, 1f)
                if (p > 0f) {
                    val ring = RING.toPx()
                    drawRoundRect(
                        Palette.accent, topLeft = Offset(ring / 2, ring / 2), size = Size(size.width - ring, size.height - ring),
                        cornerRadius = CornerRadius(Radius.row.toPx() - ring / 2), style = Stroke(ring), alpha = p,
                    )
                }
            },
    ) {
        if (request != null) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        // The name and progress read over any picture.
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xE6000000))) })
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = Space.s, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (new) Text(NEW_EPISODE, style = NEW_STYLE, maxLines = 1)
            FitText(listOfNotNull(movie.title.title, saved.label).joinToString(" · "), style = Type.caption.copy(color = Palette.textPrimary), active = focused)
            if (saved.dur > 0) ProgressLine({ saved.pos.toFloat() / saved.dur })
        }
    }
}

/**
 * The focused title on the rows and search pages: name, meta, how much is left of a started one,
 * and two lines of plot. It cross-fades once focus rests, in a fixed box, so the rows below never move.
 */
@Composable
private fun Hero(movie: Movie?, info: MovieInfo?, resume: List<Resume>, placeholder: String, modifier: Modifier) {
    AnimatedContent(
        targetState = movie to info,
        contentKey = { it.first?.key to (it.second != null) },
        transitionSpec = { fadeIn(Motion.spring(0.3f)) togetherWith fadeOut(Motion.spring(0.2f)) using SizeTransform(clip = false) },
        modifier = modifier,
        label = "hero",
    ) { (m, i) ->
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (m == null) {
                Text(placeholder, style = Type.display, maxLines = 1)
                return@Column
            }
            FitText(m.title.title, style = Type.display, active = true)
            // A show also says which episode it is on: "S1 E3 · 40 min left".
            val left = remember(m, resume) {
                resume.firstOrNull { it.title == m.titleId }?.let { r ->
                    listOfNotNull(r.label, r.takeIf { it.dur > 0 && it.pos > 0 }?.let { leftLabel(it.dur - it.pos) }).joinToString(" · ").ifEmpty { null }
                }
            }
            MetaLine(m, i, extra = left)
            i?.plot?.ifBlank { null }?.let { ScrollText(it, style = Type.prose, lines = 2, modifier = Modifier.widthIn(max = PROSE_WIDTH)) }
        }
    }
}

/** "1 h 12 min left", "42 min left". */
private fun leftLabel(ms: Long): String = "${runtimeLabel(ms)} left"

/** "2 h 10 min", "42 min". */
private fun runtimeLabel(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
}

/** A row's scroll: the focused card keeps a card's width from either edge, so the next one always shows. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberKeepClear(): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) {
        val margin = with(density) { (CARD.width + Space.l).toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val before = offset - margin
                val after = offset + size + margin - containerSize
                return when {
                    before < 0 -> before
                    after > 0 -> after
                    else -> 0f
                }
            }
        }
    }
}

/**
 * Rows of posters. The focused row rises to the top of the list; within a row the focused card keeps a
 * card's width from either edge. Each row comes back to the card last focused in it, and the screen to
 * the card focused last ([focus] restores it). Up and Down move a row at a time on purpose, scrolling the
 * next row in first, so a row above the screen is never skipped for a button; Up from the first row is [onTop].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Rows(
    rows: List<MovieRow>, watched: Set<TitleId>, focus: FocusRequester, active: Boolean, onFocused: (Movie) -> Unit, onOpen: (Movie) -> Unit,
    onMenu: (Movie, continuing: Boolean) -> Unit, onTop: () -> Unit, onGenre: (String, GenreTile) -> Unit, returnTo: MutableState<GenreTile?>,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    // Back from a genre's titles (these rows were gone meanwhile): the tile it was opened from.
    val tile = remember { returnTo.value }
    LaunchedEffect(Unit) { returnTo.value = null }
    // The card each row was left on, for a row built again after scrolling far away.
    val columns = remember { HashMap<String, Int>().apply { tile?.let { put(GENRES_ROW, it.col) } } }
    val rowFocus = remember { HashMap<String, FocusRequester>() }
    var focusedRow by remember { mutableStateOf(-1) }
    val pivot = remember(density) {
        val top = with(density) { (CARD_TOP + FADE).toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - top
        }
    }
    val keepClear = rememberKeepClear()
    // The card a title page was opened from (row key, movie id). Back lands on it explicitly.
    val list = rememberLazyListState()
    val back = remember { FocusRequester() }
    var opened by remember { mutableStateOf(tile?.let { GENRES_ROW to it.id }) }
    val lastFocused = remember { arrayOfNulls<Pair<String, Int>>(1) }
    LaunchedEffect(active) {
        val target = opened?.takeIf { active } ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == target.first }
        if (index >= 0) {
            if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                if (lastFocused[0] == target) return@repeat
                runCatching { back.requestFocus() }
            }
        }
        opened = null
    }
    fun goRow(i: Int) {
        scope.launch {
            val requester = rowFocus[rows[i].key] ?: return@launch
            repeat(FOCUS_FRAMES) {
                if (list.layoutInfo.visibleItemsInfo.none { it.index == i }) list.scrollToItem(i)
                withFrameNanos {}
                if (focusedRow == i) return@launch
                runCatching { requester.requestFocus() }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides pivot) {
        LazyColumn(
            Modifier.fillMaxSize().edgeFade(Palette.ink, top = FADE).focusRequester(focus).focusRestorer(first).focusGroup(),
            state = list,
            contentPadding = PaddingValues(top = FADE, bottom = Space.xxxl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            itemsIndexed(rows, key = { _, row -> row.key }, contentType = { _, _ -> "row" }) { i, row ->
                CompositionLocalProvider(LocalBringIntoViewSpec provides keepClear) {
                    PosterRow(
                        row,
                        watched = watched,
                        rowFocus = rowFocus.getOrPut(row.key) { FocusRequester() },
                        entry = if (i == 0) first else null,
                        startAt = columns[row.key] ?: 0,
                        back = opened?.takeIf { it.first == row.key }?.let { it.second to back },
                        onFocused = { col, movie ->
                            columns[row.key] = col
                            focusedRow = i
                            lastFocused[0] = row.key to movie.id
                            onFocused(movie)
                        },
                        onOpen = { movie ->
                            opened = row.key to movie.id
                            val genre = row.genres?.get(movie.key)
                            if (genre != null) onGenre(genre, GenreTile(movie.id, columns[row.key] ?: 0)) else onOpen(movie)
                        },
                        onMenu = { movie -> onMenu(movie, row.key == CONTINUE_ROW) },
                        onVertical = { down ->
                            when {
                                !down && i == 0 -> onTop()
                                down && i == rows.lastIndex -> {}
                                else -> goRow(if (down) i + 1 else i - 1)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterRow(
    row: MovieRow, watched: Set<TitleId>, rowFocus: FocusRequester, entry: FocusRequester?, startAt: Int, back: Pair<Int, FocusRequester>?,
    onFocused: (Int, Movie) -> Unit, onOpen: (Movie) -> Unit, onVertical: (down: Boolean) -> Unit, onMenu: ((Movie) -> Unit)? = null,
) {
    val own = remember { FocusRequester() }
    val restore = entry ?: own
    val at = startAt.coerceIn(0, (row.movies.size - 1).coerceAtLeast(0))
    // Saveable: a row built again after scrolling far away is back where it was.
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (at - 1).coerceAtLeast(0))
    // A row's end is felt, not crossed: Left on the first card or Right on the last nudges the row and springs it back.
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val nudge = remember { Animatable(0f) }
    val focusedCol = remember { intArrayOf(at) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.padding(start = EDGE), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
            Text(row.title, style = Type.headline, maxLines = 1)
            row.badge?.let { Badges(remember(it) { listOf(it) }) }
        }
        LazyRow(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = nudge.value * Space.s.toPx() }
                .onKeyEvent {
                    val code = it.nativeKeyEvent.keyCode
                    // Up and Down belong to the rows (one row a step), never to focus search.
                    if (code == AndroidKeyEvent.KEYCODE_DPAD_UP || code == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                        if (it.type == KeyEventType.KeyDown) onVertical(code == AndroidKeyEvent.KEYCODE_DPAD_DOWN)
                        return@onKeyEvent true
                    }
                    val left = code == AndroidKeyEvent.KEYCODE_DPAD_LEFT
                    val edge = (left && focusedCol[0] == 0) || (code == AndroidKeyEvent.KEYCODE_DPAD_RIGHT && focusedCol[0] == row.movies.lastIndex)
                    if (edge && !reduced && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) scope.launch {
                        nudge.animateTo(if (left) -1f else 1f, Motion.spring(0.1f))
                        nudge.animateTo(0f, Motion.spring(0.3f))
                    }
                    edge
                }
                .focusRequester(rowFocus)
                // Asking for the opened card enters through here: falling back to the entry card would win.
                .focusRestorer(back?.second ?: restore)
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = EDGE, vertical = Space.m),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            itemsIndexed(row.movies, key = { _, m -> m.key }, contentType = { _, _ -> "card" }) { col, movie ->
                PosterCard(
                    movie, row.progress[movie.titleId], watched = movie.titleId in watched,
                    focus = if (col == at) restore else null,
                    back = back?.takeIf { it.first == movie.id }?.second,
                    onFocused = {
                        focusedCol[0] = col
                        onFocused(col, movie)
                    },
                    onClick = { onOpen(movie) },
                    onMenu = onMenu?.takeIf { row.genres == null }?.let { m -> { m(movie) } },
                    label = row.genres?.get(movie.key),
                )
            }
        }
    }
}

/**
 * A 2:3 poster at a fixed size, so nothing moves while it loads; the title's monogram when there is
 * none. Focus lifts it toward the viewer with an accent ring, on one spring read only while drawing.
 * With a [label] it is a Genres tile: the poster pictures the genre, named over it, and nothing else.
 */
@Composable
private fun PosterCard(movie: Movie, progress: Float?, watched: Boolean = false, focus: FocusRequester?, back: FocusRequester? = null, onFocused: () -> Unit, onClick: () -> Unit, onMenu: (() -> Unit)? = null, label: String? = null) {
    val reduced = LocalReducedMotion.current
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    val lift = animateFloatAsState(if (focused) 1f else 0f, Motion.focus(), label = "lift")
    var failed by remember(movie.poster) { mutableStateOf(false) }
    val request = remember(movie.poster) {
        movie.poster.takeIf { it.isNotEmpty() }?.let {
            with(density) { ImageRequest.Builder(context).data(posterUrl(it)).size(CARD.width.roundToPx(), CARD.height.roundToPx()).crossfade(POSTER_FADE_MS).build() }
        }
    }
    Box(
        Modifier
            .size(CARD)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .then(if (back != null) Modifier.focusRequester(back) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onKeyEvent {
                if (onMenu == null || remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Menu) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) onMenu()
                true
            }
            .clickable(interaction, indication = null, onClick = onClick)
            .graphicsLayer {
                val s = if (reduced) 1f else 1f + (CARD_SCALE - 1f) * lift.value
                scaleX = s
                scaleY = s
                shape = CARD_SHAPE
                clip = true
            }
            .drawWithContent {
                drawRect(Palette.raised)
                drawContent()
                val p = lift.value.coerceIn(0f, 1f)
                if (p > 0f) {
                    val ring = RING.toPx()
                    drawRoundRect(
                        Palette.accent, topLeft = Offset(ring / 2, ring / 2), size = Size(size.width - ring, size.height - ring),
                        cornerRadius = CornerRadius(Radius.row.toPx() - ring / 2), style = Stroke(ring), alpha = p,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (request == null || failed) {
            Text(remember(movie.name) { monogram(movie.title.title) }, style = Type.title.copy(color = Palette.textSecondary), maxLines = 1)
        }
        if (request != null) {
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, onError = { failed = true }, modifier = Modifier.fillMaxSize())
        }
        if (label != null) {
            // Dark enough at the foot that the name reads over a poster's own lettering.
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(0.25f to Color.Transparent, 0.6f to Color(0xB3000000), 1f to Color(0xF2000000))) })
            Text(label, style = Type.title, modifier = Modifier.align(Alignment.BottomStart).padding(Space.s))
            return@Box
        }
        if (progress != null) {
            ProgressLine({ progress }, Modifier.align(Alignment.BottomCenter).padding(horizontal = Space.s, vertical = Space.s))
        }
        if (watched) WatchedMark(Modifier.align(Alignment.TopEnd).padding(Space.xs))
        // How long it runs, where no progress line takes the bottom.
        val rt = movie.lib?.rt ?: 0
        if (rt > 0 && progress == null) Text(
            remember(rt) { if (rt >= 60) "${rt / 60}h %02d".format(Locale.ROOT, rt % 60).removeSuffix(" 00") else "${rt}m" }, style = CHIP_TEXT, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomEnd).padding(Space.xs).background(CHIP_GROUND, CHIP_SHAPE).padding(horizontal = Space.xs + 2.dp, vertical = 1.dp),
        )
        val learnt = movie.lib?.takeIf { !it.known }?.let { LocalContext.current.graph.titles.scores.collectAsState().value[movie.titleId] }
        val chip = remember(movie.lib, learnt) { movie.lib?.let { cardChip(learnt ?: it) } }
        if (chip != null) Text(
            chip, style = CHIP_TEXT, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(Space.xs).background(CHIP_GROUND, CHIP_SHAPE).padding(horizontal = Space.xs + 2.dp, vertical = 1.dp),
        )
    }
}

private fun monogram(title: String): String =
    title.split(' ').mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }.take(2).joinToString("") { it.uppercase() }

/**
 * Search: the app's keyboard over a grid of matching posters: titles from TMDB and the provider's whole
 * catalogue, or a genre's or language's ("comedy", "arabic"). Back leaves it. [focus] restores the result
 * last focused, else the keyboard ([keys]).
 */
@Composable
private fun SearchGrid(
    library: Catalog,
    query: String,
    onQuery: (String) -> Unit,
    focus: FocusRequester,
    keys: FocusRequester,
    rowNamed: (String) -> List<Movie>?,
    onFocused: (Movie?) -> Unit,
    back: Pair<Int, FocusRequester>?,
    stray: () -> Boolean,
    arrive: () -> Boolean,
    onPlay: (Movie) -> Unit,
    onMenu: (Movie) -> Unit,
) {
    var found by remember { mutableStateOf<List<Movie>?>(null) }
    val graph = LocalContext.current.graph
    val sync = graph.titles
    val recent by graph.recentSearches.all.collectAsState()
    // Runs once typing pauses (the box keeps each answer, and so does the app); the last results stay
    // meanwhile. Offline, the library's own titles.
    LaunchedEffect(query, library) {
        if (query.isBlank()) {
            found = null
            return@LaunchedEffect
        }
        rowNamed(query)?.let {
            found = it
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        found = sync.search(query).getOrNull() ?: withContext(Dispatchers.Default) { library.search(query) }
    }
    val first = remember { FocusRequester() }
    // A spoken query: once its results are in, the first card takes focus. None leaves focus on the keys.
    var spoken by remember { mutableStateOf(false) }
    LaunchedEffect(found) {
        if (!spoken) return@LaunchedEffect
        spoken = false
        if (found.isNullOrEmpty()) return@LaunchedEffect
        repeat(10) {
            withFrameNanos {}
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    LazyVerticalGrid(
        columns = GridCells.FixedSize(CARD.width),
        modifier = Modifier.fillMaxSize().focusRequester(focus).focusRestorer(keys).focusGroup(),
        contentPadding = PaddingValues(start = EDGE, end = EDGE, top = Space.m, bottom = Space.xxxl),
        horizontalArrangement = Arrangement.spacedBy(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.xl),
    ) {
        item(key = "keys", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.widthIn(max = KEYBOARD_WIDTH)) {
                SearchBox(
                    query, busy = false, onQuery = onQuery, entry = keys,
                    onFocused = { onFocused(null) },
                    onDown = { runCatching { first.requestFocus() } },
                    onSpoken = { spoken = true },
                    stray = stray,
                    arrive = arrive,
                )
            }
        }
        val list = found
        when {
            query.isBlank() && recent.isNotEmpty() -> item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                // Past searches that led to a title: OK searches again.
                Row(Modifier.focusGroup(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    recent.forEachIndexed { i, q -> ActionButton(q, { onQuery(q) }, if (i == 0) Modifier.focusRequester(first) else Modifier) }
                }
            }
            query.isBlank() -> item(key = "hint", span = { GridItemSpan(maxLineSpan) }) { HintLine("Type a title, or a genre or language such as comedy or Arabic") }
            list == null -> {}
            list.isEmpty() -> item(key = "none", span = { GridItemSpan(maxLineSpan) }) { HintLine("No ${LocalLibrary.current.noun} match “${query.trim()}”") }
            else -> itemsIndexed(list, key = { _, m -> m.key }) { i, movie ->
                PosterCard(movie, null, focus = if (i == 0) first else null, back = back?.takeIf { it.first == movie.id }?.second, onFocused = { onFocused(movie) }, onClick = { graph.recentSearches.add(query); onPlay(movie) }, onMenu = { onMenu(movie) })
            }
        }
    }
}

@Composable
private fun HintLine(text: String) {
    Text(text, style = Type.body, modifier = Modifier.padding(start = Space.m))
}

/** First ever open only: later ones read the copy on disk. Waits 400ms, so a quick load never flashes it. */
@Composable
private fun LoadingMovies(focus: FocusRequester) {
    // Holds focus meanwhile, so Back still answers.
    Waiting("Loading ${LocalLibrary.current.noun}…", Modifier.padding(start = EDGE, top = Space.xl).focusRequester(focus).focusable())
}

@Composable
private fun Unavailable(focus: FocusRequester, onRetry: () -> Unit) {
    Column(Modifier.padding(start = EDGE, top = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("${LocalLibrary.current.title} didn’t load", style = Type.title)
        Text("Check that the Fire TV is online, then try again.", style = Type.body)
        ActionButton("Try again", onRetry, Modifier.padding(top = Space.m).focusRequester(focus))
    }
}

/** Every source has answered and no copy plays here: the title stays, its page says so (R21). */
private val Prepared.unavailable get() = plays == null && debridChecked && ranking != null && answer?.debridPending == false

/**
 * Where focus goes back to on a title page after a page its cast or More like this opened: the item [opened]
 * ([back] is on it); [first] is the franchise's first card, else More like this's, else the first person: an unavailable title's landing.
 */
private class ExtrasFocus {
    val first = FocusRequester()
    val back = FocusRequester()
    var opened by mutableStateOf<String?>(null)
}

/**
 * A film's title page: its backdrop over the whole screen and, low-left, everything about it (meta and
 * scores, how much is left, plot), the copy Play will use, then Resume or Play, Start over once it is
 * started, and Versions; below, its cast and director and More like this. Opening it prepares the film's
 * copies (KTD13). A film nothing plays says so instead, focus on More like this (R21). Back returns to
 * where it was opened from, or first closes Versions. [hidden] while the film, or a page over it, is up.
 */
@Composable
private fun FilmPage(
    film: Movie, openVersions: Boolean, resume: Resume?, watched: Set<TitleId>, active: Boolean, hidden: Boolean,
    onPlay: (fromStart: Boolean) -> Unit, onOpen: (Movie) -> Unit, onPerson: (Person) -> Unit, onBack: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val info = rememberInfo(film, fresh = true)
    val prepared by remember(film.titleId) { graph.films.state(film.titleId) }.collectAsState()
    val unavailable = prepared.unavailable
    val primary = remember { FocusRequester() }
    val versionsButton = remember { FocusRequester() }
    val noCopy = remember { FocusRequester() }
    val extras = remember { ExtrasFocus() }
    val hasExtras = info != null && (info.people.isNotEmpty() || info.more.isNotEmpty() || info.parts.isNotEmpty())
    var hasFocus by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf(openVersions) }
    val closedVersions = remember { booleanArrayOf(false) }
    // ☰'s marks panel.
    var marking by remember { mutableStateOf(false) }
    // The Plays line and the buttons come a frame after the rest (their slots held), so the opening frame stays under budget.
    var buttons by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        buttons = true
        Check.log("title_shown", "title" to film.titleId)
        graph.prefs.put(Check.LAST_SCREEN, "title:${film.titleId}")
    }
    LaunchedEffect(active, versions || marking, unavailable, hasExtras) {
        if (!active || versions || marking) return@LaunchedEffect
        val target = when {
            closedVersions[0] -> versionsButton
            extras.opened != null -> extras.back
            unavailable -> if (hasExtras) extras.first else noCopy
            else -> primary
        }
        closedVersions[0] = false
        try {
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                if (hasFocus) return@LaunchedEffect
                runCatching { target.requestFocus() }
            }
        } finally {
            extras.opened = null
        }
    }
    val started = resume != null && resume.pos > 0
    // The provider's own copy, named on the Plays line when the box can't answer.
    val iptv = film.id > 0 || graph.titles.movies.items.value?.findTitle(film.titleId) != null
    val onMenu = if (!versions && !marking && graph.marks.canMark(film.titleId)) ({ marking = true }) else null
    TitleFrame(backdropOf(film, info), hidden, onBack, onFocus = { hasFocus = it }, onMenu = onMenu) {
        TitleScroll(
            main = {
                Column(Modifier.padding(start = EDGE, bottom = Space.xl).widthIn(max = PAGE_WIDTH), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    PageTitle(film.title.title, info, Modifier.glide())
                    // The tier is the copy's that plays, on the Plays line.
                    MetaLine(film, info, modifier = Modifier.glide())
                    if (started && resume!!.dur > 0) Row(Modifier.glide(), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                        Text(leftLabel(resume.dur - resume.pos), style = Type.headline, maxLines = 1)
                        ProgressLine({ resume.pos.toFloat() / resume.dur }, Modifier.width(PROGRESS_WIDTH))
                    }
                    info?.plot?.ifBlank { null }?.let { ScrollText(it, style = Type.prose, lines = if (unavailable) 8 else 10, modifier = Modifier.widthIn(max = PROSE_WIDTH).appear()) }
                    // The provider's facts name people only as text; TMDB's are the row below.
                    if (info?.people.isNullOrEmpty()) {
                        info?.cast?.ifBlank { null }?.let { Text("Starring $it", style = Type.caption, maxLines = 2, modifier = Modifier.appear()) }
                        info?.director?.substringBefore(",")?.trim()?.ifBlank { null }?.let { Text("Directed by $it", style = Type.caption, maxLines = 2, modifier = Modifier.appear()) }
                    }
                    if (unavailable) {
                        Text("No copy available", style = Type.headline.copy(color = Palette.warning), modifier = Modifier.appear().padding(top = Space.s).focusRequester(noCopy).focusable())
                        Text("Neither IPTV nor ${debridName()} has one that plays here. It stays in the library for when one turns up.", style = Type.caption, modifier = Modifier.appear())
                    } else {
                        // The Plays line may run past the column, across the page's whole width.
                        if (!buttons) Box(Modifier.padding(top = Space.s).height(PLAYS_HEIGHT)) else PlaysLine(
                            film.titleId, fallback = if (iptv) listOf("IPTV") + info?.quality.orEmpty() else null,
                            Modifier.padding(top = Space.s).wrapContentWidth(Alignment.Start, unbounded = true).widthIn(max = LocalConfiguration.current.screenWidthDp.dp - EDGE * 2),
                        )
                        if (!buttons) Box(Modifier.height(BUTTON_HEIGHT)) else Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            PageButton(if (started) "Resume" else "Play", { onPlay(false) }, Modifier.focusRequester(primary))
                            if (started) PageButton("Start over", { onPlay(true) })
                            PageButton("Versions", { versions = true }, Modifier.focusRequester(versionsButton))
                            TrailerButton(info?.trailer)
                        }
                        if (buttons) AudioChips(film.titleId, prepared)
                    }
                }
            },
            extras = { TitleExtras(info, watched, extras, onPerson, onOpen, it) },
        )
        SlidePanel(Unit.takeIf { versions }) { _, open, m ->
            VersionsPanel(
                film.titleId,
                onPlay = { onPlay(false) },
                onClose = {
                    closedVersions[0] = true
                    versions = false
                },
                modifier = m,
                open = open,
            )
        }
        SlidePanel(Unit.takeIf { marking }) { _, open, m -> TitleActions(film, onClose = { marking = false }, modifier = m, open = open) }
    }
}

/**
 * [4K] [HDR10] [E-AC3 5.1] ([formatLabels]): the copy Play will open, in a
 * one-line slot that is there from the start, so nothing moves when it fills in or changes (R29). Until
 * the ranking and Real-Debrid's check are in, it stays empty; after Play's own wait it names the best
 * IPTV copy, and the library's ([fallback] labels) when the box can't answer.
 */
@Composable
private fun PlaysLine(title: TitleId, fallback: List<String>?, modifier: Modifier = Modifier) {
    val films = LocalContext.current.graph.films
    val service = debridName()
    val p by remember(title) { films.state(title) }.collectAsState()
    var late by remember { mutableStateOf(false) }
    LaunchedEffect(title) {
        delay(FilmSession.WAIT_MS)
        late = true
    }
    val copy = p.plays ?: p.takeIf { late }?.pickWithoutWaiting()?.copy
    val text = when {
        copy != null -> copyLine(copy, p.answer, service)
        p.failed || (late && p.ranking == null) -> fallback?.joinToString(" · ")
        p.debridChecked && p.ranking != null -> "no copy plays here right now"
        else -> null
    }
    // Shown as format chips; the source, bitrate and size stay in Versions.
    val chips = copy?.let(::formatLabels) ?: fallback?.takeIf { p.failed || (late && p.ranking == null) }
    LaunchedEffect(text) { if (text != null) Check.log("plays", "title" to title, "copy" to copy?.id, "src" to (copy?.source ?: "library")) }
    Row(modifier.height(PLAYS_HEIGHT), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
        if (text == null) return@Row
        Row(Modifier.appear(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (chips != null) FormatChips(chips) else FitText(text, style = Type.body, active = true)
        }
    }
}

private val PLAYS_HEIGHT = 24.dp

/**
 * A panel at the right edge that slides in from the right and back out to it, as Options and Recent do on the
 * live screen. A non-null [value] shows it; while it leaves, [content] keeps the last value. [content] gets
 * whether the panel is still open (passed on, so focus can leave a closing panel) and the modifier for the panel.
 */
@Composable
private fun <T : Any> BoxScope.SlidePanel(value: T?, content: @Composable (value: T, open: Boolean, modifier: Modifier) -> Unit) {
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
        modifier = Modifier.align(Alignment.CenterEnd).padding(Space.safe).onFocusChanged { holdsFocus[0] = it.hasFocus },
    ) {
        val shown = reveal()
        (value ?: last)?.let { content(it, value != null, Modifier.slideFade({ shown.value }, Edge.Right, reduced)) }
    }
}

/** A title page's name in display type, its original-language title under it when TMDB has one that differs. */
@Composable
private fun PageTitle(title: String, info: MovieInfo?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        ScrollText(title, style = BIG_TITLE, lines = 2)
        info?.originalTitle?.ifBlank { null }?.let { FitText(it, style = ORIGINAL_TITLE, active = true, modifier = Modifier.appear()) }
    }
}

private val ORIGINAL_TITLE = Type.headline.copy(color = Palette.textSecondary)

/** TMDB's show status in the app's words. */
private val SHOW_STATUS = mapOf(
    "Returning Series" to "Returning", "Ended" to "Ended", "Canceled" to "Cancelled",
    "In Production" to "In production", "Planned" to "Planned", "Pilot" to "Pilot",
)

/**
 * "AMC · Ended", "Apple TV · Returning", "HBO · Season 3 starts Sun 14 Dec", "Netflix · S2 E5 airs tomorrow": a show's network and
 * where it stands, its next episode in place of "Returning" once dated (a date gone by falls back to the status); null when TMDB knows neither.
 */
private fun showStatus(info: MovieInfo): String? {
    val today = LocalDate.now()
    val next = info.next?.let { n ->
        val day = runCatching { LocalDate.parse(n.air) }.getOrNull()?.takeIf { !it.isBefore(today) } ?: return@let null
        val on = when {
            day == today -> "today"
            day == today.plusDays(1) -> "tomorrow"
            day.year == today.year || day.isBefore(today.plusMonths(6)) -> day.format(DAY_MONTH)
            else -> day.format(DAY_MONTH_YEAR)
        }
        if (n.episode == 1) "Season ${n.season} starts $on" else "S${n.season} E${n.episode} airs $on"
    }
    return listOfNotNull(info.network.ifEmpty { null }, next ?: SHOW_STATUS[info.status]).joinToString(" · ").ifEmpty { null }
}

private val DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

/** A title page's ground: the backdrop, Back to leave, focus kept inside; undrawn while [hidden]. */
@Composable
private fun TitleFrame(backdrop: String?, hidden: Boolean, onBack: () -> Unit, onFocus: (Boolean) -> Unit, onMenu: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(ARRIVAL_MS)
        arrived = true
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .onFocusChanged { onFocus(it.hasFocus) }
            .onKeyEvent {
                val key = remoteKey(it.nativeKeyEvent.keyCode)
                // ☰ that nothing on the page took (an episode row, a card) opens the title's marks.
                if (key == RemoteKey.Menu && onMenu != null) {
                    if (it.type == KeyEventType.KeyDown) onMenu()
                    return@onKeyEvent true
                }
                if (key != RemoteKey.Back) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown) onBack()
                true // the key-up too, so Back never reaches the screen behind
            }
            .focusGroup(),
    ) {
        CompositionLocalProvider(LocalStill provides hidden, LocalArrived provides arrived) {
            Backdrop(backdrop, solidFrom = null)
            content()
        }
    }
}

/** Whether the page has been up a moment: what comes after that has loaded, and [appear]s. */
private val LocalArrived = compositionLocalOf { false }

/** Past this after a page opens, what shows has loaded: it fades in rather than popping. */
private const val ARRIVAL_MS = 150L

/** How long arriving content waits, unseen, while what it pushes glides out of its way. */
private const val MAKE_ROOM_MS = 200L

/** Fades in what arrives once its page is up (TMDB's details, the episodes, a copy); what's there from the start just shows. */
@Composable
private fun Modifier.appear(): Modifier {
    val reduced = LocalReducedMotion.current
    val late = LocalArrived.current
    // Decided once: what was there from the start costs nothing (no layer, no effect).
    val alpha = remember { if (late) Animatable(0f) else null } ?: return this
    LaunchedEffect(alpha) {
        // The lines it pushes up [glide] out of its way first, so the two never overlap.
        if (!reduced) delay(MAKE_ROOM_MS)
        alpha.animateTo(1f, if (reduced) tween(Motion.FADE_MS) else Motion.spring(0.3f))
    }
    return graphicsLayer {
        this.alpha = alpha.value
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** A wait that says what for: nothing for 400 ms, so a quick load never flashes it, then the words over the activity line, faded in. */
@Composable
private fun Waiting(text: String, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(400)
        shown = true
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, Motion.panel(), label = "waiting")
    Column(modifier.graphicsLayer { this.alpha = alpha }, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Text(text, style = Type.body, maxLines = 1)
        ActivityLine()
    }
}

/** How much of the cast row's heading shows under a title page's first screen, so Down has somewhere to go. */
private val PEEK = 40.dp

@OptIn(ExperimentalFoundationApi::class)
private val NO_SCROLL = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/**
 * A title page's two screens: [main] fills the first, bottom-aligned; [extras] (franchise, cast, More like this) wait
 * below it. Focus going down into a section of them ([extras]' scrollTo, with the section's top) scrolls it to the top
 * of the screen, or as far as the page goes, and back up to [main] scrolls back; nothing else scrolls the page, so a
 * row moving its cards never drags it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TitleScroll(main: @Composable BoxScope.() -> Unit, extras: @Composable (scrollTo: (top: Float) -> Unit) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val inherited = LocalBringIntoViewSpec.current
    val keepClear = rememberKeepClear()
    // The extras come two frames after the page (a film page's buttons take the second), so its opening frames lay out only what shows.
    var below by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos {} }
        below = true
    }
    // The page fills the screen: its height is known without a subcomposition pass on the opening frame.
    val screen = LocalConfiguration.current.screenHeightDp.dp
    val density = LocalDensity.current
    val from = with(density) { (screen - PEEK - Space.safeY).toPx() }
    val scrollTo = remember(from) { { top: Float -> scope.launch { scroll.animateScrollTo((from + top).toInt().coerceIn(0, scroll.maxValue)) }; Unit } }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides NO_SCROLL) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                CompositionLocalProvider(LocalBringIntoViewSpec provides inherited) {
                    Box(
                        Modifier.fillMaxWidth().height(screen - PEEK).onFocusChanged { if (it.hasFocus) scope.launch { scroll.animateScrollTo(0) } },
                        contentAlignment = Alignment.BottomStart,
                        content = main,
                    )
                }
                if (below) CompositionLocalProvider(LocalBringIntoViewSpec provides keepClear) { extras(scrollTo) }
            }
        }
    }
}

/**
 * A film's franchise (its other films, named as TMDB names the collection), the title's cast and director (OK opens
 * their titles) and More like this, from its TMDB details (R32); nothing when TMDB has none. [focus] marks the item
 * opened, so Back lands on it; each section scrolls itself into view ([scrollTo]) as focus enters it.
 */
@Composable
private fun TitleExtras(info: MovieInfo?, watched: Set<TitleId>, focus: ExtrasFocus, onPerson: (Person) -> Unit, onOpen: (Movie) -> Unit, scrollTo: (Float) -> Unit) {
    val parts = info?.partMovies.orEmpty()
    val people = info?.people.orEmpty()
    val more = info?.moreMovies.orEmpty()
    if (parts.isEmpty() && people.isEmpty() && more.isEmpty()) return
    val focusManager = LocalFocusManager.current
    // Two items a frame, in page order: a row's visible cards built in one frame took 10-21 ms on the stick, on top of the page's.
    var built by remember { mutableIntStateOf(0) }
    val total = parts.size + people.size + more.size
    LaunchedEffect(total) {
        while (built < total) {
            withFrameNanos {}
            built += 2
        }
    }
    val franchise = parts.take(built)
    val cast = people.take((built - parts.size).coerceAtLeast(0))
    val posters = more.take((built - parts.size - people.size).coerceAtLeast(0))
    Column(Modifier.padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (franchise.isNotEmpty()) ExtrasSection(scrollTo) {
            val opened = focus.opened?.takeIf { it.startsWith("c:") }?.removePrefix("c:")
            PosterRow(
                remember(parts, franchise.size) { MovieRow("parts", info!!.collection, null, franchise, emptyMap()) },
                watched = watched,
                rowFocus = remember { FocusRequester() },
                entry = focus.first,
                startAt = 0,
                back = parts.firstOrNull { it.key == opened }?.let { it.id to focus.back },
                onFocused = { _, _ -> },
                onOpen = {
                    focus.opened = "c:${it.key}"
                    onOpen(it)
                },
                onVertical = { down -> focusManager.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up) },
            )
        }
        if (people.isNotEmpty()) ExtrasSection(scrollTo) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text("Cast and crew", style = Type.headline, maxLines = 1, modifier = Modifier.padding(start = EDGE))
                LazyRow(
                    Modifier.fillMaxWidth().focusRestorer().focusGroup(),
                    contentPadding = PaddingValues(horizontal = EDGE, vertical = Space.m),
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    itemsIndexed(cast, key = { _, p -> p.id }) { i, p ->
                        val first = i == 0 && more.isEmpty() && parts.isEmpty()
                        val back = focus.opened == "p:${p.id}"
                        PageButton(
                            if (p.role == "Director" || p.role == "Creator") "${p.name} · ${p.role.lowercase()}" else p.name,
                            {
                                focus.opened = "p:${p.id}"
                                onPerson(p)
                            },
                            Modifier.then(if (first) Modifier.focusRequester(focus.first) else Modifier).then(if (back) Modifier.focusRequester(focus.back) else Modifier),
                        )
                    }
                }
            }
        }
        if (posters.isNotEmpty()) ExtrasSection(scrollTo) {
            val opened = focus.opened?.removePrefix("m:")
            PosterRow(
                remember(more, posters.size) { MovieRow("more", "More like this", null, posters, emptyMap()) },
                watched = watched,
                rowFocus = remember { FocusRequester() },
                entry = focus.first.takeIf { parts.isEmpty() },
                startAt = 0,
                back = more.firstOrNull { it.key == opened }?.let { it.id to focus.back },
                onFocused = { _, _ -> },
                onOpen = {
                    focus.opened = "m:${it.key}"
                    onOpen(it)
                },
                onVertical = { down -> if (!down) focusManager.moveFocus(FocusDirection.Up) },
            )
        }
    }
}

/** One of a title page's extras, which scrolls its top to the top of the screen when focus enters it ([scrollTo]). */
@Composable
private fun ExtrasSection(scrollTo: (Float) -> Unit, content: @Composable () -> Unit) {
    val top = remember { floatArrayOf(0f) }
    Box(Modifier.onPlaced { top[0] = it.positionInParent().y }.onFocusChanged { if (it.hasFocus) scrollTo(top[0]) }) { content() }
}

/**
 * A person's films and shows as a poster grid under their name, best score first (R32). Focus starts on the
 * first, and comes back to the one opened. Back returns to the title page.
 */
@Composable
private fun PersonPage(person: Person, watched: Set<TitleId>, active: Boolean, hidden: Boolean, onOpen: (Movie) -> Unit, onBack: () -> Unit) {
    val sync = LocalContext.current.graph.titles
    var titles by remember { mutableStateOf(sync.peekPerson(person.id)) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        sync.person(person.id).onSuccess { titles = it }.onFailure { failed = titles == null }
    }
    val first = remember { FocusRequester() }
    val back = remember { FocusRequester() }
    val status = remember { FocusRequester() }
    var opened by remember { mutableStateOf<String?>(null) }
    var hasFocus by remember { mutableStateOf(false) }
    val list = titles
    // The cards come a frame after the page, as a title page's extras do, so its opening frame stays light.
    var cards by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        cards = true
    }
    LaunchedEffect(active, list != null, failed) {
        if (!active) return@LaunchedEffect
        val target = when {
            list.isNullOrEmpty() -> status
            opened != null -> back
            else -> first
        }
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            if (hasFocus) return@LaunchedEffect
            runCatching { target.requestFocus() }
        }
    }
    TitleFrame(null, hidden, onBack, onFocus = { hasFocus = it }) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(CARD.width),
            modifier = Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(start = EDGE, end = EDGE, top = Space.safeY, bottom = Space.xxxl),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.xl),
        ) {
            item(key = "name", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(person.name, style = BIG_TITLE, maxLines = 2)
                    // Something holds focus while there are no cards, so Back still answers.
                    if (list == null && !failed) Waiting("Loading their films and shows…", Modifier.focusRequester(status).focusable())
                    else Text(
                        when {
                            !list.isNullOrEmpty() -> "${list.size} films and shows, best rated first"
                            failed -> "Their films and shows didn’t load. Check that the Fire TV is online."
                            else -> "No films or shows"
                        },
                        style = Type.body, maxLines = 1,
                        modifier = (if (list.isNullOrEmpty()) Modifier.focusRequester(status).focusable() else Modifier).appear(),
                    )
                    if (failed) ActionButton("Try again", { attempt++ }, Modifier.padding(top = Space.s))
                }
            }
            if (cards && !list.isNullOrEmpty()) itemsIndexed(list, key = { _, m -> m.key }, contentType = { _, _ -> "card" }) { i, m ->
                PosterCard(
                    m, null, watched = m.titleId in watched,
                    focus = if (i == 0) first else null,
                    back = if (m.key == opened) back else null,
                    onFocused = {},
                    onClick = {
                        opened = m.key
                        onOpen(m)
                    },
                )
            }
        }
    }
}

/** A row of a show's episode list: a season's heading, or an episode (its index in the show's list). */
private sealed interface EpisodeLine {
    data class Season(val season: Int) : EpisodeLine
    data class Item(val index: Int, val episode: Episode) : EpisodeLine
}

/**
 * A show's title page: its backdrop, then on the left everything about it and Resume (or Play) on the
 * episode up next; on the right its episodes by season, each with its still, length and progress.
 * Right enters the list on the episode up next; OK on any plays it from its place. Below, its cast and
 * More like this. The episodes are the provider's when it lists the show, else TMDB's (Real-Debrid plays
 * those). Back returns to where it was opened. [hidden] while an episode, or a page over it, is up.
 * [watched] is the films watched (for More like this); episodes keep their own marks.
 */
@Composable
private fun ShowPage(
    show: Movie, resume: Resume?, watched: Set<TitleId>, active: Boolean, hidden: Boolean,
    onPlay: (List<Episode>, Int, fromStart: Boolean) -> Unit, onOpen: (Movie) -> Unit, onPerson: (Person) -> Unit, onBack: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val sync = graph.titles
    // The stick's marks with the box's (U16).
    val seen = watched
    val ratings by graph.marks.ratings.collectAsState()
    val info = rememberInfo(show, fresh = true)
    val extras = remember { ExtrasFocus() }
    var data by remember { mutableStateOf(sync.peekShowOf(show)) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos {}
        Check.log("title_shown", "title" to show.titleId)
        graph.prefs.put(Check.LAST_SCREEN, "title:${show.titleId}")
    }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        // Fresh on opening, so a new episode shows; the kept copy (if any) is on screen meanwhile.
        sync.showOf(show, fresh = true).onSuccess { data = it }.onFailure { if (data == null) failed = true }
    }
    val episodes = data?.episodes
    val lines = remember(episodes) {
        buildList {
            episodes?.forEachIndexed { i, e ->
                if (i == 0 || episodes[i - 1].season != e.season) add(EpisodeLine.Season(e.season))
                add(EpisodeLine.Item(i, e))
            }
        }
    }
    var lastPlayed by remember { mutableStateOf<Int?>(null) }
    val target = episodes?.takeIf { it.isNotEmpty() }?.let { lastPlayed ?: upNextIndex(it, resume, seen) }
    val primary = remember { FocusRequester() }
    val targetRow = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val list = rememberLazyListState()
    var hasFocus by remember { mutableStateOf(false) }
    // The episode whose Versions are open, and whose marks are (Menu on its row; -1 the show's, Menu elsewhere).
    var versionsFor by remember { mutableStateOf<Int?>(null) }
    var marksFor by remember { mutableStateOf<Int?>(null) }
    val panel = versionsFor != null || marksFor != null
    // Where focus goes once the panel closes: the list (back on its row) or the page's first button.
    val backTo = remember { arrayOfNulls<FocusRequester>(1) }
    LaunchedEffect(panel) {
        val back = backTo[0]
        if (panel || back == null || !active) return@LaunchedEffect
        backTo[0] = null
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            if (hasFocus) return@LaunchedEffect
            runCatching { back.requestFocus() }
        }
    }
    LaunchedEffect(active, target != null, failed) {
        if (!active) return@LaunchedEffect
        if (target != null && extras.opened == null) list.scrollToItem((lines.indexOfFirst { it is EpisodeLine.Item && it.index == target } - 1).coerceAtLeast(0))
        try {
            repeat(FOCUS_FRAMES) {
                withFrameNanos {}
                if (hasFocus) return@LaunchedEffect
                runCatching { (if (extras.opened != null) extras.back else primary).requestFocus() }
            }
        } finally {
            extras.opened = null
        }
    }
    val onMenu = if (!panel && episodes != null && graph.marks.canMark(show.titleId)) ({ backTo[0] = primary; marksFor = -1 }) else null
    TitleFrame(backdropOf(show, info) ?: data?.backdrop, hidden, onBack, onFocus = { hasFocus = it }, onMenu = onMenu) {
        TitleScroll(
            main = {
                Row(Modifier.fillMaxSize().padding(start = EDGE, end = EDGE), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                    Column(Modifier.width(SHOW_INFO_WIDTH).fillMaxHeight().padding(bottom = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.Bottom)) {
                        PageTitle(show.title.title, info, Modifier.glide())
                        val seasons = episodes?.map { it.season }?.distinct()?.size
                        MetaLine(show, info ?: data?.info, extra = seasons?.let { if (it == 1) "1 season" else "$it seasons" }, modifier = Modifier.glide())
                        remember(info) { info?.let(::showStatus) }?.let { FitText(it, style = Type.body, active = true, modifier = Modifier.glide().appear()) }
                        (info?.plot?.ifBlank { null } ?: data?.plot?.ifBlank { null })?.let { ScrollText(it, style = Type.prose, lines = 10, modifier = Modifier.widthIn(max = PROSE_WIDTH).glide().appear()) }
                        if (info?.people.isNullOrEmpty()) data?.cast?.ifBlank { null }?.let { Text("Starring $it", style = Type.caption, maxLines = 2, modifier = Modifier.glide().appear()) }
                        val next = target?.let { episodes[it] }
                        // The copy the episode up next plays on, prepared as the page opens; TMDB's episodes have no IPTV copy of their own.
                        if (next != null) key(next.titleId) { PlaysLine(next.titleId, fallback = if (next.id > 0) listOf("IPTV") + next.quality else null, Modifier.glide()) }
                        val started = next != null && resume?.episode == next.titleId && resume.pos > 0
                        // Buttons that don't fit beside the episode list go on a second line, never cut.
                        FlowRow(Modifier.glide().padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            when {
                                next != null -> {
                                    PageButton("${if (started) "Resume" else "Play"} ${next.code}", { onPlay(episodes, target, false) }, Modifier.focusRequester(primary).appear())
                                    if (started) PageButton("Start over", { onPlay(episodes, target, true) }, Modifier.appear())
                                    TrailerButton(info?.trailer)
                                }
                                failed -> PageButton("Try again", { attempt++ }, Modifier.focusRequester(primary).appear())
                                // Something holds focus meanwhile, so Back still answers.
                                episodes == null -> Waiting("Loading episodes…", Modifier.focusRequester(primary).focusable())
                                else -> Text("No episodes yet", style = Type.body, modifier = Modifier.focusRequester(primary).focusable().appear())
                            }
                        }
                        if (failed) Text("Episodes didn’t load. Check that the Fire TV is online.", style = Type.caption, modifier = Modifier.appear())
                    }
                    if (!episodes.isNullOrEmpty()) LazyColumn(
                        // On the app's floating material, so the list reads over any backdrop.
                        Modifier.weight(1f).appear().padding(vertical = Space.safeY).material().edgeFade(Palette.material, top = Space.m, bottom = Space.xl)
                            .focusRequester(listFocus).focusRestorer(targetRow).focusGroup(),
                        state = list,
                        contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.m),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        items(lines, key = { if (it is EpisodeLine.Item) it.episode.id else "s${(it as EpisodeLine.Season).season}" }) { line ->
                            when (line) {
                                is EpisodeLine.Season -> Text("Season ${line.season}", style = Type.headline, modifier = Modifier.padding(start = Space.m, top = Space.l, bottom = Space.xs))
                                is EpisodeLine.Item -> EpisodeRow(
                                    line.episode,
                                    watched = line.episode.titleId in seen,
                                    rating = ratings[line.episode.titleId],
                                    progress = resume?.takeIf { it.episode == line.episode.titleId && it.dur > 0 && it.pos > 0 }?.let { it.pos.toFloat() / it.dur },
                                    focus = if (line.index == target) targetRow else null,
                                    onClick = {
                                        lastPlayed = line.index
                                        onPlay(episodes, line.index, false)
                                    },
                                    onMenu = {
                                        backTo[0] = listFocus
                                        if (graph.marks.canMark(line.episode.titleId)) marksFor = line.index else versionsFor = line.index
                                    },
                                )
                            }
                        }
                    }
                }
            },
            extras = { TitleExtras(info, watched, extras, onPerson, onOpen, it) },
        )
        SlidePanel(versionsFor) { i, open, m ->
            VersionsPanel(
                episodes!![i].titleId,
                onPlay = {
                    lastPlayed = i
                    onPlay(episodes, i, false)
                },
                onClose = { versionsFor = null },
                modifier = m,
                open = open,
            )
        }
        SlidePanel(marksFor) { i, open, at ->
            val close = { marksFor = null }
            if (i < 0) {
                val all = episodes.orEmpty().filter { it.season > 0 && it.titleId.isNotEmpty() }
                val season = target?.let { episodes!![it].season }?.takeIf { s -> s > 0 && all.any { it.season != s } }
                MarksPanel(
                    show.title.title,
                    listOfNotNull(
                        if (graph.watchlist.canHold(show.titleId) && !(show.titleId in graph.watchlist.letterboxd.value && show.titleId in graph.watchlist.ids.value))
                            (if (show.titleId in graph.watchlist.ids.value) "Remove from Watchlist" else "Add to Watchlist") to { graph.watchlist.toggle(show.titleId); close() } else null,
                        season?.let { s -> "Mark season $s watched" to { graph.marks.setWatched("${show.titleId}:s$s", true, all.filter { it.season == s }.map { it.titleId }); close() } },
                        "Mark show watched" to { graph.marks.setWatched(show.titleId, true, all.map { it.titleId }); close() },
                    ),
                    ratings[show.titleId], onRate = { graph.marks.rate(show.titleId, it); close() }, onClose = close, modifier = at, open = open,
                )
            } else {
                val e = episodes!![i]
                val done = e.titleId in seen
                MarksPanel(
                    listOfNotNull(e.code, e.title.takeUnless { it.isEmpty() || GENERIC_EPISODE.matches(it) }).joinToString(" · "),
                    listOf(
                        (if (done) "Mark unwatched" else "Mark watched") to { graph.marks.setWatched(e.titleId, !done); close() },
                        "Versions" to { marksFor = null; versionsFor = i },
                    ),
                    ratings[e.titleId], onRate = { graph.marks.rate(e.titleId, it); close() }, onClose = close, modifier = at, open = open,
                )
            }
        }
    }
}

/**
 * An episode: its still (else its number), title, length, how far it was watched, a check once seen;
 * the owner's rating; focused, the copy it plays on (prepared once focus rests there), and Menu opens its marks and Versions.
 */
@Composable
private fun EpisodeRow(episode: Episode, watched: Boolean, rating: Int?, progress: Float?, focus: FocusRequester?, onClick: () -> Unit, onMenu: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val request = remember(episode.image) {
        episode.image.ifEmpty { null }?.let { with(density) { ImageRequest.Builder(context).data(tmdbAt(it, "w300")).size(STILL.width.roundToPx(), STILL.height.roundToPx()).crossfade(POSTER_FADE_MS).build() } }
    }
    // "Episode 4" says nothing a number doesn't.
    val title = episode.title.takeUnless { it.isEmpty() || GENERIC_EPISODE.matches(it) }?.replace("...", "…")
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (remoteKey(it.nativeKeyEvent.keyCode) != RemoteKey.Menu) return@onKeyEvent false
                if (it.type == KeyEventType.KeyDown && episode.titleId.isNotEmpty()) onMenu()
                true
            }
            .pressable(onClick)
            .padding(Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Box(Modifier.size(STILL).graphicsLayer { shape = CARD_SHAPE; clip = true }.drawBehind { drawRect(Palette.raised) }, contentAlignment = Alignment.Center) {
            if (request != null) AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text("${episode.episode}", style = Type.title.copy(color = Palette.textSecondary))
            if (progress != null) ProgressLine({ progress }, Modifier.align(Alignment.BottomCenter).padding(horizontal = Space.xs, vertical = Space.xs))
            if (watched) WatchedMark(Modifier.align(Alignment.TopEnd).padding(Space.xs))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            FitText(listOfNotNull("${episode.episode}", title).joinToString(". "), style = Type.headline, active = focused)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                if (episode.secs > 0) Text("${(episode.secs + 30) / 60} min", style = Type.caption, maxLines = 1)
                if (rating != null) Text(yoursLabel(rating), style = Type.caption.copy(color = Palette.outOf10(rating.toDouble())), maxLines = 1)
                if (focused && episode.titleId.isNotEmpty()) EpisodePlays(episode.titleId)
            }
        }
    }
}

/** A focused episode's copy, in its caption line ("4K · DTS-HD 5.1 · ☰ More"); asked for only once focus rests. */
@Composable
private fun EpisodePlays(title: TitleId) {
    val films = LocalContext.current.graph.films
    val p by produceState<Prepared?>(null, title) {
        delay(WARM_REST_MS)
        films.state(title).collect { value = it }
    }
    val plays = p?.plays
    LaunchedEffect(plays) { if (plays != null) Check.log("plays", "title" to title, "copy" to plays.id, "src" to plays.source) }
    val text = plays?.let { (formatLabels(it).take(2) + "☰ More").joinToString(" · ") } ?: return
    FitText(text, style = Type.caption, active = true)
}

private val GENERIC_EPISODE = Regex("""(?i)episode\s*\d+""")

/** A watched film's corner mark: a check on an accent disc. */
@Composable
private fun WatchedMark(modifier: Modifier) {
    Box(
        modifier.size(22.dp).drawBehind {
            drawCircle(Palette.accent)
            val w = size.width
            val check = Path().apply {
                moveTo(w * 0.28f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.68f)
                lineTo(w * 0.73f, w * 0.36f)
            }
            drawPath(check, Color.White, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        },
    )
}
