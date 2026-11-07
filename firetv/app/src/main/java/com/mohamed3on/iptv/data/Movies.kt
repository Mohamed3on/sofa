package com.mohamed3on.iptv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.time.Duration.Companion.minutes

/**
 * A film or show on screen: one row of `/api/app/movies` (a 4K film or a UFC PPV replay, by provider [id]) or a
 * library title ([lib]; [id] is then the provider's for an `iptv:` title, else negative, see [titleKey]).
 */
@Serializable
data class Movie(
    val id: Int, val name: String, val poster: String = "", val group: String, val ext: String = "mkv", val titleId: TitleId = "",
    @Transient val lib: LibTitle? = null,
) {
    /** The name as shown, cleaned once: rows ask for it on every focus move. */
    val title by lazy { movieTitle(name) }
    /** A list key unique across films and shows. */
    val key: String get() = titleId.ifEmpty { id.toString() }
}

/**
 * What scores a title (U12): conviction [s] (orders rows) with its net share [sn] (%), [se] 1 when estimated; Letterboxd [lb] (0–5)
 * and IMDb [ir] (0–10) averages with their vote counts [lv] and [iv]; [s10] the combined score out of 10 cards show. A film in
 * TrueScore's store: its adjusted score [ta], all-time and recent net-loved ratios [tr] and [tq] (0–1).
 */
interface Scored {
    val s: Int?
    val sn: Int?
    val se: Int
    val lb: Double?
    val ir: Double?
    val lv: Int?
    val iv: Int?
    val s10: Double?
    val ta: Int?
    val tr: Double?
    val tq: Double?
}

/** Any score is known. */
val Scored.known get() = sn != null || lb != null || ir != null || s10 != null || tr != null


/**
 * A library title (`/api/app/library`, `/api/app/search`, More like this): its id, 'm' film or 't' show, name, year,
 * poster and backdrop (a TMDB path, or a full URL), genre ids, best known tier ([e] 1 when estimated) and scores;
 * a film the provider has, its best copy's VOD id [v] and container [x] (Play starts it when the box can't answer); its runtime [rt] in minutes (a show's: an episode's), 0 unknown.
 */
@Serializable
data class LibTitle(
    val id: TitleId, val k: String = "m", val n: String, val y: Int = 0, val p: String = "", val b: String = "", val g: List<Int> = emptyList(),
    val t: String = "", val e: Int = 0,
    override val s: Int? = null, override val sn: Int? = null, override val se: Int = 0, override val lb: Double? = null, override val ir: Double? = null,
    override val lv: Int? = null, override val iv: Int? = null, override val s10: Double? = null,
    override val ta: Int? = null, override val tr: Double? = null, override val tq: Double? = null,
    val v: Int = 0, val x: String = "", val rt: Int = 0,
) : Scored {
    /** On screen, [genres] its first two genres' names. */
    fun toMovie(genres: String = ""): Movie {
        val provider = if (id.startsWith("iptv:")) id.substringAfterLast(':').toIntOrNull() else v.takeIf { it > 0 }
        return Movie(provider ?: titleKey(id), if (y > 0) "$n ($y)" else n, tmdbImage(p, "w342"), genres, x.ifEmpty { "mkv" }, titleId = id, lib = this)
    }
}

/** A negative id for a title the provider has no id for, so it never reaches a provider call. */
fun titleKey(title: TitleId) = -1 - (title.hashCode() and Int.MAX_VALUE)

/** A film or show (not an episode) by its title id. */
val TitleId.isShow get() = startsWith("tv:") || startsWith("iptv:tv:")
/** A title TMDB knows, so `/api/app/title` describes it. */
val TitleId.isTmdb get() = startsWith("movie:") || startsWith("tv:")

private const val TMDB_IMG = "https://image.tmdb.org/t/p/"

/** A TMDB image path ("/x.jpg") at [size]; a full URL as given. */
fun tmdbImage(p: String, size: String) = if (p.startsWith("/")) "$TMDB_IMG$size$p" else p

/** How long the library on disk counts as current at launch (opening Movies & Shows always revalidates it, a 304 when unchanged). */
val LIBRARY_CHECK = 5.minutes

/** A row of the library: its id ("trending", "ar"), name and titles, best score first. */
class CatalogRow(val id: String, val name: String, val movies: List<Movie>)

