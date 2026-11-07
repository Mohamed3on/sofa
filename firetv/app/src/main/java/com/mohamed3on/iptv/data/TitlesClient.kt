package com.mohamed3on.iptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Films and shows from the box: the lists kept in [dir] (the library, the owner's history, the provider's films) and the
 * title endpoints, each saying how long its answers are kept here.
 */
class TitlesClient(
    private val server: SofaServer,
    private val dir: File,
    store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
    /** Each show's episodes as they arrive (old watched marks move to title ids then). */
    private val onEpisodes: (List<Episode>) -> Unit = {},
) {
    private val files = BoxFiles(server, store, now)
    private val json = Json { ignoreUnknownKeys = true }

    /** The 4K films and UFC replays (v2): the IPTV copy Play falls back on when the box can't list copies. */
    val movies = Library("movies") { text -> parseMovies(text).also { require(it.movies[0].titleId.isNotEmpty()) } }
    /** Movies & Shows (U11): title-first rows of films and shows, revalidated by its ETag (a 304 when unchanged). */
    val library = Library("library", ::parseCatalog)
    /** The owner's history (U16): Trakt and Letterboxd marks, Watchlist and followed shows' next episodes. */
    val history = Library("history", ::parseHistory)

    /**
     * An on-demand list (/api/app/[kind]) as [parse] reads it, kept on disk: read from there only once it's wanted, and
     * synced at most every [sync]'s maxAge.
     */
    inner class Library<T : Any>(private val kind: String, private val parse: (String) -> T) {
        private val file = File(dir, "$kind.json")
        private val lock = Mutex()
        private val _items = MutableStateFlow<T?>(null)
        /** Null until the cached copy is read or the first download lands. */
        val items: StateFlow<T?> = _items

        /** Reads the cached copy, without touching the network. */
        suspend fun loadCached() = lock.withLock {
            withContext(Dispatchers.IO) {
                if (_items.value == null && file.exists()) {
                    // An unreadable copy goes, so the next sync downloads it whole instead of hearing "unchanged";
                    // so does one [parse] rejects (saved before titles had ids, say: resume and watched marks are keyed by them).
                    runCatching { parse(file.readText()) }
                        .onSuccess { _items.value = it }.onFailure { file.delete() }
                }
            }
        }

        /** Returns null when the list is current (fetched, unchanged, or younger than [maxAge]); [query] goes on the URL ("shows=…"). */
        suspend fun sync(maxAge: Duration, query: String = ""): SyncError? = lock.withLock {
            withContext(Dispatchers.IO) {
                files.sync(kind, file, if (_items.value == null) Duration.ZERO else maxAge, query) { tmp, keep ->
                    val items = parse(tmp.readText())
                    keep(tmp)
                    _items.value = items
                }
            }
        }
    }

    private fun <T> get(path: String, parse: (String) -> T): Result<T> = server.get(path) { parse(it.string()) }

    private fun q(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** A film's plot and running time (/api/app/movie); fetched when it is first focused, then kept for the app session. */
    suspend fun movieInfo(id: Int): Result<MovieInfo> = withContext(Dispatchers.IO) {
        movieInfos[id]?.let { return@withContext Result.success(it) }
        get("movie?id=$id", ::parseMovieInfo).onSuccess { movieInfos[id] = it }
    }
    private val movieInfos = ConcurrentHashMap<Int, MovieInfo>()

    /**
     * Films and shows for a search (a title, or a genre or language word), from the box; each query's answer is
     * kept for the app session, so typing back over it answers at once.
     */
    suspend fun search(query: String): Result<List<Movie>> = withContext(Dispatchers.IO) {
        val key = query.trim().lowercase()
        searches[key]?.let { return@withContext Result.success(it) }
        get("search?q=${q(query.trim())}", ::parseTitles).onSuccess { searches[key] = it }
    }
    private val searches = ConcurrentHashMap<String, List<Movie>>()

    /**
     * A library title's page: TMDB's plot, people, More like this and scores (/api/app/title); kept for the app session,
     * and asked again when [fresh] (its page opening) once over 2 minutes old: TrueScore's extension adds scores any
     * time. One without scores is asked again (the box fills them on demand), and its scores, once they come, go to [scores].
     */
    suspend fun title(id: TitleId, fresh: Boolean = false): Result<MovieInfo> = withContext(Dispatchers.IO) {
        val kept = titles[id]
        if (kept != null && kept.known && (!fresh || now() - (titleAt[id] ?: 0) < TITLE_MS)) return@withContext Result.success(kept)
        get("title?id=${q(id)}", ::parseMovieInfo)
            .onSuccess { titles[id] = it; titleAt[id] = now(); if (it.known) _scores.update { s -> s + (id to it) } }
            .let { if (it.isFailure && kept != null) Result.success(kept) else it }
    }
    private val titles = ConcurrentHashMap<TitleId, MovieInfo>()
    private val titleAt = ConcurrentHashMap<TitleId, Long>()
    private val TITLE_MS = 2 * 60_000L
    private val _scores = MutableStateFlow<Map<TitleId, Scored>>(emptyMap())
    /** Scores title pages learnt for titles the library had none for, so their cards show them too. */
    val scores: StateFlow<Map<TitleId, Scored>> = _scores

    /** A person's films and shows, best score first (/api/app/person); kept for the app session. */
    suspend fun person(id: Int): Result<List<Movie>> = withContext(Dispatchers.IO) {
        people[id]?.let { return@withContext Result.success(it) }
        get("person?id=$id", ::parseTitles).onSuccess { people[id] = it }
    }
    fun peekPerson(id: Int): List<Movie>? = people[id]
    private val people = ConcurrentHashMap<Int, List<Movie>>()

    /**
     * What a title page says about [m]: TMDB's for a title it knows (the provider's when that fails), else the
     * provider's own, a show's from its episodes call.
     */
    suspend fun info(m: Movie, fresh: Boolean = false): Result<MovieInfo> {
        if (m.titleId.isTmdb) title(m.titleId, fresh).let { if (it.isSuccess || m.id <= 0) return it }
        if (m.id <= 0) return Result.failure(IOException("no provider id"))
        return if (m.titleId.isShow) episodes(m.id).map { it.info } else movieInfo(m.id)
    }

    /** What [info] already holds for [m], without waiting: a screen's first frame. */
    fun peekInfo(m: Movie): MovieInfo? =
        (if (m.titleId.isTmdb) titles[m.titleId] else null) ?: if (m.titleId.isShow) showCache[m.id]?.second?.info else movieInfos[m.id]

    /**
     * A show's episodes: the provider's when it lists the show ([m]'s own id, or the series TMDB's answer names),
     * else TMDB's, which only copies (Real-Debrid's, say) play.
     */
    suspend fun showOf(m: Movie, fresh: Boolean = false): Result<ShowEpisodes> {
        if (m.id > 0) return episodes(m.id, fresh)
        val info = title(m.titleId).getOrElse { return Result.failure(it) }
        if (info.series > 0) return episodes(info.series, fresh)
        return Result.success(ShowEpisodes(info.episodes, info.plot, info.backdrop, info.genre, info.cast, info.rating))
    }

    /** What [showOf] already holds for [m]. */
    fun peekShowOf(m: Movie): ShowEpisodes? {
        if (m.id > 0) return peekShow(m.id)
        val info = titles[m.titleId] ?: return null
        return if (info.series > 0) peekShow(info.series) else ShowEpisodes(info.episodes, info.plot, info.backdrop, info.genre, info.cast, info.rating)
    }

    /**
     * What a film really is, measured while it played ([reading] from player.filmReading), so the server knows its
     * quality rather than its category's claim (/api/app/film-quality). Once per film per app session; best effort.
     */
    suspend fun reportFilmQuality(id: Int, reading: Map<String, Any>) = withContext(Dispatchers.IO) {
        if (!filmsReported.add(id)) return@withContext
        val body = JsonObject((reading + ("id" to id)).mapValues { (_, v) ->
            when (v) {
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        })
        server.post("film-quality", body) {}.onSuccess { movieInfos.remove(id) }
    }
    private val filmsReported = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** What [episodes] already holds for a show, without waiting. */
    fun peekShow(id: Int): ShowEpisodes? = showCache[id]?.second

    /**
     * A show's facts and episodes, in order (/api/app/episodes); kept for [SHOW_KEEP_MS] (focus moving over shows asks
     * for them often), then fetched again so new episodes appear. [fresh] skips the kept copy (opening a show).
     */
    suspend fun episodes(seriesId: Int, fresh: Boolean = false): Result<ShowEpisodes> = withContext(Dispatchers.IO) {
        showCache[seriesId]?.takeIf { !fresh && now() - it.first < SHOW_KEEP_MS }?.let { return@withContext Result.success(it.second) }
        get("episodes?id=$seriesId", ::parseEpisodes).onSuccess {
            showCache[seriesId] = now() to it
            onEpisodes(it.episodes)
        }
    }
    private val showCache = ConcurrentHashMap<Int, Pair<Long, ShowEpisodes>>()
    private val SHOW_KEEP_MS = 30 * 60_000L

    /** Title ids for the provider ids marks were saved under (/api/app/title-ids, the one-time move to title ids); not kept; throws on failure. */
    suspend fun titleIds(request: TitleIdRequest): TitleIdMap = withContext(Dispatchers.IO) {
        server.post("title-ids", json.encodeToJsonElement(TitleIdRequest.serializer(), request)) {
            json.decodeFromString(TitleIdMap.serializer(), it.string())
        }.getOrThrow()
    }

    /** Every copy of a film or episode the box knows, IPTV's and Real-Debrid's (/api/app/copies); not kept here (FilmCopies keeps them). */
    suspend fun copies(title: TitleId): Result<CopiesAnswer> = withContext(Dispatchers.IO) {
        get("copies?title=${q(title)}") { json.decodeFromString(CopiesAnswer.serializer(), it) }
    }

    /** English, then Arabic, subtitles for the copy playing (/api/app/subtitles): its OpenSubtitles [hash] ("" unknown) and release [name] pick the best. Not kept. */
    suspend fun subtitles(title: TitleId, hash: String, size: Long, name: String): Result<SubtitlesAnswer> = withContext(Dispatchers.IO) {
        get("subtitles?title=${q(title)}&hash=$hash&size=$size&name=${q(name)}") { json.decodeFromString(SubtitlesAnswer.serializer(), it) }
    }

    /** One subtitle file as SRT text (/api/app/subtitle); null when today's downloads are spent (HTTP 429). Not kept. */
    suspend fun subtitle(id: String): Result<String?> = withContext(Dispatchers.IO) {
        get<String?>("subtitle?id=$id") { it }.let { if (it.exceptionOrNull() == SyncError.Rejected(429)) Result.success(null) else it }
    }

    /** Torrents Real-Debrid refused (infringing_file), so the box drops them from every title's copies; best effort. */
    suspend fun rdInfringing(hashes: List<String>) = withContext(Dispatchers.IO) {
        server.post("rd-infringing", buildJsonObject { put("hashes", JsonArray(hashes.map(::JsonPrimitive))) }) {}
    }

    /** Puts a film or show on the Trakt watchlist, or takes it off, through the box: false when that failed. */
    suspend fun setWatchlist(title: TitleId, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        server.post("watchlist", buildJsonObject {
            put("title", JsonPrimitive(title))
            put("on", JsonPrimitive(on))
        }) {}.isSuccess
    }

    /** Marks a title watched or not ([watched]), or rates it 1–10 or clears its rating (null, with [rate]), on Trakt through the box: false when that failed. */
    suspend fun mark(title: TitleId, watched: Boolean? = null, rating: Int? = null, rate: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        server.post("mark", buildJsonObject {
            put("title", JsonPrimitive(title))
            if (watched != null) put("watched", JsonPrimitive(watched))
            if (rate) put("rating", rating?.let(::JsonPrimitive) ?: JsonNull)
        }) {}.isSuccess
    }
}

/** `/api/app/copies`: a title's copies, its original language (ISO 639-1, "" unknown), each source's state ("ok", "pending", "unavailable") and an episode's [intro] ([start, end] ms, empty unknown). */
@Serializable
data class CopiesAnswer(val titleId: String = "", val originalLanguage: String = "", val runtime: Int = 0, val sources: CopySources = CopySources(), val copies: List<ServerCopy> = emptyList(), val intro: List<Long> = emptyList())

/** `/api/app/subtitles`: [status] "ok", "none" or "quota" (no subtitles today); [subs] best first, English before Arabic. */
@Serializable
data class SubtitlesAnswer(val status: String = "none", val subs: List<FoundSubtitle> = emptyList())

/** A subtitle file the box found: [lang] "en" or "ar"; [hash] when it was made for this very file. */
@Serializable
data class FoundSubtitle(val id: String, val lang: String, val hash: Boolean = false, val release: String = "")

@Serializable
data class CopySources(val iptv: String = "ok", val rd: String = "ok")

/**
 * One copy as the box describes it (U3): an IPTV stream ([id], [ext]) or a Real-Debrid candidate ([hash], [file],
 * [filename], [size], [cached] for Torrentio's [RD+]); attributes are the box's names, 0 or empty when unknown.
 */
@Serializable
data class ServerCopy(
    val src: String, val id: Int = 0, val ext: String = "mkv", val name: String = "",
    val hash: String = "", val seeds: Int = 0, val file: Int? = null, val filename: String? = null, val size: Long = 0, val cached: Boolean = false,
    val res: Int = 0, val hdr: List<String> = emptyList(), val dvProfile: Int? = null, val codec: String = "", val audio: String = "",
    val ch: Int = 0, val atmos: Boolean = false, val langs: List<String> = emptyList(), val subs: List<String> = emptyList(), val kbps: Double = 0.0,
)
