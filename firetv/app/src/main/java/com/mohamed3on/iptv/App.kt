package com.mohamed3on.iptv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.mohamed3on.iptv.data.LiveScores
import com.mohamed3on.iptv.data.LiveSync
import com.mohamed3on.iptv.data.SofaServer
import com.mohamed3on.iptv.data.TitlesClient
import com.mohamed3on.iptv.data.Lineup
import com.mohamed3on.iptv.data.PrefsStore
import com.mohamed3on.iptv.data.Route
import com.mohamed3on.iptv.data.LIBRARY_CHECK
import com.mohamed3on.iptv.data.RecentSearches
import com.mohamed3on.iptv.data.ResumeStore
import com.mohamed3on.iptv.data.TitleMigration
import com.mohamed3on.iptv.data.TraktSync
import com.mohamed3on.iptv.data.NetworkMonitor
import com.mohamed3on.iptv.data.WatchedStore
import com.mohamed3on.iptv.data.Watchlist
import com.mohamed3on.iptv.data.Marks
import com.mohamed3on.iptv.data.WatchHistory
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.player.CopyChoices
import com.mohamed3on.iptv.player.FilmCopies
import com.mohamed3on.iptv.player.FilmHeads
import com.mohamed3on.iptv.player.Telemetry
import com.mohamed3on.iptv.data.Debrids
import com.mohamed3on.iptv.data.RealDebrid
import com.mohamed3on.iptv.data.TorBox
import com.mohamed3on.iptv.player.Setup
import com.mohamed3on.iptv.system.VoiceFix
import com.mohamed3on.iptv.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class App : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch {
            graph.sync.loadCached()
            graph.sync.refresh(Duration.ZERO, Duration.ZERO) // launch refresh; playback never waits on it (R25)
            // The movie library is read once the first tune has had the stick to itself, so Movies opens at once.
            delay(5.seconds)
            launch { graph.trakt.flush() } // plays finished while the box was out of reach
            graph.titles.library.loadCached()
            graph.titles.library.sync(LIBRARY_CHECK)
            graph.titles.history.loadCached()
            graph.syncHistory()
            graph.titles.movies.loadCached()
            withContext(Dispatchers.Main) { graph.setup } // its callbacks land on the main thread too
            graph.migration.run(graph.titles::titleIds)
        }
        graph.scope.launch { VoiceFix.onceThisBoot(this@App) }
    }

    /** Channel logos load through the shared HTTP client; the banner swaps them on every zap, so no crossfade. */
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
        .crossfade(false)
        .build()
}

val Context.graph get() = (applicationContext as App).graph

