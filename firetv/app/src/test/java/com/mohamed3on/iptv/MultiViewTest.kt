package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.data.Route
import com.mohamed3on.iptv.player.MultiView
import com.mohamed3on.iptv.player.StreamPlayer
import com.mohamed3on.iptv.player.StreamSession
import com.mohamed3on.iptv.player.TilePlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a player was last told to open, and how loud it is. */
private class FakeTilePlayer : TilePlayer {
    var url: String? = null
    var heard = 1f
    override val position = 0L
    override val bytesLoaded = 0L
    override fun open(url: String) {
        this.url = url
    }
    override fun stop() {
        url = null
    }
    override fun setListener(listener: StreamPlayer.Listener?) {}
    override fun setVolume(volume: Float) {
        heard = volume
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MultiViewTest {
    // Family "a": its best feed is 4K, then an HD one probed at 1080 (listed in another group).
    private val a4k = Channel(id = 1, name = "A 4K", group = "G", family = "a", rank = 0, q = 5)
    private val aHd = Channel(id = 2, name = "A HD", group = "H", family = "a", rank = 1, h = 1080, q = 3)
    private val b = Channel(id = 3, name = "B", group = "G", family = "b", rank = 0)
    private val c = Channel(id = 4, name = "C", group = "G", family = "c", rank = 0)
    private val d = Channel(id = 5, name = "D", group = "G", family = "d", rank = 0)
    private val feed = Feed(listOf("G", "H"), ProviderRoute("http://plain.test", "http://vpn.test", "u", "p"), listOf(a4k, aHd, b, c, d))

    private class Rig(val tiles: MultiView<FakeTilePlayer>, val main: StreamSession, val players: List<FakeTilePlayer>, val released: List<FakeTilePlayer>)

    private fun TestScope.rig(): Rig {
        val route = Route(MapStore(), MutableStateFlow(false), backgroundScope)
        val players = mutableListOf<FakeTilePlayer>()
        val released = mutableListOf<FakeTilePlayer>()
        val tiles = MultiView({ FakeTilePlayer().also(players::add) }, released::add, route, backgroundScope) { testScheduler.currentTime }
        val main = StreamSession(FakeTilePlayer(), route, backgroundScope) { testScheduler.currentTime }
        return Rig(tiles, main, players, released)
    }

    @Test fun tilesPlayAFeedBelow4KOnlyTheSelectedIsHeardAndTheCapRefusesMore() = runTest {
        val r = rig()
        r.main.zap(a4k, feed)
        r.tiles.enter(r.main, b, feed)
        runCurrent()
        assertEquals("http://plain.test/live/u/p/2.ts", r.players[0].url)
        assertEquals(listOf(1f, 0f), r.players.map { it.heard })

        assertTrue(r.tiles.add(c, feed))
        assertFalse(r.tiles.add(d, feed))
        assertEquals(3, r.players.size)
        r.tiles.select(2)
        assertEquals(listOf(0f, 0f, 1f), r.players.map { it.heard })
    }

    @Test fun removingDownToOneTilePutsItFullScreenOnItsBestFeedAndReleasesEveryPlayer() = runTest {
        val r = rig()
        r.main.zap(aHd, feed)
        r.tiles.enter(r.main, b, feed)
        r.tiles.select(1)
        r.tiles.remove(r.main, feed)
        runCurrent()
        assertTrue(r.tiles.state.value.tiles.isEmpty())
        assertEquals(r.players.toSet(), r.released.toSet())
        assertEquals(a4k, r.main.state.value.requested)
    }
}