/** Movies & Shows (`/api/app/library`): every title once, and the rows that hold them. */
class Catalog(val rows: List<CatalogRow>, titles: List<Movie>) {
    private val byTitle = titles.associateBy { it.titleId }
    fun find(title: TitleId): Movie? = byTitle[title]

    private val searchIndex by lazy { titles.map { searchNorm(it.title.title) to it } }

    /** Titles containing every typed word, at most [SEARCH_CAP]: search with the box out of reach. */
    fun search(query: String): List<Movie> = matching(searchIndex, query)
}

@Serializable
private class LibraryJson(val gn: Map<String, String> = emptyMap(), val titles: List<LibTitle>, val rows: List<RowJson>)

@Serializable
private class RowJson(val id: String, val n: String, val i: List<Int>)

/** Parses and validates the library; throws on anything with no row. */
fun parseCatalog(text: String): Catalog {
    val lib = json.decodeFromString(LibraryJson.serializer(), text)
    val movies = lib.titles.map { t -> t.toMovie(t.g.mapNotNull { lib.gn[it.toString()] }.take(2).joinToString(", ")) }
    val rows = lib.rows.mapNotNull { r -> r.i.mapNotNull(movies::getOrNull).takeIf { it.isNotEmpty() }?.let { CatalogRow(r.id, r.n, it) } }
    require(rows.isNotEmpty()) { "no rows" }
    return Catalog(rows, movies)
}

/** A cast member or director, for their page. */
@Serializable
data class Person(val id: Int, val name: String, val role: String = "")

@Serializable
private class TitlesAnswer(val name: String = "", val titles: List<LibTitle> = emptyList())

/** `/api/app/search`'s or `/api/app/person`'s titles as cards. */
fun parseTitles(text: String): List<Movie> = json.decodeFromString(TitlesAnswer.serializer(), text).titles.map { it.toMovie() }

/** A followed show's next episode (`/api/app/history`): [episode] "S2 E4" ([label]), [new] when it aired after the show was last watched, [air] its air date ("2026-11-04"). */
class NextEpisode(val show: Movie, val episode: TitleId, val label: String, val new: Boolean, val air: String = "")

/**
 * The owner's history from the box (U16): every film and episode Trakt and Letterboxd mark [watched] (merged with
 * the stick's own marks in [Marks]), the Watchlist row, and followed shows' next episodes, latest aired first;
 * the owner's rating per title out of 10 ([ratings]: Trakt's, else IMDb's, else Letterboxd's stars doubled), and when Trakt
 * last saw a film or show watched ([seen]).
 */
class History(val watched: Set<TitleId>, val watchlist: List<Movie>, val next: List<NextEpisode>, val ratings: Map<TitleId, Int> = emptyMap(), val seen: Map<TitleId, Seen> = emptyMap())

/** A title's last watch, epoch seconds ([at] 0: watched, the day unknown), and how many times a film was watched. */
class Seen(val at: Long, val plays: Int)

@Serializable
private class HistoryJson(val watched: List<TitleId> = emptyList(), val watchlist: List<LibTitle> = emptyList(), val next: List<NextJson> = emptyList(), val ratings: Map<TitleId, Int> = emptyMap(), val seen: Map<TitleId, List<Long>> = emptyMap())

@Serializable
private class NextJson(val t: LibTitle, val ep: TitleId, val c: String, val new: Int = 0, val air: String = "")

fun parseHistory(text: String): History {
    val h = json.decodeFromString(HistoryJson.serializer(), text)
    return History(h.watched.toHashSet(), h.watchlist.map { it.toMovie() }, h.next.map { NextEpisode(it.t.toMovie(), it.ep, it.c, it.new == 1, it.air) }, h.ratings, h.seen.mapValues { (_, v) -> Seen(v.getOrElse(0) { 0 }, v.getOrElse(1) { 1 }.toInt()) })
}

/** A movie's name as shown: [title] without the provider's [badge] prefix ("EN", "HDR") and the [year] pulled out. */
data class MovieTitle(val title: String, val year: String?, val badge: String?)

/** A movie group's name as shown ("🎬 NETFLIX MOVIES 4K" → "Netflix movies"), its language prefix as a [badge]. */
data class GroupLabel(val name: String, val badge: String?)

@Serializable
data class Movies(val groups: List<String>, val movies: List<Movie>) {
    private val byGroup by lazy { movies.groupBy { it.group } }
    private val byId by lazy { movies.associateBy { it.id } }
    private val byTitle by lazy { movies.asReversed().associateBy { it.titleId } }

