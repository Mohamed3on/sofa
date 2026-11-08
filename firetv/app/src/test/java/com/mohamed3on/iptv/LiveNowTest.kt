package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Airing
import com.mohamed3on.iptv.data.Channel
import com.mohamed3on.iptv.data.Feed
import com.mohamed3on.iptv.data.Programme
import com.mohamed3on.iptv.data.ProviderRoute
import com.mohamed3on.iptv.data.liveNow
import org.junit.Assert.assertEquals
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
        fun c(id: Int, name: String, tvgId: String, q: Int, badges: List<String> = emptyList(), home: String? = null) =
            Channel(id = id, name = name, group = pl, tvgId = tvgId, home = home, family = "f$id", rank = 0, q = q, badges = badges)
        val arabic = c(1, "beIN Sports 1", "a", 4, listOf("FHD", "50fps"), home = "🇸🇦 beIN & Arabic Sports")
        val english = c(2, "UK: TNT Sports 1", "b", 4, listOf("FHD", "50fps"))
        val uhd = c(3, "DE: Sky Sport Top Event", "c", 5)
        val laterMatch = c(4, "UK: Sky Sports Main Event", "d", 4)
        val feed = Feed(listOf(pl), ProviderRoute("http://p", "http://v", "u", "p"), listOf(laterMatch, arabic, english, uhd))
        val late = "Arsenal vs Chelsea - Premier League"
        val early = "Liverpool vs Everton - Premier League"
        fun on(id: String, title: String, start: Long) = Airing(id, title.lowercase(), Programme(start, start + 7200, title, "", false, true), pl)
        val live = feed.liveNow(listOf(on("d", late, 3600), on("a", early, 0), on("b", early, 0), on("c", early, 0)))

        // The earlier kick-off leads; its 4K channel first, then English over Arabic at equal quality.
        assertEquals(listOf(listOf(3, 2, 1), listOf(4)), live.matches.map { m -> listOf(m.tune.id) + m.alsoOn.map { it.id } })
    }
}
