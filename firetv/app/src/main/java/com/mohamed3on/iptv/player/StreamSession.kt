package com.mohamed3on.iptv.player

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.isBackup
import com.mohamed3on.iptv.data.Route
import com.mohamed3on.iptv.data.RouteChoice
import com.mohamed3on.iptv.data.RouteKind
import com.mohamed3on.iptv.data.streamUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The slice of a player a session drives; ExoPlayer in the app, a fake in tests. Main thread only. */
interface StreamPlayer {
    /** Stops whatever is open (closing its connection), then opens and plays [url]. */
    fun open(url: String)

    /** Stops and closes the connection. */
    fun stop()

    /** Playback position in ms; it stops advancing when data stops arriving. */
    val position: Long

    /** Bytes of stream received so far, across opens; it stops growing when data stops arriving. */
    val bytesLoaded: Long

    fun setListener(listener: Listener?)

    interface Listener {
        fun onPlaying()
        fun onEnded()
        fun onError(failure: StreamFailure)
    }
}

sealed interface Phase {
    data object Stopped : Phase
    data object Connecting : Phase
    data object Playing : Phase
    /** Another device holds the account's connection; retrying every 5s. */
    data object Busy : Phase
    /** Still busy after 10 min; [StreamSession.retry] starts over. */
    data object GaveUp : Phase
    /** The provider says the route is wrong for this network; [StreamSession.switchRoute] moves to [switchTo]. */
    data class WrongRoute(val switchTo: RouteKind) : Phase
    /** Every feed of the channel's family failed; [StreamSession.retry] starts over. */
    data object Offline : Phase
}

/** [requested] updates on every zap; [feed] is the family member actually tuned. */
data class SessionState(val requested: Channel? = null, val feed: Channel? = null, val phase: Phase = Phase.Stopped)

/**
 * One player's tune-and-recover state machine (KTD8, KTD9): debounced zaps, a stall watchdog,
 * busy / backoff / wrong-route retries and family failover (R20). Everything pending (a debounce,
 * a retry timer, the watchdog) is one job, cancelled on every zap, so nothing outlives it (R12).
 * Call from the main thread; [scope] must run there too.
 */
