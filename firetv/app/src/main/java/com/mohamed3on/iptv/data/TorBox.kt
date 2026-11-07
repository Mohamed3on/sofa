package com.mohamed3on.iptv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable private class TbEnvelope<T>(val success: Boolean = false, val error: String? = null, val data: T? = null)
@Serializable private class TbFile(val id: Int, @SerialName("short_name") val name: String = "", val size: Long = 0)
@Serializable private class TbTorrent(
    val id: Long,
    val hash: String,
    @SerialName("download_finished") val finished: Boolean = false,
    val files: List<TbFile> = emptyList(),
)
@Serializable private class TbAdded(@SerialName("torrent_id") val id: Long)
@Serializable private class TbHash(val hash: String)
@Serializable private class TbUser(val plan: Int = 0, @SerialName("premium_expires_at") val expires: String? = null)

private val json = Json { ignoreUnknownKeys = true }

/**
 * TorBox from the stick's own IP, beside Real-Debrid in [Debrids] (copies keep their "rd:" ids, so marks
 * carry over). A check asks TorBox which torrents it holds (checkcached), then adds the best of them with
 * `add_only_if_cached`, which never leaves an uncached download in the account; it needs no Torrentio flag, so
 * TorrentsDB's copies are checked too. [DebridCheck.Ready.link] holds the TorBox file id. TorBox's file ids don't follow
 * the torrent's order, so a file is found by name. The download URL [link] returns carries the API key: never log it.
 */
