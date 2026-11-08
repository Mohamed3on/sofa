package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Episode
import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.data.ResumeStore
import com.mohamed3on.iptv.data.WatchedStore
import com.mohamed3on.iptv.player.Capacity.Source.Iptv
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.player.CopyChoices
import com.mohamed3on.iptv.player.CopyPlan
import com.mohamed3on.iptv.player.TitleAnswer
import com.mohamed3on.iptv.player.FilmPlay
import com.mohamed3on.iptv.player.FilmSession
import com.mohamed3on.iptv.player.Hdr
import com.mohamed3on.iptv.player.Place
import com.mohamed3on.iptv.player.PlayOf
import com.mohamed3on.iptv.player.PlayStores
import com.mohamed3on.iptv.player.SetupProfile
import com.mohamed3on.iptv.player.TitleCopies
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The end-of-play rules (#16): one "finished" (90%) for Trakt, watched marks, following a show and resume. */
@OptIn(ExperimentalCoroutinesApi::class)
class FilmPlayTest {
    private val min = 60_000L
    private val film = Movie(id = 1, name = "EN - Film (2024)", group = "g", titleId = "movie:603")
    private val show = Movie(id = 2, name = "EN - Show", group = "s", titleId = "tv:1396")
    private val pilot = Episode(id = 3, titleId = "tv:1396:s1e1", season = 1, episode = 1)
    private val tv = SetupProfile("TV", setOf(Hdr.Hdr10), setOf(5, 8), setOf("video/hevc", "video/avc"), emptySet(), 8)

    private class Rig(val play: FilmPlay, val player: FakeFilm, val stores: PlayStores, val trakt: List<String>, val events: List<String>)

    private fun TestScope.rig(of: PlayOf): Rig {
        val player = FakeFilm(backgroundScope) { testScheduler.currentTime }
        val choices = CopyChoices(MapStore()) { testScheduler.currentTime }
        val plan = CopyPlan(of.id, FakeDebrid(), choices, { tv }, { null }, backgroundScope, { testScheduler.currentTime })
        plan.answered(TitleAnswer(TitleCopies(listOf(Copy("iptv:1", Iptv, 1080)), originalLanguage = "en")))
        val session = FilmSession(of.id, plan, player, choices, { tv }, FakeBandwidth(), { c, _ -> "http://iptv.test/${c.id}" }, backgroundScope, { testScheduler.currentTime })
        val store = MapStore()
        val trakt = mutableListOf<String>()
        val events = mutableListOf<String>()
        val stores = PlayStores(
            ResumeStore(store, "films"), ResumeStore(store, "shows"), WatchedStore(store, "wf"), WatchedStore(store, "we"),
            ResumeStore(store, "followed"), { trakt += it }, { testScheduler.currentTime },
        )
        val play = FilmPlay(of, player, session, stores, backgroundScope, { events += "ended" }, { events += "closed" })
        play.start(Place(0, audio = "en"), null)
        runCurrent()
        return Rig(play, player, stores, trakt, events)
    }

    @Test fun `a film left at 92 percent is finished everywhere, and logged on Trakt once`() = runTest {
        val r = rig(PlayOf(film))
        r.player.seek(r.player.duration * 91 / 100)
        r.play.save()
        r.player.seek(40 * min) // seeked back, then past 90% again
        r.player.seek(r.player.duration * 92 / 100)
        r.play.leave()
        assertEquals(listOf("movie:603"), r.trakt)
        assertTrue(film.titleId in r.stores.watchedFilms.ids.value)
        assertNull("out of Continue watching", r.stores.films.find(film.titleId))
        assertEquals(0L, r.stores.films.startAt(film.titleId))
        assertEquals(listOf("closed"), r.events)
    }

    @Test fun `a film left part-way keeps its place, saved as it plays`() = runTest {
        val r = rig(PlayOf(film))
        r.player.seek(30 * min)
        advanceTimeBy(FilmPlay.SAVE_EVERY_MS + 1)
        runCurrent()
        assertTrue(r.stores.films.startAt(film.titleId) >= 30 * min)
        r.player.seek(50 * min)
        r.play.leave()
        assertEquals(50 * min, r.stores.films.startAt(film.titleId))
        assertTrue(r.trakt.isEmpty())
        assertTrue(r.stores.watchedFilms.ids.value.isEmpty())
    }

    @Test fun `an episode played to the end is watched, its show followed, then the end is followed`() = runTest {
        val r = rig(PlayOf(show, pilot))
        r.player.seek(r.player.duration)
        r.player.end()
        runCurrent()
        assertEquals(listOf(pilot.titleId), r.trakt)
        assertTrue(pilot.titleId in r.stores.watchedEpisodes.ids.value)
        assertEquals(pilot.titleId, r.stores.followed.find(show.titleId)?.episode)
        assertNull("a finished episode with nothing after it drops the show", r.stores.shows.find(show.titleId))
        assertEquals(listOf("ended"), r.events)
        r.play.close() // the screen goes: nothing more is saved over it
        assertNull(r.stores.shows.find(show.titleId))
    }
}
