package com.mohamed3on.iptv.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mohamed3on.iptv.data.Catalog
import com.mohamed3on.iptv.data.Episode
import com.mohamed3on.iptv.data.History
import com.mohamed3on.iptv.data.KeyValueStore
import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.data.NextEpisode
import com.mohamed3on.iptv.data.Person
import com.mohamed3on.iptv.data.Resume
import com.mohamed3on.iptv.data.ResumeStore
import com.mohamed3on.iptv.data.TitleId
import com.mohamed3on.iptv.data.isShow
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.system.Check
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** A page over the browsing screen: a title's, or a person's titles. Back takes the top one off. */
internal sealed interface LibraryPage {
    data class Title(val movie: Movie, val versions: Boolean = false) : LibraryPage
    data class Cast(val person: Person) : LibraryPage
}

/** Pages kept under the top one, so Back lands where each was left; past this the oldest goes. */
private const val MAX_PAGES = 6
/** The next episode is prepared this long after one starts, so its start has the line to itself. */
private const val NEXT_WARM_MS = 15_000L
/** What was played (or a followed show's episode aired) within this long leads the screen as Up next; older is a Continue watching row. */
private const val RECENT_DAYS = 7L

/** A show's episode on screen: the show, its episodes, and which one plays. */
internal data class ShowPlay(val show: Movie, val episodes: List<Episode>, val index: Int) {
    val episode get() = episodes[index]
}

/**
 * What plays: the movie (for a show, the episode as one), its [title] id and where it starts (ms);
 * [fallback] is the library's IPTV copy, for when the box can't say what else there is.
 */
internal data class Playing(val movie: Movie, val from: Long, val title: TitleId, val fallback: Copy?, val show: ShowPlay? = null)

/** The library's own copy of a film or episode (provider stream [id]); none for a title the provider lacks. */
private fun libraryCopy(id: Int) = if (id > 0) Copy("iptv:$id", Capacity.Source.Iptv) else null

/** Live TV and the multi-view tiles: the provider's one connection, which Movies & Shows takes while open. */
internal interface LiveLine {
    /** Whether the channel is stopped, now and on every change. */
    val stopped: Flow<Boolean>
    /** Stops the tiles and the channel. */
    fun stop()
    /** Tunes the channel again. */
    fun retry()
}

/**
 * Movies & Shows' navigation, which its Composables draw: the [pages] over the browsing screen, what is [playing], the
 * next episode, the screen a launch reopens ([Check.LAST_SCREEN]: what plays, else the top title page, else the browsing
 * screen) and the provider's one connection ([hold]). [episodesOf] is a show's episodes (null when they can't be read),
 * [providerId] the provider film list's id for a title, [warm] prepares a title's copies; [scope] runs the lookups.
 */
internal class MoviesNavigator(
    private val prefs: KeyValueStore,
    private val films: ResumeStore,
    private val shows: ResumeStore,
    private val episodesOf: suspend (Movie) -> List<Episode>?,
    private val watched: () -> Set<TitleId>,
    private val nextUp: () -> List<NextEpisode>,
    private val providerId: (TitleId) -> Int?,
    private val warm: (TitleId) -> Unit,
    private val scope: CoroutineScope,
) {
    private val stack = mutableStateListOf<LibraryPage>()
    /** The pages on screen over the browsing screen, the top one last. */
    val pages: List<LibraryPage> get() = stack
    /** The film or episode on screen. */
    var playing by mutableStateOf<Playing?>(null)
        private set
    /** Whether an episode follows the one playing. */
    val hasNext get() = playing?.show?.let { it.index < it.episodes.lastIndex } == true
    private var warming: Job? = null

    /** A title's page; a person search found ("person:<id>") opens theirs. */
    fun open(movie: Movie) {
        val person = movie.titleId.takeIf { it.startsWith("person:") }?.removePrefix("person:")?.toIntOrNull()
        push(if (person != null) LibraryPage.Cast(Person(person, movie.name)) else LibraryPage.Title(movie))
    }

    fun open(person: Person) = push(LibraryPage.Cast(person))

    /** Takes the top page off. */
    fun back() {
        stack.removeLastOrNull()
        record()
    }

    /** A film from its saved place, or from the start. */
    fun playFilm(movie: Movie, fromStart: Boolean) {
        val id = movie.id.takeIf { it > 0 } ?: providerId(movie.titleId) ?: 0
        onScreen(Playing(movie, if (fromStart) 0 else films.startAt(movie.titleId), movie.titleId, libraryCopy(id)))
    }

    /** A show's episode [index], from its saved place or the start. */
    fun playEpisode(show: Movie, episodes: List<Episode>, index: Int, fromStart: Boolean) =
        onScreen(episode(show, episodes, index, if (fromStart) 0 else shows.startAt(show.titleId, episodes[index].titleId)))

    /** Up next's buttons: a film at its place; a show's episode up next (its episodes are kept a while, so this is quick), else its page. */
    fun resume(movie: Movie, fromStart: Boolean) {
        if (!movie.titleId.isShow) return playFilm(movie, fromStart)
        scope.launch {
            val eps = episodesOf(movie)?.takeIf { it.isNotEmpty() }
            val place = shows.find(movie.titleId) ?: nextUp().firstOrNull { it.show.titleId == movie.titleId }?.place()
            if (eps == null) push(LibraryPage.Title(movie)) else playEpisode(movie, eps, upNextIndex(eps, place, watched()), fromStart)
        }
    }

    /** The episode after the one playing, from its start; false when there is none. */
    fun nextEpisode(): Boolean {
        val s = playing?.show?.takeIf { it.index < it.episodes.lastIndex } ?: return false
        val next = s.episodes[s.index + 1]
        // Continue watching moves on to the next episode even if it is left in its first minute.
        shows.save(s.show, 0, 0, next.titleId, next.code)
        onScreen(episode(s.show, s.episodes, s.index + 1, 0))
        return true
    }

    /** The end of a film or episode: the next episode, else back where it was played from. */
    fun ended() {
        if (!nextEpisode()) stop()
    }

    /** The player closed: back where it was played from. */
    fun stop() = onScreen(null)

    /** A screen asked for by name (a launch's last screen, firetv/check.sh): `title`, `versions`, `play`, else the browsing screen. */
    fun reopen(screen: String, title: Movie) {
        stack.clear()
        if (screen != "play") stop()
        when (screen) {
            "title", "versions" -> push(LibraryPage.Title(title, versions = screen == "versions"))
            "play" -> resume(title, fromStart = false)
        }
    }

    /**
     * While Movies & Shows is [open], live TV and the tiles stay stopped, even when the app starts them again (Home and
     * back): the provider's one connection and the bandwidth are the films' (KTD20). Leaving re-tunes the same channel,
     * and a launch then opens on live TV.
     */
    suspend fun hold(line: LiveLine, open: Boolean) {
        if (!open) {
            prefs.put(Check.LAST_SCREEN, null)
            if (line.stopped.first()) line.retry()
            return
        }
        if (prefs.get(Check.LAST_SCREEN) == null) record()
        line.stopped.collect { if (!it) line.stop() }
    }

    private fun push(page: LibraryPage) {
        if (stack.size >= MAX_PAGES) stack.removeAt(0)
        stack.add(page)
        record()
    }

    private fun episode(show: Movie, episodes: List<Episode>, index: Int, from: Long): Playing {
        val e = episodes[index]
        val name = listOf(show.title.title, e.code, e.title).filter(String::isNotEmpty).joinToString(" · ")
        val movie = Movie(id = e.id, name = name, poster = show.poster, group = show.group, ext = e.ext)
        return Playing(movie, from, e.titleId, libraryCopy(e.id), ShowPlay(show, episodes, index))
    }

    private fun onScreen(p: Playing?) {
        playing = p
        // While an episode plays, the next one is prepared, so Next episode and the end of this one start at once (KTD13).
        warming?.cancel()
        warming = p?.show?.let { s -> s.episodes.getOrNull(s.index + 1)?.titleId?.ifEmpty { null } }
            ?.let { next -> scope.launch { delay(NEXT_WARM_MS); warm(next) } }
        record()
    }

    /** Closing the app mid-film reopens it playing from its saved place (a show: its episode up next). */
    private fun record() = prefs.put(
        Check.LAST_SCREEN,
        playing?.let { "play:${it.show?.show?.titleId ?: it.title}" }
            ?: stack.filterIsInstance<LibraryPage.Title>().lastOrNull()?.let { "title:${it.movie.titleId}" }
            ?: "movies",
    )
}

