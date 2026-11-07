package com.mohamed3on.iptv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Trakt's sign-in as the box reports it (GET /api/app/trakt): off, waiting (with [code] to enter at [url]),
 * expired, denied, on (as [user]) or reconnect.
 */
@Serializable
data class TraktStatus(val state: String = "off", val code: String = "", val url: String = "", val user: String = "")

/** A finished play waiting for the box: [at] is when it finished (epoch ms), the time Trakt records. */
@Serializable
data class FinishedPlay(val title: TitleId, val at: Long)

/**
 * Trakt through the box (KTD19): the Options sign-in, and films and episodes finished here (past 90%, once a play) logged
 * to Trakt history. Finished plays are kept in [store] until the box has logged them (Trakt's duplicate counts),
 * so a play finished offline, or before Trakt is signed in, goes later. Sends run on [scope], off the player.
 */
class TraktSync(
    private val server: SofaServer,
    private val store: KeyValueStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<TraktStatus?>(null)
    /** The last sign-in state the box gave; null until it answers. */
    val status: StateFlow<TraktStatus?> = _status
    private val sending = Mutex()

    init {
        scope.launch {
            while (true) {
                delay(RETRY_MS)
                flush()
            }
        }
    }

    /** Queues [title] as finished now and sends it; only TMDB titles, the ones Trakt knows. */
    fun finished(title: TitleId) {
        if (!LOGGABLE.matches(title)) return
        synchronized(this) { save((pending() + FinishedPlay(title, now())).takeLast(CAP)) }
        scope.launch { flush() }
    }

    /** Sends the queued plays in order; stops at the first the box can't take now (offline, Trakt not signed in). */
    suspend fun flush() = sending.withLock {
        withContext(Dispatchers.IO) {
            for (p in pending()) {
                val sent = server.post("trakt-history", buildJsonObject { put("title", p.title); put("at", p.at) }) {}
                // Logged, or refused for good (400): either way it leaves the queue.
                if (sent.isFailure && sent.exceptionOrNull() != SyncError.Rejected(400)) return@withContext
                synchronized(this@TraktSync) { save(pending() - p) }
            }
        }
    }

    /** Asks the box for the sign-in state. */
    suspend fun refresh() = withContext(Dispatchers.IO) { server.get("trakt", ::status).onSuccess { _status.value = it } }

    /** Connects (a new code), cancels the code or disconnects ([action]); the answer is the new state. */
    suspend fun act(action: String) = withContext(Dispatchers.IO) {
        server.post("trakt", buildJsonObject { put("action", action) }, ::status).onSuccess { _status.value = it }
        // Signed in: whatever finished meanwhile goes now.
        if (_status.value?.state == "on") flush()
    }

    private fun status(body: okhttp3.ResponseBody) = json.decodeFromString<TraktStatus>(body.string())

    private fun pending(): List<FinishedPlay> =
        store.get(KEY)?.let { runCatching { json.decodeFromString<List<FinishedPlay>>(it) }.getOrNull() } ?: emptyList()

    private fun save(plays: List<FinishedPlay>) = store.put(KEY, if (plays.isEmpty()) null else json.encodeToString(plays))

    companion object {
        private const val KEY = "trakt.finished"
        private const val CAP = 500
        private const val RETRY_MS = 10 * 60_000L
        private val LOGGABLE = Regex("""movie:\d+|tv:\d+:s\d+e\d+""")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
