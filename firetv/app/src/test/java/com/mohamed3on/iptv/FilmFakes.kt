package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Debrid
import com.mohamed3on.iptv.data.DebridCandidate
import com.mohamed3on.iptv.data.DebridCheck
import com.mohamed3on.iptv.data.DebridStatus
import com.mohamed3on.iptv.player.Bandwidth
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.player.FilmPlayer
import com.mohamed3on.iptv.player.Place
import com.mohamed3on.iptv.player.StreamFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Opens are recorded; each answers asynchronously, like ExoPlayer: [respond]'s failure, else the first frame. */
internal class FakeFilm(private val scope: CoroutineScope, private val clock: () -> Long) : FilmPlayer {
    val opens = mutableListOf<Pair<String, Place>>()
    var respond: (String) -> StreamFailure? = { null }
    override var duration = 120 * 60_000L
    private var listener: FilmPlayer.Listener? = null
    private var base = Place(0)
    private var since: Long? = null
    private var gen = 0

    override val place get() = since?.let { base.copy(position = base.position + clock() - it) } ?: base

    override fun open(url: String, place: Place) {
        stop()
        base = place
        opens += url to place
        val g = gen
        scope.launch {
            if (g != gen) return@launch
            val failure = respond(url)
            if (failure != null) listener!!.onError(failure) else {
                since = clock()
                listener!!.onPlaying()
            }
        }
    }

    override fun stop() {
        pause()
        gen++
    }

    override fun pause() {
        base = place
        since = null
    }

    override fun play() {
        if (since == null) since = clock()
    }

    override fun seek(position: Long) {
        base = place.copy(position = position)
        if (since != null) since = clock()
    }

    override fun setListener(listener: FilmPlayer.Listener?) {
        this.listener = listener
    }

    fun rebuffer() = listener!!.onRebuffer()
    fun end() = listener!!.onEnded()
}

/** The debrid has the torrents [ready] names (every one by default); links count up so a fresh one is visible. */
internal class FakeDebrid(private val ready: (String) -> Boolean = { true }) : Debrid {
    override val status = MutableStateFlow(DebridStatus("Fake"))
    var links = 0
    var checks = 0
    override suspend fun check(candidates: List<DebridCandidate>): Map<DebridCandidate, DebridCheck> {
        checks++
        return candidates.associateWith { if (ready(it.hash)) DebridCheck.Ready(it.hash, "hoster/${it.hash}") else DebridCheck.NotCached }
    }
    override suspend fun link(ready: DebridCheck.Ready) = "https://rd.test/${ready.torrentId}/${++links}"
}

internal class FakeBandwidth : Bandwidth {
    override fun typicalMbps(source: Capacity.Source, at: Long): Float? = null
    override var sessionMbps: Float? = null
}
