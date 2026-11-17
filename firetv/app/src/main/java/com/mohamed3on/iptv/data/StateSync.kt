package com.mohamed3on.iptv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/** A shared key's value as written, with when (epoch ms); a null value is a removal. */
@Serializable
data class StateEntry(val v: String?, val at: Long)

/**
 * [local] with the owner's own state shared by every device through the box ([exchange]: POST /api/app/state, null when it
 * fails): favourites, channel and row order, resume points, followed shows, Up next dismissals, recent searches, copy pins
 * and languages. Each key's newest write wins. A change goes up a few seconds after it is made; the box's newer ones come
 * down with it and whenever [sync] runs (the app coming to the front), and [changed] then names the keys rewritten, for the
 * stores holding them to read again. What belongs to one device (bandwidth, route, what its screen plays, its copy marks)
 * stays on it. On the first run everything already here goes up, behind whatever the box already has.
 */
class StateSync(
    private val local: KeyValueStore,
    existing: () -> Set<String>,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val exchange: (Map<String, StateEntry>) -> Map<String, StateEntry>?,
) : KeyValueStore {
    /** Each shared key's write here or taken from the box (a key never synced has none), and the keys still to send. */
    @Serializable
    private data class State(val at: Map<String, Long> = emptyMap(), val dirty: Set<String> = emptySet())

    private var state = local.get(STATE)?.let { runCatching { json.decodeFromString<State>(it) }.getOrNull() }
        ?: State(dirty = existing().filter(::shared).toSet())
    private val _changed = MutableSharedFlow<Set<String>>(extraBufferCapacity = 8)
    val changed: SharedFlow<Set<String>> = _changed
    private val running = Mutex()
    private var pending: Job? = null

    override fun get(key: String) = local.get(key)

    override fun put(key: String, value: String?) {
        local.put(key, value)
        if (!shared(key)) return
        synchronized(this) {
            state = State(state.at + (key to now()), state.dirty + key)
            save()
            pending?.cancel()
            pending = scope.launch { delay(PUSH_DELAY); sync() }
        }
    }

    /** Sends the keys changed here and takes the box's newer ones; a failure leaves them to the next time. */
    suspend fun sync() = running.withLock {
        val sent = synchronized(this) { state.dirty.associateWith { StateEntry(local.get(it), state.at[it] ?: 0) } }
        val box = withContext(Dispatchers.IO) { exchange(sent) } ?: return@withLock
        val newer = synchronized(this) {
            val newer = box.filter { (k, e) -> shared(k) && e.at > (state.at[k] ?: -1) }
            newer.forEach { (k, e) -> local.put(k, e.v) }
            // A key changed again while this was on its way stays to send.
            state = State(state.at + newer.mapValues { it.value.at }, state.dirty.filterNot { it in newer || sent[it]?.at == (state.at[it] ?: 0) }.toSet())
            save()
            newer.keys
        }
        if (newer.isNotEmpty()) _changed.emit(newer)
    }

    private fun save() = local.put(STATE, json.encodeToString(state))

    companion object {
        private const val STATE = "sync.state"
        private val PUSH_DELAY = 3.seconds
        private val json = Json { ignoreUnknownKeys = true }
        private val SHARED = setOf(
            "lineup.favourites", "movies.rowOrder", "movies.hiddenRows", "movies.search.recent",
            "titles.movies.resume", "titles.shows.resume", "titles.shows.followed", "titles.upnext.dismissed",
            "copies.pins", "copies.languages", "copies.lacking.v2", "copies.dubFirst",
        )

        fun shared(key: String) = key in SHARED || key.startsWith("lineup.order.")
    }
}
