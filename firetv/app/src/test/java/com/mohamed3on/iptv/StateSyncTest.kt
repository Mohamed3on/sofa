package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.StateEntry
import com.mohamed3on.iptv.data.StateSync
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StateSyncTest {
    /** The box: each key's newest write wins (lib/app-state.ts). */
    private val box = mutableMapOf<String, StateEntry>()
    private var online = true
    private var clock = 1_000L

    private fun device(scope: TestScope, local: MapStore = MapStore()) =
        StateSync(local, { local.map.keys }, scope, now = { clock++ }) { put ->
            if (!online) return@StateSync null
            put.forEach { (k, e) -> if ((box[k]?.at ?: -1) < e.at) box[k] = e }
            box.toMap()
        }

    @Test
    fun `the TV's state seeds the box, the stick merges its own in once, and later changes go both ways`() = runTest {
        val tv = device(this, MapStore(mutableMapOf(
            "lineup.favourites" to "bbc", "capacity" to "tv-only",
            "titles.movies.resume" to """[{"title":"movie:1","pos":5,"at":20},{"title":"movie:2","pos":9,"at":20}]""",
        )))
        val stick = device(this, MapStore(mutableMapOf(
            "lineup.favourites" to "old\nbbc", "capacity" to "stick-only",
            "titles.movies.resume" to """[{"title":"movie:2","pos":7,"at":10},{"title":"movie:3","pos":1,"at":30}]""",
        )))
        tv.sync()
        stick.sync()
        // The stick's favourites and places join the TV's, the newer place of a film winning, and go back to the TV.
        assertEquals("bbc\nold", stick.get("lineup.favourites"))
        assertEquals("""[{"title":"movie:3","pos":1,"at":30},{"title":"movie:1","pos":5,"at":20},{"title":"movie:2","pos":9,"at":20}]""", stick.get("titles.movies.resume"))
        advanceUntilIdle()
        tv.sync()
        assertEquals("bbc\nold", tv.get("lineup.favourites"))
        // What belongs to one device stays on it.
        assertEquals("stick-only", stick.get("capacity"))
        assertNull(box["capacity"])

        stick.put("lineup.favourites", "bbc\nsky")
        stick.put("lineup.order.UK", "sky\nbbc")
        stick.sync()
        tv.sync()
        assertEquals("bbc\nsky", tv.get("lineup.favourites"))
        assertEquals("sky\nbbc", tv.get("lineup.order.UK"))

        // A removal made offline goes up once the box is back.
        online = false
        tv.put("lineup.order.UK", null)
        tv.sync()
        online = true
        tv.sync()
        stick.sync()
        assertNull(stick.get("lineup.order.UK"))
    }
}
