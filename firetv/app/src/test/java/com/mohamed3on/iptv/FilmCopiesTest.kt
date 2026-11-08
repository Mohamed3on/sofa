package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.CopiesAnswer
import com.mohamed3on.iptv.data.CopySources
import com.mohamed3on.iptv.data.Debrid
import com.mohamed3on.iptv.data.DebridCandidate
import com.mohamed3on.iptv.data.DebridCheck
import com.mohamed3on.iptv.data.DebridState
import com.mohamed3on.iptv.data.ServerCopy
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.player.CopyChoices
import com.mohamed3on.iptv.player.FilmCopies
import com.mohamed3on.iptv.player.Hdr
import com.mohamed3on.iptv.player.Passthrough
import com.mohamed3on.iptv.player.SetupProfile
import com.mohamed3on.iptv.player.answerOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilmCopiesTest {
    private val tv = SetupProfile("TV", setOf(Hdr.Hdr10), setOf(5, 8), setOf("video/hevc", "video/avc"), setOf(Passthrough.Ac3, Passthrough.EAc3), 8)
    private val iptv = ServerCopy("iptv", id = 7, ext = "mp4", res = 1080, codec = "h264", audio = "ac3", ch = 6)
    private val rd4k = ServerCopy("rd", hash = "aa", file = 2, size = 9_000_000_000, cached = true, res = 2160, hdr = listOf("hdr10"), codec = "hevc", audio = "eac3", ch = 6, kbps = 8_000.0)

    @Test
    fun `the box's names become the ranker's`() {
        val a = answerOf(CopiesAnswer("movie:1", "fr", copies = listOf(iptv, rd4k, ServerCopy("rd", hash = "bb", hdr = listOf("dv", "hdr10"), audio = "dts-hd", codec = "mpeg2", subs = listOf("en")))))
        val (i, r, dv) = a.copies.copies
        assertEquals(listOf("iptv:7", "rd:aa:2", "rd:bb"), listOf(i.id, r.id, dv.id))
        assertEquals("fr", a.copies.originalLanguage)
        assertEquals(8f, r.mbps)
        assertEquals(listOf("DV", "HDR10"), dv.hdr)
        assertEquals("dtshd" to "mpeg2video", dv.audio to dv.codec)
        assertEquals(true, dv.englishSubs)
        assertEquals(DebridCandidate("aa", 2, null, cached = true), a.copies.debrid["rd:aa:2"])
        assertEquals("mp4", a.ext("iptv:7"))
    }

    @Test
    fun `plays names the Real-Debrid copy once it is ready, IPTV when it isn't, and asks again while Real-Debrid is pending`() = runTest {
        var asks = 0
        fun copies(ready: Set<String>) = FilmCopies(
            fetch = {
                asks++
                Result.success(CopiesAnswer("movie:1", "en", sources = CopySources(rd = if (asks == 1) "pending" else "ok"), copies = if (asks == 1) listOf(iptv) else listOf(iptv, rd4k)))
            },
            debrid = FakeDebrid { it in ready }, choices = CopyChoices(MapStore()), capacity = Capacity(MapStore()), setup = { tv }, scope = backgroundScope,
        )
        val ready = copies(setOf("aa")).state("movie:1")
        runCurrent()
        assertEquals("iptv:7", ready.value.plays?.id) // only IPTV so far
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("rd:aa:2", ready.value.plays?.id)

        asks = 1
        val notCached = copies(emptySet()).state("movie:1")
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals("iptv:7", notCached.value.plays?.id)
    }

    // #22: past the 30 minutes a plan is kept, a play's plan isn't replaced by an empty one (its IPTV container with it).
    @Test
    fun `a play keeps its plan past the 30 minutes, and the plan is let go 30 minutes after it`() = runTest {
        var t = 0L
        val films = FilmCopies(
            fetch = { Result.success(CopiesAnswer("movie:1", "en", copies = listOf(iptv.copy(ext = "mkv")))) },
            debrid = FakeDebrid(), choices = CopyChoices(MapStore()), capacity = Capacity(MapStore()), setup = { tv }, scope = backgroundScope, now = { t },
        )
        val plan = films.plan("movie:1")
        runCurrent()
        plan.playing(true)
        t = 31 * 60_000L
        assertSame(plan, films.plan("movie:1"))
        assertEquals("mkv", films.state("movie:1").value.answer?.ext("iptv:7"))
        plan.playing(false)
        t += 29 * 60_000L
        assertSame(plan, films.plan("movie:1"))
        t += 2 * 60_000L
        assertNotSame(plan, films.plan("movie:1"))
    }
}