/** App-wide singletons. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The one HTTP client: feed sync now, Media3's OkHttp data source later. */
    val http = OkHttpClient()
    val prefs = PrefsStore(context.getSharedPreferences("iptv", Context.MODE_PRIVATE))
    val network = NetworkMonitor(context)
    val route = Route(prefs, network.vpn, scope)
    val history = WatchHistory(prefs)
    /** Favourites and each group's own channel order. */
    val lineup = Lineup(prefs)
    /** Where each film was left, by title id (the provider-id keys before them, "movies.resume" and co, are left alone). */
    val resume = ResumeStore(prefs, "titles.movies.resume")
    /** Where each show was left: its episode and place. */
    val showResume = ResumeStore(prefs, "titles.shows.resume")
    /** Films and episodes watched to the end. */
    val watchedMovies = WatchedStore(prefs, "titles.movies.watched")
    val watchedEpisodes = WatchedStore(prefs, "titles.shows.watched")
    /** Each show's episode last finished here, and when: the box follows the shows watched in the last 6 months (U16). */
    val followed = ResumeStore(prefs, "titles.shows.followed")
    /** Next episodes taken out of Up next with Menu, by episode title id. */
    val dismissed = WatchedStore(prefs, "titles.upnext.dismissed")
    /** Carries the marks saved under provider ids over to title ids, once. */
    val migration = TitleMigration(prefs, resume, showResume, watchedMovies, watchedEpisodes)
    /** Typical film bandwidth per source, hour band and route (VPN on or off). */
    val capacity = Capacity(prefs, vpn = { network.vpn.value })
    /** The Trakt watchlist with the owner's own changes applied at once (title page button, ☰ on a card). */
    val watchlist by lazy { Watchlist(titles, scope) }
    /** Watched marks and ratings, the owner's changes shown at once and written to Trakt (☰ on title pages and episodes). */
    val marks by lazy { Marks(titles, watchedMovies, watchedEpisodes, scope) { syncHistory(Duration.ZERO) } }
    /** Movies & Shows searches that led to a title, offered under an empty search box. */
    val recentSearches = RecentSearches(prefs)
    /** Marks, corrections and pins the copy ranker reads. */
    val copyChoices = CopyChoices(prefs)
    /** Every call to the box goes through this. */
    val server = SofaServer(http, BuildConfig.SERVER_URL, BuildConfig.APP_TOKEN)
    /** Channels and guide. */
    val sync = LiveSync(server, context.filesDir, prefs)
    /** Today's football scores, polled while live TV is on screen. */
    val scores = LiveScores(server)
    /** Films and shows: the library, history, title pages, copies, subtitles, marks and the watchlist. */
    val titles = TitlesClient(server, context.filesDir, prefs, onEpisodes = migration::adoptEpisodes)
    /** What the TV, stick and soundbar can show and play; built off the launch path. */
    val setup by lazy { Setup(context) }
    /** Events the server keeps for the stick (/api/app/events). */
    val telemetry by lazy { Telemetry(server, scope) }
    /** Trakt through the box: the Options sign-in and finished plays, queued until logged. */
    val trakt by lazy { TraktSync(server, prefs, scope) }
    /**
     * TorBox and Real-Debrid, whichever has the copy ([Debrids]), each from the stick's own IP; one instance, so
     * account lists and answers are shared.
     */
    val debrid by lazy {
        Debrids(
            TorBox(http, { sync.torboxToken }, scope = scope),
            RealDebrid(http, { sync.realDebridToken }, onInfringing = ::refused, scope = scope),
            scope,
        )
    }
    /** "<service> premium has ended" shows once an app session, once both debrids are down. */
    val premiumEndedShown = java.util.concurrent.atomic.AtomicBoolean()
    /** Each title's copies, ranked and checked ahead of Play. Touch [setup] on the main thread first. */
    val films by lazy { FilmCopies(titles::copies, debrid, copyChoices, capacity, { setup.profile.value }, scope, ip = { network.vpn.value }, linked = filmHeads::fetch) }
    /** The head and index of a Real-Debrid file whose link was fetched ahead, served to the film player from memory. */
    val filmHeads by lazy { FilmHeads(http) }

    /** The owner's history from the box, telling it the shows finished here in the last 6 months ("tv:N:sSeE@ms"). */
    suspend fun syncHistory(maxAge: Duration = LIBRARY_CHECK) = titles.history.sync(maxAge, followed.entries.value
        .filter { it.episode != null && System.currentTimeMillis() - it.at < 183.days.inWholeMilliseconds }
        .joinToString(",") { "${it.episode}@${it.at}" }.let { if (it.isEmpty()) "" else "shows=$it" })
    private val refusedHashes = mutableSetOf<String>()

    /** A torrent Real-Debrid refused goes to the box (one post for a burst), which drops it from every title's copies. */
    private fun refused(hash: String) {
        if (synchronized(refusedHashes) { !refusedHashes.add(hash) || refusedHashes.size > 1 }) return
        scope.launch {
            delay(2_000)
            titles.rdInfringing(synchronized(refusedHashes) { refusedHashes.toList().also { refusedHashes.clear() } })
        }
    }
    val updater = Updater(context, server, scope)
}
