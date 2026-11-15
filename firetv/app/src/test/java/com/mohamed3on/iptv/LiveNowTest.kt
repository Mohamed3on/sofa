package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Airing
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Crests
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.Picture
import com.mohamed3on.iptv.data.Programme
import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.data.LiveEntry
import com.mohamed3on.iptv.data.LiveNow
import com.mohamed3on.iptv.data.SameMatch
import com.mohamed3on.iptv.data.liveNow
import com.mohamed3on.iptv.ui.zapStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNowTest {
    private val laliga = "🏆 La Liga"
    private val ppv = "⚽ Football — Match PPV"
    private fun ch(id: Int, name: String, group: String = laliga, tvgId: String = "", slot: Boolean = false) =
        Channel(id = id, name = name, group = group, tvgId = tvgId, slot = slot, fixture = slot, family = "f$id", rank = 0)

    @Test fun eventChannelsNamedForAMatchAreListedUnderIt() {
        val bein = ch(1, "beIN Sports 1", tvgId = "b1")
        val dazn20 = ch(2, "Live | Atletico Madrid - FC Barcelona (deutscher Kommentar) | LALIGA | DE: DAZN PPV 20", slot = true)
        val soccer104 = ch(3, "Live | Atlético Madrid vs. Barcelona | all | 8K EXCLUSIVE | DE: SOCCER PPV 104", ppv, slot = true)
        val club = ch(4, "UK: LA LIGA - ATLETICO MADRID", slot = true)
        val other = ch(5, "Live | Getafe - Sevilla | LALIGA | DE: DAZN PPV 22", slot = true)
        val feed = Feed(listOf(laliga, ppv), ProviderRoute("http://p", "http://v", "u", "p"), listOf(bein, dazn20, soccer104, club, other))
        val title = "Atletico Madrid vs Barcelona - Spanish LaLiga 2026/2027 - Week 12"
        val live = feed.liveNow(listOf(Airing("b1", title.lowercase(), Programme(0, 7200, title, "", false, true), laliga)))

        assertEquals(listOf(listOf(1, 2, 3)), live.matches.map { m -> listOf(m.tune.id) + m.alsoOn.map { it.id } })
        // The club's channel and another match's channel stay event slots of their own.
        assertEquals(listOf(4, 5), live.slots.map { it.tune.id })
    }

    @Test fun matchesGoByKickOffAndChannelsByQualityThenLanguage() {
        val pl = "🏆 Premier League"
        fun c(id: Int, name: String, tvgId: String, pic: Picture, home: String? = null) =
            Channel(id = id, name = name, group = pl, tvgId = tvgId, home = home, family = "f$id", rank = 0, pic = pic)
        val arabic = c(1, "beIN Sports 1", "a", Picture(1080, 50f, q = 4), home = "🇸🇦 beIN & Arabic Sports")
        val english = c(2, "UK: TNT Sports 1", "b", Picture(1080, 50f, q = 4))
        val uhd = c(3, "DE: Sky Sport Top Event", "c", Picture(1080, q = 5))
        val laterMatch = c(4, "UK: Sky Sports Main Event", "d", Picture(1080, q = 4))
        val feed = Feed(listOf(pl), ProviderRoute("http://p", "http://v", "u", "p"), listOf(laterMatch, arabic, english, uhd))
        val late = "Arsenal vs Chelsea - Premier League"
        val early = "Liverpool vs Everton - Premier League"
        fun on(id: String, title: String, start: Long) = Airing(id, title.lowercase(), Programme(start, start + 7200, title, "", false, true), pl)
        val live = feed.liveNow(listOf(on("d", late, 3600), on("a", early, 0), on("b", early, 0), on("c", early, 0)))

        // The earlier kick-off leads; English over Arabic at equal pictures, then the unmeasured "4K" name,
        // which the server counts as a 1080p feed of unknown fps.
        assertEquals(listOf(listOf(2, 1, 3), listOf(4)), live.matches.map { m -> listOf(m.tune.id) + m.alsoOn.map { it.id } })
    }

    @Test fun oneMatchTitledInSeveralLanguagesIsOneRow() {
        val unl = "🏆 Nations League"
        fun c(id: Int) = Channel(id = id, name = "C$id", group = unl, tvgId = "t$id", family = "f$id", rank = 0)
        val feed = Feed(listOf(unl), ProviderRoute("http://p", "http://v", "u", "p"), (1..5).map(::c))
        fun on(id: Int, title: String) = Airing("t$id", title.lowercase(), Programme(0, 7200, title, "", false, true), unl)
        // Titles from tonight's guide; the server resolved the teams of all but the conference feed.
        val crests = mapOf(
            "Spanien - England" to Crests(null, null, key = "spain|england"),
            "Spagna - Inghilterra" to Crests(null, null, key = "spain|england"),
            "Futebol - UEFA Nations League  : Espanha x Inglaterra ᴸᶦᵛᵉ" to Crests("es.png", "en.png", key = "spain|england"),
            "Frankreich - Türkei" to Crests(null, null, key = "france|turkiye"),
        )
        val live = feed.liveNow(listOf(
            on(1, "Spanien - England"), on(2, "Spagna - Inghilterra"), on(3, "Frankreich - Türkei"),
            on(4, "Futebol - UEFA Nations League  : Espanha x Inglaterra ᴸᶦᵛᵉ"), on(5, "UEFA Nations League Switch ᴸᶦᵛᵉ"),
        ), crests)

        // Same two teams in any language: one row. Another match at the same time, and a feed naming no teams, stay apart.
        assertEquals(setOf(setOf(1, 2, 4), setOf(3), setOf(5)), live.matches.map { m -> (listOf(m.tune.id) + m.alsoOn.map { it.id }).toSet() }.toSet())
        // Up/Down from any of them walks all three.
        assertEquals(listOf(1, 2, 4), live.contextFor(c(2)).map { it.id }.sorted())
    }

    @Test fun anEventSlotNamedInEnglishFoldsUnderItsMatchTitledOtherwise() {
        val unl = "🏆 Nations League"
        val ucl = "🏆 Champions League"
        val polsat = Channel(id = 1, name = "Polsat Sport Premium 2", group = unl, tvgId = "p", family = "f1", rank = 0)
        val sport1 = Channel(id = 2, name = "Sport 1", group = unl, tvgId = "s", family = "f2", rank = 0)
        val croatia = ch(3, "UEFA | 06 - Croatia vs Czechia 7:45pm", ucl, slot = true)
        val france = ch(4, "UEFA | 07 - France vs Turkey 7:45pm", ucl, slot = true)
        val belgium = ch(5, "UEFA | 05 - Belgium vs Italy 7:45pm", ucl, slot = true)
        val english = Channel(id = 6, name = "Sport 2", group = unl, tvgId = "e", family = "f6", rank = 0)
        val feed = Feed(listOf(unl, ucl), ProviderRoute("http://p", "http://v", "u", "p"), listOf(polsat, sport1, english, croatia, france, belgium))
        fun on(tvgId: String, title: String) = Airing(tvgId, title.lowercase(), Programme(0, 7200, title, "", false, true), unl)
        val live = feed.liveNow(
            listOf(
                on("p", "Croatia vs Czech Republic - UEFA Nations League"), on("s", "Fotball: UEFA Nations League - Frankrike - Tyrkia"),
                on("e", "France vs Turkey - UEFA Nations League"),
            ),
            mapOf(
                "Croatia vs Czech Republic - UEFA Nations League" to Crests(null, null, "Croatia", "Czechia", "croatia|czechia"),
                "Fotball: UEFA Nations League - Frankrike - Tyrkia" to Crests(null, null, "France", "Türkiye", "france|turkiye"),
                "France vs Turkey - UEFA Nations League" to Crests(null, null, "France", "Türkiye", "france|turkiye"),
            ),
        )

        // Croatia's slot by the crests' English "Czechia" (its title says "Czech Republic"); France's by "Turkey" in another
        // channel's title of the match. Belgium vs Italy isn't on, so its slot stays one of its own.
        assertEquals(setOf(setOf(1, 3), setOf(2, 4, 6)), live.matches.map { m -> (listOf(m.tune.id) + m.alsoOn.map { it.id }).toSet() }.toSet())
        assertEquals(listOf(5), live.slots.map { it.tune.id })
    }

    @Test fun abroadChannelsAreListedOnlyWhenTheirPictureHoldsUp() {
        val pl = "🏆 Premier League"
        fun c(id: Int, h: Int, q: Int, abroad: Boolean = false) =
            Channel(id = id, name = "C$id", group = pl, tvgId = "t$id", family = "f$id", rank = 0, pic = Picture(h, q = q), abroad = abroad)
        val title = "Arsenal vs Chelsea - Premier League"
        fun ids(vararg chs: Channel): List<Int> {
            val feed = Feed(listOf(pl), ProviderRoute("http://p", "http://v", "u", "p"), chs.toList())
            val live = feed.liveNow(chs.map { Airing(it.tvgId, title.lowercase(), Programme(0, 7200, title, "", false, true), pl) })
            return live.matches.flatMap { m -> listOf(m.tune.id) + m.alsoOn.map { it.id } }
        }
        val uk720 = c(1, 720, 3)
        val uk1080 = c(2, 1080, 4)
        val uk2160 = c(3, 2160, 5)
        val ab1080 = c(11, 1080, 4, abroad = true)
        val ab720 = c(12, 720, 3, abroad = true)
        val ab2160 = c(13, 2160, 5, abroad = true)
        // 1080p abroad next to 720p in-region, 720p abroad never while anything in-region shows the match
        assertEquals(listOf(11, 1), ids(uk720, ab1080, ab720))
        assertEquals(listOf(3), ids(uk2160, ab1080))
        // nothing in-region: every abroad channel, best first
        assertEquals(listOf(11, 12), ids(ab720, ab1080))
        // a better abroad picture leads
        assertEquals(listOf(13, 2), ids(uk1080, ab2160))
    }

    // Up/Down in a Live now context read Live now at each press (#28), so an ended match drops out.
    @Test fun zapStepsReadLiveNowAsItIs() {
        val (a1, a2, b, c) = listOf(1, 2, 3, 4).map { ch(it, "C$it") }
        fun entry(tune: Channel, vararg also: Channel) = LiveEntry("m${tune.id}", Programme(0, 7200, "m", "", false, true), tune, tune, alsoOn = also.toList())
        val both = LiveNow(listOf(entry(a1, a2), entry(b), entry(c)), emptyList())
        // Through one match's channels, wrapping
        val (to, context) = both.zapStep(sameMatch = true, from = a1, down = true)!!
        assertEquals(a2, to)
        assertTrue(context is SameMatch)
        assertEquals(a1, both.zapStep(true, a2, true)!!.first)
        // Across the matches, one stop per match
        assertEquals(listOf(b, c), listOf(both.zapStep(false, a1, true)!!.first, both.zapStep(false, b, true)!!.first))
        // Match a ended: from its channel, its context gives way to the matches, from the first (or last)
        val later = LiveNow(listOf(entry(b), entry(c)), emptyList())
        assertEquals(b to listOf(b, c), later.zapStep(true, a2, true))
        assertEquals(c, later.zapStep(false, a1, false)!!.first)
        // Alone in it: nowhere to go in Live now (the key falls back to the group)
        assertNull(LiveNow(listOf(entry(b)), emptyList()).zapStep(false, b, true))
        // Nothing on
        assertNull(LiveNow(emptyList(), emptyList()).zapStep(false, b, true))
    }
}
