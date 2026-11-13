package com.mohamed3on.iptv.player

import android.content.Context
import android.util.Log
import android.view.Window
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mohamed3on.iptv.AppGraph
import com.mohamed3on.iptv.data.Episode
import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.data.DebridState
import com.mohamed3on.iptv.data.ResumeStore
import com.mohamed3on.iptv.data.TitleId
import com.mohamed3on.iptv.data.WatchedStore
import com.mohamed3on.iptv.data.finished
import com.mohamed3on.iptv.data.resume
import com.mohamed3on.iptv.system.Check
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** What a play is of: a film, or a show's [episode] (then [title] is the show). */
class PlayOf(val title: Movie, val episode: Episode? = null) {
    /** The film's or the episode's title id: its copies, watched mark and Trakt entry. */
    val id: TitleId get() = episode?.titleId ?: title.titleId
}

/** Where a play leaves its marks; [trakt] queues a finished play for Trakt. */
class PlayStores(
    val films: ResumeStore,
    val shows: ResumeStore,
    val watchedFilms: WatchedStore,
    val watchedEpisodes: WatchedStore,
    val followed: ResumeStore,
    val trakt: (TitleId) -> Unit,
    val now: () -> Long = System::currentTimeMillis,
)

/**
 * One play of a film or an episode, from Play to leaving (#16): the [session] over its copies, its place kept every
 * [SAVE_EVERY_MS] while it plays and on the way out, and the end-of-play rules. Past [finished] (90%) a play is
 * finished: logged on Trakt (once a play), marked watched, its show followed, and its place no longer offered to
 * resume. Leaving (Back, Home, the idle stop) keeps the place and lets go of the account's one connection. Call
 * from the main thread, with [scope] there too; [close] when the player screen goes.
 */
class FilmPlay(
    private val of: PlayOf,
    private val player: FilmPlayer,
    val session: FilmSession,
    private val stores: PlayStores,
    scope: CoroutineScope,
    /** It played to the end: the next episode, else back to the titles. */
    private val onEnded: () -> Unit,
    /** It was left: back to the titles. */
    private val onClose: () -> Unit,
    /** Each new IP (network or VPN): a debrid link is tied to the old one (KTD2). */
    private val ipChanges: Flow<Any?> = emptyFlow(),
    /** Emits the service whose premium has ended, for the notice. */
    private val premiumEnded: Flow<String> = emptyFlow(),
    /** What else the play holds (decoder, subtitles, meter), let go after the session. */
    private val release: () -> Unit = {},
) {
    private val jobs = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    /** Whether it played since it opened (or since an episode moved on): only then is its place worth keeping. */
    var started = false
        private set
    private var logged = false
    /** The place last kept. */
    private var saved = -1L
    private var closed = false

    /** Plays from [from]; [fallback] is the library's IPTV copy, for when the box can't say what else there is. */
    fun start(from: Place, fallback: Copy?) {
        jobs.launch { ipChanges.collect { session.ipChanged() } }
        jobs.launch { premiumEnded.collect { session.inform(Notice.PremiumEnded(it)) } }
        jobs.launch {
            session.state.map { it.phase }.distinctUntilChanged().collect {
                when (it) {
                    FilmPhase.Playing -> started = true
                    FilmPhase.Ended -> {
                        record(player.duration, player.duration)
                        started = false
                        onEnded()
                    }
                    // Every copy failed: the place stays as it was left.
                    FilmPhase.Unavailable -> save()
                    else -> {}
                }
            }
        }
        jobs.launch {
            while (true) {
                delay(SAVE_EVERY_MS)
                // Only a place that moved: a paused film is left as it was saved.
                if (player.place.position != saved) save()
            }
        }
        session.play(from, fallback)
    }

    /** Keeps the place, if it ever played. */
    fun save() {
        if (!started) return
        saved = player.place.position
        record(saved, player.duration)
    }

    fun pause() {
        session.pause()
        save()
    }

    fun resume() {
        session.resume()
    }

    fun fromStart() {
        session.seek(0)
        resume()
    }

    /** Back out, Home or the idle stop: the place is kept and the connection let go. */
    fun leave() {
        save()
        started = false
        session.stop()
        onClose()
    }

    /**
     * Next episode (from Options, or the card over the credits): this one's place is kept now, so its leaving saves
     * nothing over the next one's; from the credits ([done]) it counts as finished, as its end would.
     */
    fun next(play: () -> Unit, done: Boolean = false) {
        if (done && started) record(player.duration, player.duration) else save()
        started = false
        play()
    }

    /** The player screen went. */
    fun close() {
        if (closed) return
        closed = true
        save()
        session.stop()
        jobs.cancel()
        release()
    }

    private fun record(pos: Long, dur: Long) {
        val e = of.episode
        if (finished(pos, dur)) {
            if (!logged) {
                logged = true
                stores.trakt(of.id)
            }
            (if (e != null) stores.watchedEpisodes else stores.watchedFilms).mark(of.id)
            // The show is followed from here: its next or newly aired episode comes to Up next (R31).
            if (e != null && stores.followed.find(of.title.titleId)?.episode != e.titleId)
                stores.followed.merge(listOf(of.title.resume(pos, dur, e.titleId, e.code, stores.now())))
        }
        if (e == null) stores.films.save(of.title, pos, dur) else stores.shows.save(of.title, pos, dur, e.titleId, e.code)
    }

    companion object {
        const val SAVE_EVERY_MS = 10_000L
    }
}