class TorBox(
    private val client: OkHttpClient,
    private val token: () -> String?,
    private val base: String = "https://api.torbox.app/v1/api",
    private val now: () -> Long = System::currentTimeMillis,
    /** Where the account is read once built, off Play's path. */
    scope: CoroutineScope? = null,
) : Debrid {
    private val _status = MutableStateFlow(DebridStatus("TorBox"))
    override val status: StateFlow<DebridStatus> = _status
    private var state: DebridState
        get() = _status.value.state
        set(s) = _status.update { it.copy(state = s) }

    private val listLock = Mutex()
    private var listed: List<TbTorrent>? = null
    private var listedAt = 0L
    private val answers = ConcurrentHashMap<DebridCandidate, DebridCheck>()
    @Volatile private var notPremium = false
    @Volatile private var accountRead = false
    @Volatile private var pausedUntil = 0L

    init {
        scope?.launch { readAccount() }
    }

    /** Reads the account once per app session: the free plan or a past expiry means [DebridState.NotPremium]. */
    private suspend fun readAccount() {
        if (accountRead || token() == null) return
        runCatching { call<TbUser>(get("user/me?settings=false")) }.onSuccess {
            accountRead = true
            val expired = it.expires?.let { at -> runCatching { Instant.parse(at).toEpochMilli() < now() }.getOrNull() } == true
            if (it.plan == 0 || expired) lapsed()
        }
    }

    private fun lapsed() {
        notPremium = true
        state = DebridState.NotPremium
    }

    /** The account first, then TorBox's cache answer for the best ranked rest, adding the best held copy (at most 3 tries). */
    override suspend fun check(candidates: List<DebridCandidate>): Map<DebridCandidate, DebridCheck> {
        val out = LinkedHashMap(inAccount(candidates))
        val rest = candidates.takeWhile { it !in out }.take(ASK_MAX)
        if (rest.isEmpty() || state != DebridState.Ok) return out
        // Unknown when the cache check fails: the box's "[TB+]" flags decide, as for Real-Debrid.
        val held = runCatching { call<List<TbHash>>(get("torrents/checkcached?hash=${rest.joinToString(",") { it.hash }}&format=list")) }
            .getOrNull()?.mapTo(HashSet()) { it.hash.lowercase() }
        var tries = 0
        for (c in rest) {
            if (held != null && c.hash.lowercase() !in held) {
                out[c] = DebridCheck.NotCached
                continue
            }
            if (held == null && !c.cached) continue
            if (tries++ == 3 || state != DebridState.Ok) break
            val result = probe(c)
            out[c] = result
            if (result is DebridCheck.Ready) break
        }
        return out
    }

    private suspend fun inAccount(candidates: List<DebridCandidate>): Map<DebridCandidate, DebridCheck> {
        if (token() == null) return emptyMap<DebridCandidate, DebridCheck>().also { state = DebridState.NoToken }
        if (notPremium) return emptyMap()
        if (now() >= pausedUntil && state != DebridState.TokenInvalid) state = DebridState.Ok
        return try {
            val list = torrents()
            candidates.mapNotNull { c ->
                (answers[c] as? DebridCheck.Ready)?.let { return@mapNotNull c to it }
                list.firstOrNull { it.hash.equals(c.hash, true) && it.finished }?.let { ready(it, c) }?.let { c to it }
            }.toMap()
        } catch (e: DebridError) {
            emptyMap()
        }
    }

    private suspend fun probe(c: DebridCandidate): DebridCheck {
        if (notPremium) return DebridCheck.Failed(DebridError(false, "not_premium"))
        if (now() - listedAt >= KEEP_MS) answers.clear()
        answers[c]?.let { return it }
        if (now() < pausedUntil) return DebridCheck.Failed(DebridError(false, "too_many_requests"))
        return add(c).also { if (it !is DebridCheck.Failed) answers[c] = it }
    }

    override suspend fun link(ready: DebridCheck.Ready): String {
        if (notPremium) throw DebridError(false, "not_premium")
        val key = token() ?: throw DebridError(false, "no_token")
        return call(get("torrents/requestdl?token=$key&torrent_id=${ready.torrentId}&file_id=${ready.link}&zip_link=false"))
    }

    private suspend fun add(c: DebridCandidate): DebridCheck = try {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("magnet", "magnet:?xt=urn:btih:${c.hash.lowercase()}")
            .addFormDataPart("seed", "3") // don't seed
            .addFormDataPart("allow_zip", "false")
            .addFormDataPart("add_only_if_cached", "true")
            .build()
        val id = call<TbAdded>(Request.Builder().url("$base/torrents/createtorrent").post(body)).id
        val torrent = call<TbTorrent>(get("torrents/mylist?id=$id&bypass_cache=true"))
        listLock.withLock { listed = listed?.plus(torrent) }
        ready(torrent, c) ?: DebridCheck.NotCached
    } catch (e: DebridError) {
        if (e.code == "DOWNLOAD_NOT_CACHED") DebridCheck.NotCached else DebridCheck.Failed(e)
    }

    private fun ready(t: TbTorrent, c: DebridCandidate) = fileOf(t.files, c)?.let { DebridCheck.Ready("${t.id}", "${it.id}").also { r -> answers[c] = r } }

    /** [c]'s file by name; without one (or no name match when the torrent has one video), the largest. */
    private fun fileOf(files: List<TbFile>, c: DebridCandidate): TbFile? =
        files.firstOrNull { c.filename != null && it.name.equals(c.filename, true) }
            ?: files.maxByOrNull { it.size }?.takeIf { c.filename == null || files.count { f -> f.size > it.size / 10 } == 1 }

    /** The account's torrents, kept for [KEEP_MS] (as are the answers) and added to by probes. */
    private suspend fun torrents(): List<TbTorrent> = listLock.withLock {
        listed?.takeIf { now() - listedAt < KEEP_MS } ?: call<List<TbTorrent>>(get("torrents/mylist?bypass_cache=true")).also {
            answers.clear()
            listed = it
            listedAt = now()
        }
    }

    private fun get(path: String) = Request.Builder().url("$base/$path")

    private suspend inline fun <reified T> call(request: Request.Builder): T {
        val auth = request.header("Authorization", "Bearer ${token() ?: throw DebridError(false, "no_token")}").build()
        val (code, body, retryAfter) = try {
            withContext(Dispatchers.IO) {
                client.newCall(auth).execute().use { Triple(it.code, it.body.string(), it.header("Retry-After")?.toLongOrNull()) }
            }
        } catch (e: IOException) {
            throw DebridError(false, "network")
        }
        val reply = runCatching { json.decodeFromString<TbEnvelope<T>>(body) }.getOrNull()
        if (reply == null || code !in 200..299 || !reply.success) fail(code, reply?.error, retryAfter)
        if (now() >= pausedUntil && !notPremium) state = DebridState.Ok
        return reply.data ?: if (T::class == List::class) emptyList<Any>() as T else throw DebridError(false, "malformed")
    }

    private fun fail(code: Int, error: String?, retryAfter: Long?): Nothing {
        val refusesAdds = { s: DebridState -> state = s; pausedUntil = now() + (retryAfter ?: 60) * 1000 }
        when {
            error == "DOWNLOAD_NOT_CACHED" -> {}
            code == 401 || error in setOf("NO_AUTH", "BAD_TOKEN", "AUTH_ERROR") -> state = DebridState.TokenInvalid
            error == "PLAN_RESTRICTED_FEATURE" -> lapsed()
            code == 429 || error == "COOLDOWN_LIMIT" -> refusesAdds(DebridState.RateLimited)
            error == "ACTIVE_LIMIT" -> refusesAdds(DebridState.TooManyDownloads)
            code == 403 || error == "MONTHLY_LIMIT" -> state = DebridState.Denied
        }
        throw DebridError(false, error ?: "http_$code")
    }

    private companion object {
        const val KEEP_MS = 15 * 60_000L
        /** Hashes in one cache check (they go in its URL); a ready copy is nearly always among the best ranked. */
        const val ASK_MAX = 60
    }
}