/** A show's episode up next: the one in progress, else the first not yet watched, else the first. */
internal fun upNextIndex(episodes: List<Episode>, resume: Resume?, watched: Set<TitleId>): Int =
    episodes.indexOfFirst { it.titleId == resume?.episode }.takeIf { it >= 0 }
        ?: episodes.indexOfFirst { it.titleId !in watched }.takeIf { it >= 0 }
        ?: 0

/** A next episode as Up next's place: the show and its episode, nothing played yet ([Resume.at] 0). */
internal fun NextEpisode.place() = Resume(show.titleId, show.title.title, show.poster, show.title.year, 0, 0, 0, episode, label)

/** Followed shows' next episodes (R31): none for a show part-way through (that episode shows instead), nor one watched here or taken out. */
internal fun nextUpOf(history: History?, showPlaces: List<Resume>, watchedHere: Set<TitleId>, dismissed: Set<TitleId>) =
    history?.next.orEmpty().filter { n -> showPlaces.none { it.title == n.show.titleId } && n.episode !in watchedHere && n.episode !in dismissed }

/**
 * Up next, and what is part-way through but not recent (the Continue watching row). Each saved place ([resume], newest
 * first) with its title as last played (its provider id, for its episodes and IPTV copy) and as the library has it now
 * (tier and score); one played only from a debrid from its own name and poster (R21). Then followed shows' next
 * episodes, latest aired first. Only what was played, or aired, in the last week is Up next.
 */
internal fun upNextOf(library: Catalog?, resume: List<Resume>, nextUp: List<NextEpisode>, now: Long = System.currentTimeMillis()): Pair<List<Pair<Movie, Resume>>, List<Movie>> {
    if (library == null) return emptyList<Pair<Movie, Resume>>() to emptyList()
    val since = now - RECENT_DAYS * 86_400_000L
    val sinceDay = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().minusDays(RECENT_DAYS).toString()
    val all = resume.map { r ->
        val lib = library.find(r.title)
        Triple(r.iptv?.let { if (lib != null) it.copy(lib = lib.lib, group = lib.group) else it } ?: lib ?: placeTitle(r), r, r.at >= since)
    } + nextUp.map { n -> Triple(library.find(n.show.titleId) ?: n.show, n.place(), n.air >= sinceDay) }
    return all.filter { it.third }.map { it.first to it.second } to all.filterNot { it.third }.map { it.first }
}

/** A title the provider doesn't carry, as its saved place names it: no provider id (negative, never asked about), so only its copies play it. */
private fun placeTitle(r: Resume) =
    Movie(id = -1 - (r.title.hashCode() and Int.MAX_VALUE), name = listOfNotNull(r.name, r.year?.let { "($it)" }).joinToString(" "), poster = r.poster, group = "", titleId = r.title)