/** A play on the stick's ExoPlayer: [play] and the decoder-bound parts the player screen draws. */
class ExoFilmPlay(val exo: ExoPlayer, val play: FilmPlay, val plan: CopyPlan, val surface: PlaybackSurface, val subtitles: SubtitleTracks, val fetched: SubtitleFetch, private val begin: () -> Unit) {
    /** Starts the play, once the screen listens. */
    fun start() = begin()
}

/**
 * Opens [of] on a new ExoPlayer, shown through [window]'s display: its session over the copies, OpenSubtitles when a copy has no English (R30), the bandwidth
 * meter its loading teaches (KTD8), and a film's quality reading for the box. [ExoFilmPlay.start] plays it from [from]; [FilmPlay.close] lets it all go.
 */
fun openFilmPlay(
    context: Context, window: Window?, graph: AppGraph, of: PlayOf, from: Long, fallback: Copy?, iptvUrl: (Copy, String?) -> String,
    scope: CoroutineScope, onEnded: () -> Unit, onClose: () -> Unit,
): ExoFilmPlay {
    val title = of.id
    // The file's size, for its bitrate in the film's quality reading.
    val fileBytes = AtomicLong(0)
    // Its loading bursts teach the typical bandwidth; every film is an IPTV copy until a debrid one plays.
    val load = FilmLoad()
    val meter = BandwidthMeter(graph.capacity)
    val setup = graph.setup
    val exo = moviePlayer(context, graph.http, graph.filmHeads, { Hdr.DolbyVision in setup.profile.value.hdr }, load) { fileBytes.compareAndSet(0, it) }
    val film = ExoFilmPlayer(exo)
    val plan = graph.films.plan(title)
    val session = FilmSession(title, plan, film, graph.copyChoices, { setup.profile.value }, meter, iptvUrl, scope, System::currentTimeMillis, graph.telemetry::film)
    val fetched = SubtitleFetch(exo, graph.http, graph.titles, scope, { plan.state.value.answer?.copies?.originalLanguage }) {
        session.inform(Notice.Subtitles(it.lang))
    }
    // The display follows the copy playing: it keeps its mode while the next copy opens, and goes back once the play ends.
    val playback = session.state.map {
        val playback = when (it.phase) {
            FilmPhase.Playing -> Playback.Playing
            FilmPhase.Loading, FilmPhase.Starting -> Playback.Waiting
            FilmPhase.Stopped, FilmPhase.Ended, FilmPhase.Unavailable -> Playback.Stopped
        }
        Showing(playback, it.copy?.id)
    }.distinctUntilChanged()
    val surface = PlaybackSurface(exo, window, playback, scope)
    val subtitles = SubtitleTracks(exo)
    lateinit var play: FilmPlay
    val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && !play.started) Check.log("film_ready")
        }

        override fun onRenderedFirstFrame() {
            session.state.value.copy?.let { Check.log("first_frame", "title" to title, "copy" to it.id, "src" to it.source) }
        }

        // A copy opens (the session has published it): its subtitle lookup starts at once.
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val copy = session.state.value.copy ?: return
            val url = mediaItem?.localConfiguration?.uri?.toString() ?: return
            if (!play.started) Check.log("film_open", "src" to copy.source)
            fetched.find(title, copy.id, url, plan.state.value.answer?.name(copy.id).orEmpty())
        }
    }
    exo.addListener(listener)
    val parts = scope.launch {
        launch {
            while (true) {
                meter.next(System.currentTimeMillis(), load.bytes.get(), load.filling(exo), session.state.value.copy?.source ?: Capacity.Source.Iptv)
                delay(1_000)
            }
        }
        // Each IPTV copy of a film that plays (the first, or one chosen in Versions) is measured for what it really is
        // (an episode's is known already), once; a reading the box keeps refreshes the title's copies, so Versions shows it.
        if (of.episode == null) launch {
            session.state.map { s -> s.copy?.takeIf { s.phase == FilmPhase.Playing && it.source == Capacity.Source.Iptv } }
                .distinctUntilChanged()
                .collectLatest { copy ->
                    val id = copy?.id?.removePrefix("iptv:")?.toIntOrNull() ?: return@collectLatest
                    delay(FILM_READING_MS) // past the first frames, so the formats have settled
                    filmReading(exo, fileBytes.get())?.let { if (graph.titles.reportFilmQuality(id, it)) graph.films.refresh(title) }
                }
        }
    }
    play = FilmPlay(
        of, film, session,
        PlayStores(graph.resume, graph.showResume, graph.watchedMovies, graph.watchedEpisodes, graph.followed, graph.trakt::finished),
        scope, onEnded, onClose,
        ipChanges = graph.route.effective.drop(1),
        premiumEnded = flow {
            val lapsed = graph.debrid.status.first { it.state == DebridState.NotPremium }
            if (graph.premiumEndedShown.compareAndSet(false, true)) emit(lapsed.name)
        },
        release = {
            plan.release()
            parts.cancel()
            surface.release()
            subtitles.release()
            fetched.release()
            film.release()
            exo.removeListener(listener)
            exo.release()
            meter.finish().forEach { (bucket, mbps) -> Log.i("IptvCapacity", "${bucket.first} ${bucket.second}: $mbps Mbps") }
        },
    )
    plan.hold() // built: release (the play's close) lets it go
    return ExoFilmPlay(exo, play, plan, surface, subtitles, fetched) {
        play.start(Place(from), fallback)
    }
}

private const val FILM_READING_MS = 5_000L