    /** The groups with any movies, in server order, each with its movies in server order. */
    val rows: List<Pair<String, List<Movie>>> by lazy { groups.mapNotNull { g -> byGroup[g]?.takeIf { it.isNotEmpty() }?.let { g to it } } }

    fun find(id: Int): Movie? = byId[id]

    /** The first copy of [title] the library lists. */
    fun findTitle(title: TitleId): Movie? = byTitle[title]

    /** Each title once (a film listed in two groups), in search form, built on the first search. */
    private val searchIndex by lazy {
        movies.distinctBy { it.title.title.lowercase() to it.title.year }.map { searchNorm(it.title.title) to it }
    }

    /** Titles containing every typed word, in server order, at most [SEARCH_CAP]. */
    fun search(query: String): List<Movie> = matching(searchIndex, query)
}

private fun matching(index: List<Pair<String, Movie>>, query: String): List<Movie> {
    val words = searchNorm(query).trim().split(' ').filter(String::isNotEmpty)
    if (words.isEmpty()) return emptyList()
    return index.asSequence().filter { (name, _) -> words.all(name::contains) }.map { it.second }.take(SEARCH_CAP).toList()
}

private const val SEARCH_CAP = 120

private val json = Json { ignoreUnknownKeys = true }

/** One episode of a show, from `/api/app/episodes`; [secs] is 0 when unknown. */
@Serializable
data class Episode(val id: Int, val titleId: TitleId = "", val season: Int, val episode: Int, val title: String = "", val ext: String = "mkv", val secs: Int = 0, val image: String = "",
    /** The stream's quality as labels, best first ("4K", "HDR10", "HEVC", "18 Mbps", "E-AC3 5.1"); empty when unknown. */
    val quality: List<String> = emptyList(),
    /** The picture in a word: Reference, Great, OK or Soft; "" when unknown. */
    val tier: String = "",
) {
    /** "S1 E3". */
    val code get() = "S$season E$episode"
}

/** A show's plot, backdrop, genres, cast and rating, and its episodes, from `/api/app/episodes`. */
@Serializable
data class ShowEpisodes(
    val episodes: List<Episode>, val plot: String = "", val backdrop: String = "", val genre: String = "", val cast: String = "", val rating: String = "",
) {
    /** The show's facts in a film's shape, for the screens that show either. */
    val info get() = MovieInfo(plot = plot, genre = genre, rating = rating, backdrop = backdrop, cast = cast)
}

fun parseEpisodes(text: String): ShowEpisodes = json.decodeFromString(ShowEpisodes.serializer(), text)

/**
 * A film's plot, running time ([secs], 0 when unknown), genres, rating, backdrop, cast and director ("" when unknown), from
 * `/api/app/movie`; or a library title's from `/api/app/title`, with its [people], More like this ([more]), scores, and for a
 * show the provider's [series] id (its episodes come from the provider), else its [episodes] from TMDB.
 */
@Serializable
data class MovieInfo(
    val plot: String = "", val secs: Int = 0, val genre: String = "", val rating: String = "", val backdrop: String = "", val cast: String = "", val director: String = "",
    /** The stream's quality as labels, best first ("4K", "Dolby Vision", "HEVC", "20 Mbps", "Atmos"); empty when unknown. */
    val quality: List<String> = emptyList(),
    /** The picture in a word: Reference, Great, OK or Soft; "" when unknown. */
    val tier: String = "",
    /** [tier] is the category's claim, not yet measured (the first play measures it). */
    val estimated: Boolean = false,
    val people: List<Person> = emptyList(),
    val more: List<LibTitle> = emptyList(),
    val series: Int = 0,
    val episodes: List<Episode> = emptyList(),
    /** TMDB's best YouTube trailer ("JE9z-gy4De4"); "" when there is none. */
    val trailer: String = "",
    /** The original language's name ("French"); "" when unknown. */
    val language: String = "",
    /** The title in its own language, only when it differs from the name ("千と千尋の神隠し"). */
    val originalTitle: String = "",
    /** A film's franchise ("The Lord of the Rings Collection") and its other films, oldest first. */
    val collection: String = "",
    val parts: List<LibTitle> = emptyList(),
    /** A show's network ("HBO"), TMDB's status ("Returning Series", "Ended", "Canceled"…) and next episode. */
    val network: String = "",
    val status: String = "",
    val next: NextAir? = null,
    /** Without TrueScore's own ratio: the net-loved share (0–1) of the histogram the box scored it on, [hs] whose ("l" Letterboxd's, "i" IMDb's). */
    val hn: Double? = null, val hs: String = "",
    override val s: Int? = null, override val sn: Int? = null, override val se: Int = 0, override val lb: Double? = null, override val ir: Double? = null,
    override val lv: Int? = null, override val iv: Int? = null, override val s10: Double? = null,
    override val ta: Int? = null, override val tr: Double? = null, override val tq: Double? = null,
) : Scored {
    /** [more] as cards, built once. */
    @Transient val moreMovies: List<Movie> = more.map { it.toMovie() }
    /** [parts] as cards, built once. */
    @Transient val partMovies: List<Movie> = parts.map { it.toMovie() }
}