class StreamSession(
    private val player: StreamPlayer,
    private val route: Route,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state

    private var feed: Feed? = null
    private var members: List<Channel> = emptyList()
    private val tried = mutableSetOf<Int>()
    /** Feeds that failed this tune: never moved to by [switchFeed]. */
    private val failedIds = mutableSetOf<Int>()
    /** When each family member last stalled this tune, so a stall never hands back to a feed that just stalled. */
    private val stalledAt = mutableMapOf<Int, Long>()
    private var current: Channel? = null
    /** Whether an open attempt exists whose player events still count. */
    private var attempt = false
    private var attemptKind = RouteKind.Plain
    private var work: Job? = null
    private var lastZapAt: Long? = null
    private var busySince: Long? = null
    private var backoffs = 0
    private var routeKind = route.effective.value
    private var routeChangedAt: Long? = null
    private var quietRetryFor: Long? = null
    private var lastPosition = 0L
    private var progressAt = 0L

    init {
        player.setListener(object : StreamPlayer.Listener {
            override fun onPlaying() = playing()
            override fun onEnded() {
                if (attempt) if (_state.value.phase == Phase.Playing) stalled() else failed(StreamFailure.Unplayable)
            }
            override fun onError(failure: StreamFailure) {
                if (attempt) failed(failure)
            }
        })
        scope.launch {
            route.effective.collect { if (it != routeKind) routeChanged(it) }
        }
    }

    /**
     * Tunes [channel] at once unless another zap came within [DEBOUNCE_MS]; during a burst only
     * the channel the user rests on is tuned (KTD9). [SessionState.requested] updates immediately.
     */
    fun zap(channel: Channel, feed: Feed) {
        val t = now()
        val burst = lastZapAt?.let { t - it < DEBOUNCE_MS } == true
        lastZapAt = t
        cancelWork()
        attempt = false
        this.feed = feed
        _state.update { it.copy(requested = channel) }
        if (burst) later(DEBOUNCE_MS) { tune(channel, feed) } else tune(channel, feed)
    }

    /** Starts the requested channel over: the retry action for Offline and GaveUp. */
    fun retry() {
        val channel = _state.value.requested ?: return
        cancelWork()
        tune(channel, feed ?: return)
    }

    /** The one-press wrong-route switch: overrides the route for this session and retunes. */
    fun switchRoute() {
        val phase = _state.value.phase as? Phase.WrongRoute ?: return
        switchTo(phase.switchTo)
    }

    /** Retunes the requested channel on [kind] after a route choice (R4); nothing if unchanged or stopped. */
    fun useRoute(kind: RouteKind) {
        if (kind == routeKind || _state.value.phase == Phase.Stopped) return
        routeChanged(kind)
        retry()
    }

    /** The feeds of the channel being played, best first. */
    val family: List<Channel> get() = members

    /** Whether [member] can be moved to: it hasn't failed this tune nor stalled in the last [FLAP_MS]. */
    fun usable(member: Channel): Boolean = member.id !in failedIds && stalledAt[member.id]?.let { now() - it < FLAP_MS } != true

    /**
     * Moves a playing channel to another feed of its family ([Downshift]), on the one provider connection:
     * [open] stops the old stream first. Not a failure, so nothing counts against either feed.
     */
    fun switchFeed(member: Channel) {
        if (_state.value.phase != Phase.Playing || member.id == current?.id || !usable(member)) return
        open(member)
    }

    /** Stops playback and every pending retry; the provider connection is closed. */
    fun stop() {
        cancelWork()
        attempt = false
        player.stop()
        setPhase(Phase.Stopped)
    }

    private fun tune(channel: Channel, feed: Feed) {
        members = feed.familyMembers(channel.family)
        tried.clear()
        failedIds.clear()
        stalledAt.clear()
        busySince = null
        backoffs = 0
        open(channel)
    }

    private fun open(member: Channel, phase: Phase = Phase.Connecting) {
        cancelWork()
        current = member
        tried += member.id
        attempt = true
        attemptKind = routeKind
        _state.update { it.copy(feed = member, phase = phase) }
        player.open(feed!!.streamUrl(routeKind, member))
        lastPosition = player.position
        progressAt = now()
        work = scope.launch { watch() }
    }

    /**
     * A stall is a position that stops advancing while no data arrives either (buffering a bigger
     * cushion freezes the picture for longer, #3), or for [STALL_MAX_MS] however much trickles in;
     * before the first frame, a start that takes too long.
     */
    private suspend fun watch() {
        var lastBytes = player.bytesLoaded
        var dataAt = progressAt
        while (true) {
            delay(TICK_MS)
            val t = now()
            val position = player.position
            if (position != lastPosition) {
                lastPosition = position
                progressAt = t
            }
            val bytes = player.bytesLoaded
            if (bytes != lastBytes) {
                lastBytes = bytes
                dataAt = t
            }
            val playing = _state.value.phase == Phase.Playing
            if (playing && (t - maxOf(progressAt, dataAt) >= STALL_MS || t - progressAt >= STALL_MAX_MS)) {
                stalled()
                return
            }
            if (!playing && t - progressAt >= START_MS) {
                failed(StreamFailure.Timeout)
                return
            }
        }
    }

    private fun playing() {
        if (!attempt) return
        tried.clear()
        tried += current!!.id
        busySince = null
        backoffs = 0
        lastPosition = player.position
        progressAt = now()
        setPhase(Phase.Playing)
    }

    /**
     * A playing feed stalled or ended (live never ends): hand off to a feed that has not stalled in
     * the last [FLAP_MS], else re-prepare this one, whose cushion then grows (#3). Ping-ponging
     * between two stalling feeds reset the cushion on every hop and never settled.
     */
    private fun stalled() {
        val t = now()
        stalledAt[current!!.id] = t
        open(members.firstOrNull { it.id !in tried && stalledAt[it.id]?.let { at -> t - at < FLAP_MS } != true } ?: current!!)
    }

    private fun failed(failure: StreamFailure) {
        failedIds += current!!.id
        attempt = false
        cancelWork()
        player.stop()
        when (StreamErrors.recovery(failure)) {
            // The connection limit is per account: a feed on the other provider's plays meanwhile.
            Recovery.Busy -> members.firstOrNull { it.id !in tried && it.isBackup != current!!.isBackup }?.let { open(it) } ?: busy()
            Recovery.WrongRoute -> wrongRoute()
            Recovery.NextFeed -> nextMember()?.let { open(it) } ?: setPhase(Phase.Offline)
            Recovery.Backoff -> {
                setPhase(Phase.Connecting)
                later(StreamErrors.backoff(backoffs++).inWholeMilliseconds) { open(current!!) }
            }
        }
    }

    private fun busy() {
        val t = now()
        val since = busySince
        val changed = routeChangedAt
        when {
            // The panel takes ~5s to free the slot after a route or IP change: our old connection may still hold it.
            since == null && changed != null && t - changed < QUIET_MS && quietRetryFor != changed -> {
                quietRetryFor = changed
                later(QUIET_MS) { open(current!!) }
            }
            since != null && t - since >= GIVE_UP_MS -> setPhase(Phase.GaveUp)
            else -> {
                if (since == null) busySince = t
                setPhase(Phase.Busy)
                later(BUSY_RETRY_MS) { open(current!!, Phase.Busy) }
            }
        }
    }

    private fun wrongRoute() {
        val other = if (attemptKind == RouteKind.Vpn) RouteKind.Plain else RouteKind.Vpn
        // With no manual choice the automatic route was stale, so switch once by ourselves. That
        // sets a session override, so a second wrong route prompts instead.
        if (route.choice.value == RouteChoice.Auto && route.override.value == null) switchTo(other)
        else setPhase(Phase.WrongRoute(other))
    }

    private fun switchTo(kind: RouteKind) {
        route.overrideForSession(kind)
        routeChanged(kind) // Route.effective catches up asynchronously; tune on the new route now
        open(current!!)
    }

    private fun routeChanged(kind: RouteKind) {
        routeKind = kind
        routeChangedAt = now()
    }

    private fun nextMember() = members.firstOrNull { it.id !in tried }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase) }

    private fun later(ms: Long, block: () -> Unit) {
        work = scope.launch {
            delay(ms)
            block()
        }
    }

    private fun cancelWork() {
        work?.cancel()
        work = null
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val TICK_MS = 1_000L
        const val STALL_MS = 5_000L
        /** Three times the biggest cushion: a line far too slow for the feed still fails over. */
        const val STALL_MAX_MS = 3 * Cushion.MAX_MS
        /** Covers OkHttp's 10s connect timeout plus the first keyframe. */
        const val START_MS = 10_000L
        const val BUSY_RETRY_MS = 5_000L
        const val GIVE_UP_MS = 10 * 60_000L
        const val QUIET_MS = 5_000L
        const val FLAP_MS = 10 * 60_000L
    }
}
