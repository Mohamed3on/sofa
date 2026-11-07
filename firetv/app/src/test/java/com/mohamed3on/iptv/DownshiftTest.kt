package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.player.Downshift
import com.mohamed3on.iptv.player.Health
import com.mohamed3on.iptv.player.Shift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownshiftTest {
    private fun feed(id: Int, rank: Int, mbps: Float? = null, h: Int? = null) =
        Channel(id = id, name = "F$id", group = "G", family = "fam", rank = rank, mbps = mbps, h = h)

    // A 4K feed at 14 Mbps, an FHD one at 6, an unmeasured 720p one and an unmeasured 4K spare.
    private val uhd = feed(1, 0, mbps = 14f, h = 2160)
    private val spareUhd = feed(2, 1, h = 2160)
    private val fhd = feed(3, 2, mbps = 6f, h = 1080)
    private val hd = feed(4, 3, h = 720)
    private val family = listOf(uhd, spareUhd, fhd, hd)

    private val behind = Health(Health.Level.Warn, network = 8f, stream = 14f, behind = true)
    private val fine = Health(Health.Level.Good, network = 6f, stream = 6f)

    private class Clock(var t: Long = 0)

    /** [seconds] of the same reading, a second apart; the first move made, if any. */
    private fun Downshift.feed(clock: Clock, playing: Channel, h: Health, seconds: Int, usable: (Channel) -> Boolean = { true }): Shift? {
        repeat(seconds) {
            clock.t += 1_000
            next(playing, family, h, usable)?.let { return it }
        }
        return null
    }

    @Test
    fun `a line behind for 10 s steps down to the best feed it can carry, never one that failed`() {
        val clock = Clock()
        val d = Downshift { clock.t }
        assertNull(d.feed(clock, uhd, behind, 10))
        // 0.8 × 8 Mbps carries the 6 Mbps FHD feed, not the unmeasured 4K spare.
        assertEquals(Shift.Down(fhd, network = 8f, stream = 14f), d.feed(clock, uhd, behind, 1))

        // FHD failed this tune: the unmeasured 720p feed is still lighter than 4K.
        val e = Downshift { clock.t }
        assertEquals(hd, (e.feed(clock, uhd, behind, 11, usable = { it != fhd }) as Shift.Down).to)
    }

    @Test
    fun `a rebuffer on a slow line steps down at once`() {
        val clock = Clock()
        val d = Downshift { clock.t }
        clock.t = 1_000
        assertEquals(fhd, (d.rebuffered(uhd, family, network = 8f, measured = 14f) { true } as Shift.Down).to)
    }

    @Test
    fun `steps back up once the lighter feed has held, waiting twice as long after an up that fell behind`() {
        val clock = Clock()
        val d = Downshift { clock.t }
        d.feed(clock, uhd, behind, 11)
        assertNull(d.feed(clock, fhd, fine, 14 * 60))
        assertEquals(Shift.Up(uhd), d.feed(clock, fhd, fine, 61))

        // The 4K feed falls behind again: down at once (no 5-minute wait after an up), and the next up waits 30 min.
        assertEquals(fhd, (d.feed(clock, uhd, behind, 11) as Shift.Down).to)
        assertNull(d.feed(clock, fhd, fine, 29 * 60))
        assertEquals(Shift.Up(uhd), d.feed(clock, fhd, fine, 61))
    }

    @Test
    fun `at most one step down per 5 minutes, so a lighter feed that also falls behind waits`() {
        val clock = Clock()
        val d = Downshift { clock.t }
        d.feed(clock, uhd, behind, 11)
        val slower = Health(Health.Level.Warn, network = 4f, stream = 6f, behind = true)
        assertNull(d.feed(clock, fhd, slower, 4 * 60))
        assertEquals(hd, (d.feed(clock, fhd, slower, 60) as Shift.Down).to)
    }
}
