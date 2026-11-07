package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Catalog
import com.mohamed3on.iptv.data.Episode
import com.mohamed3on.iptv.data.History
import com.mohamed3on.iptv.data.Movie
import com.mohamed3on.iptv.data.NextEpisode
import com.mohamed3on.iptv.data.Person
import com.mohamed3on.iptv.data.Resume
import com.mohamed3on.iptv.data.ResumeStore
import com.mohamed3on.iptv.system.Check
import com.mohamed3on.iptv.ui.LibraryPage
import com.mohamed3on.iptv.ui.LiveLine
import com.mohamed3on.iptv.ui.MoviesNavigator
import com.mohamed3on.iptv.ui.nextUpOf
import com.mohamed3on.iptv.ui.upNextOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Movies & Shows' navigation (#17): pages and Back, the next episode, Up next against Continue watching, the one connection. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoviesNavigatorTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val film = Movie(id = 1, name = "Film (2024)", group = "g", titleId = "movie:1")
    private val other = Movie(id = 2, name = "Other (2020)", group = "g", titleId = "movie:2")
    private val show = Movie(id = 3, name = "Show", group = "s", titleId = "tv:3")
    private val episodes = (1..3).map { Episode(id = 10 + it, titleId = "tv:3:s1e$it", season = 1, episode = it) }

    private val prefs = MapStore()
    private val shows = ResumeStore(prefs, "shows") { now }
    private var watched = emptySet<String>()

    private fun TestScope.nav() = MoviesNavigator(
        prefs, ResumeStore(prefs, "films") { now }, shows,
        episodesOf = { episodes }, watched = { watched }, nextUp = { emptyList() },
        providerId = { null }, warm = {}, scope = backgroundScope,
    )

    private val screen get() = prefs.get(Check.LAST_SCREEN)

    @Test fun `pages open over each other, Back takes the top one off, and a launch reopens the top title`() = runTest {
        val nav = nav()
        nav.open(film)
        nav.open(Person(7, "Actor"))
        nav.open(other)
        assertEquals("title:movie:2", screen)
        nav.back()
        assertTrue(nav.pages.last() is LibraryPage.Cast)
        assertEquals("title:movie:1", screen)
        nav.back()
        nav.back()
        assertTrue(nav.pages.isEmpty())
        assertEquals("movies", screen)
        // A person search found opens their page.
        nav.open(Movie(id = -1, name = "Actor", group = "", titleId = "person:7"))
        assertEquals(LibraryPage.Cast(Person(7, "Actor")), nav.pages.single())
    }

    @Test fun `a show resumes on its first unwatched episode, and each end plays the next until the last`() = runTest {
        val nav = nav()
        watched = setOf("tv:3:s1e1")
        nav.resume(show, fromStart = false)
        runCurrent()
        assertEquals(1, nav.playing?.show?.index)
        assertEquals("play:tv:3", screen)
        nav.ended()
        assertEquals("tv:3:s1e3", nav.playing?.title)
        assertEquals(0L, nav.playing?.from)
        assertEquals("tv:3:s1e3", shows.find("tv:3")?.episode) // Continue watching moves on with it
        assertFalse(nav.hasNext)
        nav.ended()
        assertNull(nav.playing)
        assertEquals("movies", screen)
    }

    @Test fun `what was played or aired this week is Up next, older is Continue watching, a show in progress hides its next episode`() {
        val library = Catalog(emptyList(), listOf(film, other, show))
        val places = listOf(
            Resume(film.titleId, "Film", pos = 1, dur = 10, at = now - day),
            Resume(other.titleId, "Other", pos = 1, dur = 10, at = now - 30 * day),
        )
        val next = listOf(
            NextEpisode(show, "tv:3:s1e2", "S1 E2", new = true, air = "1970-04-08"),
            NextEpisode(Movie(id = 4, name = "Old", group = "s", titleId = "tv:4"), "tv:4:s1e2", "S1 E2", new = false, air = "1970-01-01"),
        )
        val (upNext, older) = upNextOf(library, places, next, now)
        assertEquals(listOf("movie:1", "tv:3"), upNext.map { it.first.titleId })
        assertEquals(listOf("movie:2", "tv:4"), older.map { it.titleId })

        val history = History(emptySet(), emptyList(), next)
        assertEquals(listOf("tv:4"), nextUpOf(history, listOf(Resume("tv:3", "Show", pos = 1, dur = 10, at = now)), emptySet(), emptySet()).map { it.show.titleId })
        assertEquals(listOf("tv:3"), nextUpOf(history, emptyList(), setOf("tv:4:s1e2"), emptySet()).map { it.show.titleId })
    }

    @Test fun `while Movies is open live TV is kept stopped, and leaving re-tunes it`() = runTest {
        val stopped = MutableStateFlow(false)
        val calls = mutableListOf<String>()
        val line = object : LiveLine {
            override val stopped = stopped
            override fun stop() { calls += "stop"; stopped.value = true }
            override fun retry() { calls += "retry"; stopped.value = false }
        }
        val nav = nav()
        val holding = backgroundScope.launch { nav.hold(line, open = true) }
        runCurrent()
        assertEquals("movies", screen)
        stopped.value = false // the app started it again (Home and back)
        runCurrent()
        assertEquals(listOf("stop", "stop"), calls)
        holding.cancel()
        nav.hold(line, open = false)
        assertEquals(listOf("stop", "stop", "retry"), calls)
        assertNull(screen) // a launch opens on live TV
    }
}
