package com.mohamed3on.iptv.player

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.plus

/** Most tiles at once: on the stick 3 played at 50fps with no dropped frames; 4 starved the line at peak (U1). */
const val TILE_CAP = 3

/**
 * The feed a tile plays (R19, KTD6): the best-ranked family member probed at 1080 or less, else
 * one whose name marker is below 4K, else the top member. Several 4K decodes won't fit the stick.
 */
fun tileFeed(feed: Feed, channel: Channel): Channel {
    val members = feed.familyMembers(channel.family).ifEmpty { listOf(channel) }
    return members.firstOrNull { it.h != null && it.h <= 1080 } ?: members.firstOrNull { it.pic.q < 5 } ?: members.first()
}

/** Full screen plays the channel's best feed: the picked row when it is the best, so a 🏆 pick keeps its group. */
private fun bestFeed(feed: Feed, channel: Channel): Channel =
    channel.takeIf { it.rank == 0 } ?: feed.familyMembers(channel.family).firstOrNull() ?: channel

/** One tile: the [channel] as picked (what the caption names); its session plays [tileFeed] of it. */
data class Tile<P : TilePlayer>(val channel: Channel, val player: P, val session: StreamSession, private val job: Job) {
    internal fun close(release: (P) -> Unit) {
        session.stop()
        job.cancel()
        release(player)
    }
}

/** No tiles = full screen. Only the [selected] tile is heard. */
data class MultiViewState<P : TilePlayer>(val tiles: List<Tile<P>> = emptyList(), val selected: Int = 0)

/**
 * Multi-view (R17, R18, R19): up to [TILE_CAP] tiles, each its own pooled player and
 * [StreamSession], so one tile going busy or offline leaves the others playing. While it is open
 * the full-screen session is stopped; leaving puts the selected tile back on it. Main thread only;
 * each tile's session runs in its own child of [scope], cancelled with the tile.
 */
class MultiView<P : TilePlayer>(
    private val acquire: () -> P,
    private val release: (P) -> Unit,
    private val route: Route,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow(MultiViewState<P>())
    val state: StateFlow<MultiViewState<P>> = _state

    /** From full screen: [main]'s channel becomes tile 1 (keeping the sound) and [second] tile 2. */
    fun enter(main: StreamSession, second: Channel, feed: Feed) {
        val first = main.state.value.requested ?: return
        main.stop()
        show(listOf(open(first, feed), open(second, feed)), 0)
    }

    /** Adds a tile; refused (false, nothing opened) at [TILE_CAP]. */
    fun add(channel: Channel, feed: Feed): Boolean {
        val s = _state.value
        if (s.tiles.size >= TILE_CAP) return false
        show(s.tiles + open(channel, feed), s.selected)
        return true
    }

    /** Tunes the selected tile to [channel] on the same player; the other tiles are untouched. */
    fun replace(channel: Channel, feed: Feed) {
        val s = _state.value
        val tile = s.tiles.getOrNull(s.selected) ?: return
        tile.session.zap(tileFeed(feed, channel), feed)
        show(s.tiles.toMutableList().also { it[s.selected] = tile.copy(channel = channel) }, s.selected)
    }

    /** Removes the selected tile; when one is left, it goes full screen on [main]. */
    fun remove(main: StreamSession, feed: Feed) {
        val s = _state.value
        val tile = s.tiles.getOrNull(s.selected) ?: return
        tile.close(release)
        val rest = s.tiles - tile
        if (rest.size > 1) return show(rest, s.selected.coerceAtMost(rest.size - 1))
        _state.value = MultiViewState(rest)
        promote(main, feed)
    }

    fun select(index: Int) {
        val s = _state.value
        if (index in s.tiles.indices) show(s.tiles, index)
    }

    /** OK and Back (R18, AE7): the selected tile's channel plays full screen on [main] at its best feed; every tile stops. */
    fun promote(main: StreamSession, feed: Feed) {
        val s = _state.value
        val tile = s.tiles.getOrNull(s.selected) ?: return
        close()
        main.zap(bestFeed(feed, tile.channel), feed)
    }

    /** Releases every tile, with nothing put on full screen (the app stopping, the idle stop). */
    fun close() {
        _state.value.tiles.forEach { it.close(release) }
        _state.value = MultiViewState()
    }

    private fun show(tiles: List<Tile<P>>, selected: Int) {
        tiles.forEachIndexed { i, t -> t.player.setVolume(if (i == selected) 1f else 0f) }
        _state.value = MultiViewState(tiles, selected)
    }

    private fun open(channel: Channel, feed: Feed): Tile<P> {
        val player = acquire()
        val job = Job(scope.coroutineContext[Job])
        val session = StreamSession(player, route, scope + job, now)
        session.zap(tileFeed(feed, channel), feed)
        return Tile(channel, player, session, job)
    }
}