/** A show's next episode and its air date ("2026-11-14"). */
@Serializable
data class NextAir(val season: Int, val episode: Int, val air: String)

fun parseMovieInfo(text: String): MovieInfo = json.decodeFromString(MovieInfo.serializer(), text)

/** Parses and validates the movie list; throws on anything that is not one with at least one movie. */
fun parseMovies(text: String): Movies {
    val movies = json.decodeFromString(Movies.serializer(), text)
    require(movies.movies.isNotEmpty()) { "no movies" }
    return movies
}

private val SPACES = Regex("\\s+")
private val TITLE_PREFIX = Regex("""^([A-Z][A-Z+]{1,2})\s+-\s+""")
private val YEAR = Regex("""\(((?:19|20)\d{2})\)""")

/**
 * "EN - UFC 328: Chimaev vs Strickland (2026)" → "UFC 328: Chimaev vs Strickland", 2026, badge "EN".
 * Anything after the year ("(Multi Audio)", "(4k)", a second title) goes with it.
 */
fun movieTitle(raw: String): MovieTitle {
    var s = raw.trim()
    val prefix = TITLE_PREFIX.find(s)
    if (prefix != null) s = s.substring(prefix.range.last + 1)
    val year = YEAR.find(s)
    if (year != null && year.range.first > 0) s = s.substring(0, year.range.first)
    val title = s.replace(SPACES, " ").trim().ifEmpty { raw.trim() }
    return MovieTitle(title, year?.groupValues?.get(1), prefix?.groupValues?.get(1))
}

private val GROUP_LANGUAGE = Regex("""^([A-Z]{2})\s+-\s+""")
private val GROUP_NOISE = Regex("""\s*\(VOD\)|\s+4K$""")
/** Words kept as their makers spell them; everything else reads in sentence case. */
private val BRANDS = listOf("UFC", "PPV", "Netflix", "Amazon", "Disney+", "Apple+", "Marvel", "OSN+", "HDR", "HBO", "Max", "FIFA+", "WWE", "TrueScore").associateBy { it.uppercase() }
private val groupLabels = java.util.concurrent.ConcurrentHashMap<String, GroupLabel>()

fun groupLabel(raw: String): GroupLabel = groupLabels.getOrPut(raw) {
    var s = raw.removePrefix("🎬").replace(GROUP_NOISE, "").trim()
    val language = GROUP_LANGUAGE.find(s)
    if (language != null) s = s.substring(language.range.last + 1)
    val words = s.split(SPACES).filter(String::isNotEmpty).mapIndexed { i, w ->
        BRANDS[w.uppercase()] ?: w.replace('İ', 'I').lowercase(Locale.ROOT).let { if (i == 0) it.replaceFirstChar(Char::uppercase) else it }
    }
    GroupLabel(words.joinToString(" ").ifEmpty { raw }, language?.groupValues?.get(1))
}

private val TMDB_SIZE = Regex("""^https?://(?:image\.tmdb\.org|www\.themoviedb\.org)/t/p/[^/]+/""")

/** TMDB posters at the size a card needs (w342) rather than 600×900 or the original, a tenth of the bytes. */
fun posterUrl(raw: String): String = raw.replace(TMDB_SIZE, "https://image.tmdb.org/t/p/w342/")

/** A TMDB image at [width] ("w1280" for a full-screen backdrop, "w780" for a card, "w300" for an episode still); others as given. */
fun tmdbAt(raw: String, width: String): String = raw.replace(TMDB_SIZE, "https://image.tmdb.org/t/p/$width/")

