package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Programme
import com.mohamed3on.iptv.data.fixtureOf
import com.mohamed3on.iptv.data.shownTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class FixtureTest {
    @Test
    fun `a live prefix goes, a team starting with live stays`() {
        assertEquals("Liverpool vs Arsenal", fixtureOf("Liverpool vs Arsenal"))
        assertEquals("Liverpool vs Arsenal", fixtureOf("Live: Liverpool vs Arsenal"))    }

    @Test
    fun `just the fixture from a broadcaster's title`() {
        assertEquals("Getafe CF vs Sevilla FC", fixtureOf("LALIGA EA SPORTS 2026/2027 MD11 : Getafe CF vs Sevilla FC"))
        assertEquals("Athletic Bilbao vs Real Sociedad", fixtureOf("Athletic Bilbao vs Real Sociedad - Spanish LaLiga 2026/2027 - Week 11"))
    }

    @Test
    fun `a re-run titled fixture first shows the fixture, highlights stay whole`() {
        val rerun = Programme(0, 1, "Leeds United vs Tottenham Hotspur - English Premier League 2026/2027 - Week 10", "", synthetic = false, match = false)
        assertEquals("Leeds United vs Tottenham Hotspur", shownTitle(rerun))
        assertEquals("LALIGA Match Highlights: Real Betis v Malaga", shownTitle(rerun.copy(title = "LALIGA Match Highlights: Real Betis v Malaga")))
    }
}
