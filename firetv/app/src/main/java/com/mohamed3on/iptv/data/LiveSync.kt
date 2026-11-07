package com.mohamed3on.iptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.time.Duration

/** The channel feed as the UI sees it. Failed only happens with no cached feed (first run). */
sealed interface FeedState {
    data object Loading : FeedState
    data class Failed(val error: SyncError) : FeedState
    data class Ready(val feed: Feed) : FeedState
}

/** Keeps the channels (feed.json) and guide (guide.db) in [dir] current from the box. */
class LiveSync(
    server: SofaServer,
    private val dir: File,
    store: KeyValueStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val files = BoxFiles(server, store, now)
    private val feedFile = File(dir, "feed.json")
    private val guideFile = File(dir, "guide.db")
    private val channelsLock = Mutex()
    private val guideLock = Mutex()

    private val _state = MutableStateFlow<FeedState>(FeedState.Loading)
    val state: StateFlow<FeedState> = _state
    private val _guide = MutableStateFlow<GuideDb?>(null)
    /** The open cached guide; replaced (and the old one closed) after each validated download. */
    val guide: StateFlow<GuideDb?> = _guide
    /** The Real-Debrid token from the feed, for [RealDebrid]; null until a feed is loaded or when the server has none. */
    val realDebridToken: String? get() = (_state.value as? FeedState.Ready)?.feed?.realDebrid
    /** The TorBox token from the feed, for [TorBox]; null until a feed is loaded or when the server has none. */
    val torboxToken: String? get() = (_state.value as? FeedState.Ready)?.feed?.torbox

    /** Loads whatever is cached, without touching the network. */
    suspend fun loadCached() = withContext(Dispatchers.IO) {
        if (_state.value !is FeedState.Ready) {
            runCatching { parseFeed(feedFile.readText()) }.onSuccess { _state.value = FeedState.Ready(it) }
        }
        if (_guide.value == null && guideFile.exists()) {
            runCatching { GuideDb.open(guideFile) }.onSuccess { _guide.value = it }
        }
    }

    /** Syncs each file older than its max age; ZERO forces a fetch. */
    suspend fun refresh(channelsMaxAge: Duration, guideMaxAge: Duration) {
        syncChannels(channelsMaxAge)
        syncGuide(guideMaxAge)
    }

    /** The first-run Retry button. */
    suspend fun retry() {
        if (_state.value !is FeedState.Ready) _state.value = FeedState.Loading
        refresh(Duration.ZERO, Duration.ZERO)
    }

    /** Returns null when the feed is current (fetched, unchanged, or younger than [maxAge]). */
    suspend fun syncChannels(maxAge: Duration = Duration.ZERO): SyncError? = channelsLock.withLock {
        withContext(Dispatchers.IO) {
            files.sync("channels", feedFile, maxAge) { tmp, keep ->
                val feed = parseFeed(tmp.readText())
                keep(tmp)
                _state.value = FeedState.Ready(feed)
            }.also { if (it != null && _state.value !is FeedState.Ready) _state.value = FeedState.Failed(it) }
        }
    }

    /** Returns null when the guide is current (fetched, unchanged, or younger than [maxAge]). */
    suspend fun syncGuide(maxAge: Duration = Duration.ZERO): SyncError? = guideLock.withLock {
        withContext(Dispatchers.IO) {
            files.sync("guide", guideFile, maxAge) { gz, keep ->
                val tmp = File(dir, "guide.db.tmp")
                try {
                    GZIPInputStream(gz.inputStream()).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    GuideDb.open(tmp).close()
                    keep(tmp)
                } finally {
                    tmp.delete()
                }
                val old = _guide.value
                _guide.value = GuideDb.open(guideFile)
                old?.close()
            }
        }
    }
}