private const val MIN_RESUME_MS = 60_000L
/** Past this share of its length a play is finished (Trakt's own threshold): logged, marked watched, no longer resumed. */
const val FINISHED = 0.9

/** Whether a play at [pos] of [dur] is finished. */
fun finished(pos: Long, dur: Long) = dur > 0 && pos >= dur * FINISHED

/** Where OK starts a movie with this saved place: there, if past the first minute and not [finished]; else the beginning. */
fun resumeAt(pos: Long, dur: Long): Long = if (pos > MIN_RESUME_MS && !finished(pos, dur)) pos else 0L

/**
 * Saved places, newest first, at most [CAP], persisted as one JSON value under [key]. Only resumable
 * ones are kept: a movie saved before its first minute or past 90% (finished) drops out of Continue
 * watching. A show's episode stays from its start (the next episode is saved at 0 when one ends),
 * and only a finished episode with nothing after it drops the show. Keyed by title (KTD10), so every
 * copy of a film, and every language of a show, shares one place.
 */
class ResumeStore(private val store: KeyValueStore, private val key: String, private val now: () -> Long = System::currentTimeMillis) {
    private val serializer = ListSerializer(Resume.serializer())
    private val _entries = MutableStateFlow(
        store.get(key)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    )
    val entries: StateFlow<List<Resume>> = _entries

    /** Where [title] (for a show, its [episode]) starts: its saved place, else the beginning. */
    fun startAt(title: TitleId, episode: TitleId? = null): Long =
        find(title)?.takeIf { it.episode == episode }?.let { resumeAt(it.pos, it.dur) } ?: 0L

    fun find(title: TitleId): Resume? = _entries.value.firstOrNull { it.title == title }

    /** Takes [title] out of Continue watching. */
    @Synchronized
    fun remove(title: TitleId) = write(_entries.value.filter { it.title != title })

    /** [movie]'s place (for a show, its [episode]'s). */
    fun save(movie: Movie, pos: Long, dur: Long, episode: TitleId? = null, label: String? = null) = save(movie.resume(pos, dur, episode, label))

    /** Saves [place], stamped now; one with no title id (a server from before them) is not kept. */
    @Synchronized
    fun save(place: Resume) {
        if (place.title.isEmpty()) return
        val rest = _entries.value.filter { it.title != place.title }
        val keep = resumeAt(place.pos, place.dur) > 0 || (place.episode != null && !finished(place.pos, place.dur))
        write(if (keep) (listOf(place.copy(at = now())) + rest).take(CAP) else rest)
    }

    /** Adds [places] as saved (their own [Resume.at]); a title already here keeps whichever place is newer. */
    @Synchronized
    fun merge(places: List<Resume>) =
        write((places + _entries.value).sortedByDescending { it.at }.distinctBy { it.title }.take(CAP))

    private fun write(next: List<Resume>) {
        if (next == _entries.value) return
        _entries.value = next
        store.put(key, json.encodeToString(serializer, next))
    }

    private companion object {
        const val CAP = 50
    }
}

/**
 * What was watched to the end (past 90%), by title id (a film or an episode), persisted under [key];
 * the newest [CAP] are kept.
 */
class WatchedStore(private val store: KeyValueStore, private val key: String) {
    private val _ids = MutableStateFlow<Set<TitleId>>(store.get(key)?.split(',')?.filter(String::isNotEmpty)?.toCollection(LinkedHashSet()) ?: emptySet())
    val ids: StateFlow<Set<TitleId>> = _ids

    fun mark(id: TitleId) = markAll(listOf(id))

    @Synchronized
    fun markAll(ids: Collection<TitleId>) {
        val new = ids.filter { it.isNotEmpty() && it !in _ids.value }
        if (new.isEmpty()) return
        val next = (_ids.value + new).toList().takeLast(CAP).toCollection(LinkedHashSet())
        _ids.value = next
        store.put(key, next.joinToString(","))
    }

    @Synchronized
    fun unmarkAll(ids: Collection<TitleId>) {
        val next = _ids.value - ids.toSet()
        if (next.size == _ids.value.size) return
        _ids.value = next
        store.put(key, next.joinToString(","))
    }

    private companion object {
        const val CAP = 5_000
    }
}
