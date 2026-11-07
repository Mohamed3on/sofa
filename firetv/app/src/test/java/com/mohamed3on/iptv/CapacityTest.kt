package com.mohamed3on.iptv

import com.mohamed3on.iptv.player.BandwidthMeter
import com.mohamed3on.iptv.player.Burst
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.player.Capacity.Source.Iptv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class CapacityTest {
    private val evening = 20 * 3_600_000L // 20:00 UTC
    private val day = 12 * 3_600_000L
    private var vpnOn = false
    private val capacity = Capacity(MapStore(), ZoneOffset.UTC) { vpnOn }

    /** Readings once a second: [seconds] filling at [mbps], then one idle second. */
    private fun BandwidthMeter.burst(from: Long, seconds: Int, mbps: Int, bytes: Long = 0): Long {
        var b = bytes
        for (s in 0..seconds) {
            next(from + s * 1_000L, b, true, Iptv)
            b += mbps * 125_000L
        }
        next(from + (seconds + 1) * 1_000L, b, false, Iptv)
        return b
    }

    @Test
    fun typicalIsTheMedianOfTheBandsSessionsKeptApartByVpn() {
        listOf(30f, 35f, 40f, 80f, 90f).forEach { capacity.record(listOf(Burst(evening, Iptv, it))) }
        assertEquals(40f, capacity.typicalMbps(Iptv, evening)!!, 0.01f)
        assertNull(capacity.typicalMbps(Iptv, day))
        assertNull(capacity.typicalMbps(Capacity.Source.Debrid, evening))
        vpnOn = true
        assertNull(capacity.typicalMbps(Iptv, evening)) // the VPN has its own history
        capacity.record(listOf(Burst(evening, Iptv, 11f)))
        assertEquals(11f, capacity.typicalMbps(Iptv, evening)!!, 0.01f)
        vpnOn = false
        assertEquals(40f, capacity.typicalMbps(Iptv, evening)!!, 0.01f)
    }

    @Test
    fun aSessionLeavesItsMedianBurstAndIgnoresShortOnes() {
        val meter = BandwidthMeter(capacity)
        var bytes = meter.burst(evening, 1, 5) // 1 s: ignored
        repeat(40) { bytes = meter.burst(evening + 10_000L + it * 10_000L, 3, 20 + it, bytes) }
        assertEquals(40, meter.bursts.size)
        assertEquals(58f, meter.sessionMbps!!, 0.01f) // the last three: 57, 58, 59
        meter.finish()
        assertEquals(39.5f, capacity.typicalMbps(Iptv, evening)!!, 0.01f)
    }
}
