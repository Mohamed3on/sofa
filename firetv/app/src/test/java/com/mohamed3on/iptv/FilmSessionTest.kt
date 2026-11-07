package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.DebridCandidate
import com.mohamed3on.iptv.data.DebridCheck
import com.mohamed3on.iptv.data.DebridState
import com.mohamed3on.iptv.player.Capacity
import com.mohamed3on.iptv.player.Capacity.Source.Iptv
import com.mohamed3on.iptv.player.Capacity.Source.Debrid
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.player.CopyChoices
import com.mohamed3on.iptv.player.CopyPlan
import com.mohamed3on.iptv.player.TitleAnswer
import com.mohamed3on.iptv.player.FilmPhase
import com.mohamed3on.iptv.player.FilmSession
import com.mohamed3on.iptv.player.Hdr
import com.mohamed3on.iptv.player.Notice
import com.mohamed3on.iptv.player.OfferKind
import com.mohamed3on.iptv.player.Passthrough
import com.mohamed3on.iptv.player.Place
import com.mohamed3on.iptv.player.SetupProfile
import com.mohamed3on.iptv.player.StreamFailure
import com.mohamed3on.iptv.player.TitleCopies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
class FilmSessionTest {
    private val tv = SetupProfile(
        "Built-in Screen", setOf(Hdr.Hdr10, Hdr.Hlg), setOf(5, 8),
        setOf("video/hevc", "video/avc"), setOf(Passthrough.Ac3, Passthrough.EAc3, Passthrough.EAc3Joc), 8,
    )
    private val dvTv = tv.copy(displayName = "OLED", hdr = tv.hdr + Hdr.DolbyVision)

    private val rd4k = Copy("rd:a", Debrid, 2160, listOf("HDR10"), codec = "hevc", audio = "eac3", channels = 6, mbps = 25f)
    private val iptv1080 = Copy("iptv:1", Iptv, 1080, codec = "h264", audio = "aac", channels = 2, mbps = 6f)
    /** Dolby Vision of unknown profile: untested (KTD6). */
    private val dv = Copy("rd:dv", Debrid, 2160, listOf("DV"), codec = "hevc", audio = "eac3", channels = 6, mbps = 40f)
    private val iptvHdr = Copy("iptv:hdr", Iptv, 2160, listOf("HDR10"), codec = "hevc", audio = "eac3", channels = 6, mbps = 20f)

    private class Rig(val session: FilmSession, val plan: CopyPlan, val player: FakeFilm, val debrid: FakeDebrid, val choices: CopyChoices, val net: FakeBandwidth) {
        val state get() = session.state.value
        val urls get() = player.opens.map { it.first }
    }

    private fun TestScope.rig(setup: SetupProfile = tv, debrid: FakeDebrid = FakeDebrid()): Rig {
        val player = FakeFilm(backgroundScope) { testScheduler.currentTime }
        val choices = CopyChoices(MapStore()) { testScheduler.currentTime }
        val net = FakeBandwidth()
        val plan = CopyPlan("movie:1", debrid, choices, { setup }, { null }, backgroundScope, { testScheduler.currentTime })
        val session = FilmSession(
            "movie:1", plan, player, choices, { setup }, net, { "http://iptv.test/${it.id}" }, backgroundScope,
            { testScheduler.currentTime },
        )
        return Rig(session, plan, player, debrid, choices, net)
    }

    private fun titleCopies(vararg copies: Copy) =
        TitleCopies(copies.toList(), copies.filter { it.source == Debrid }.associate { it.id to DebridCandidate(it.id, null) }, originalLanguage = "en")

    /** The box answers with [copies]. */
    private fun answer(r: Rig, vararg copies: Copy) = r.plan.answered(TitleAnswer(titleCopies(*copies)))

    private fun TestScope.play(r: Rig, vararg copies: Copy, from: Long = 0) {
        answer(r, *copies)
        r.session.play(Place(from, audio = "en"))
        runCurrent()
    }

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.stallThrice(r: Rig) {
        repeat(3) {
            advance(1_000)
            r.player.rebuffer()
        }
        runCurrent()
    }

    @Test fun aPermanentFaultMovesOnAndMarksTheCopy() = runTest {
        val r = rig()
        r.player.respond = { if ("rd.test" in it) StreamFailure.Unsupported else null }
        play(r, rd4k, iptv1080)
        assertEquals(iptv1080, r.state.copy)
        assertEquals(FilmPhase.Playing, r.state.phase)
        assertEquals(false, r.choices.marks(tv)[rd4k.id]?.works)
    }

    // Unreported audio is tried; a file none of whose tracks plays is marked and teaches the setup, whose copies of
    // that kind then rank below otherwise equal ones (never ruled out: a remux may carry a Dolby track too).
    @Test fun audioThatDidntPlayIsLearntForTheSetupAndItsCopiesDemoted() = runTest {
        val r = rig()
        val dts = rd4k.copy(id = "rd:dts", audio = "dts")
        r.player.respond = { if ("rd:dts" in it) StreamFailure.NoAudio(setOf("dts")) else null }
        play(r, dts, iptv1080)
        assertEquals(listOf("rd:dts", "iptv:1"), r.player.opens.map { it.first.substringAfter("test/").substringBefore('/') })
        assertEquals(iptv1080, r.state.copy)
        assertEquals(false, r.choices.marks(tv)[dts.id]?.works)

        val dtsHd = rd4k.copy(id = "rd:dtshd", audio = "dtshd")
        val ranked = r.choices.rank("movie:2", listOf(dtsHd, rd4k), "en", tv) { null }
        assertEquals(listOf(rd4k, dtsHd), ranked.copies.map { it.copy })
        assertTrue(ranked.copies[1].reason, ranked.copies[1].reason.startsWith("DTS didn’t play here on"))
        assertEquals(dtsHd, r.choices.rank("movie:2", listOf(dtsHd, iptv1080), "en", tv) { null }.pick?.copy) // not ruled out
        assertEquals(dtsHd, r.choices.rank("movie:2", listOf(dtsHd, rd4k), "en", dvTv) { null }.pick?.copy) // a new setup: no demotion, a tie keeps order
    }

    // R2: a multi-language file's default track is often a dub; a first play asks for the film's own language.
    @Test fun aFirstPlayAsksForTheOriginalLanguage() = runTest {
        val r = rig()
        answer(r, rd4k)
        r.session.play(Place(0))
        runCurrent()
        assertEquals("en", r.player.opens.last().second.audio)
    }

    // A chosen copy that can't start says so and gives up its pin; the copy playing stays.
    @Test fun aChosenCopyThatCantStartSaysSoAndIsUnpinned() = runTest {
        val r = rig(debrid = FakeDebrid(ready = { it != "rd:gone" }))
        val gone = rd4k.copy(id = "rd:gone")
        play(r, gone, iptv1080)
        assertEquals(iptv1080, r.state.copy)
        r.session.choose(gone.id)
        runCurrent()
        assertEquals(iptv1080, r.state.copy)
        assertEquals(Notice.CantStart(gone), r.state.notice)
        assertNull(r.choices.pin("movie:1"))
    }

    // A track carried over from the copy before isn't the chosen language: the copy that has it is asked for that, not marked as lacking it.
    @Test fun aCarriedOverTrackAsksForTheChosenLanguageInstead() = runTest {
        val r = rig()
        val dub = rd4k.copy(id = "rd:dub", languages = listOf("ar"))
        r.choices.language("movie:1", "ar")
        r.player.respond = { url -> if ("rd:dub" in url && r.player.opens.last().second.audio != "ar") StreamFailure.NoLanguage else null }
        answer(r, dub)
        r.session.play(Place(0, audio = "fr"))
        runCurrent()
        assertEquals(dub, r.state.copy)
        assertEquals(listOf("fr", "ar"), r.player.opens.map { it.second.audio })
        assertNull(r.choices.lacking()[dub.id])
    }

    // "und", a track without a language, is none to keep: the chosen language is asked at once.
    @Test fun anUndeterminedTrackIsNoLanguage() = runTest {
        val r = rig()
        r.choices.language("movie:1", "ar")
        answer(r, rd4k.copy(id = "rd:dub", languages = listOf("ar")))
        r.session.play(Place(0, audio = "und"))
        runCurrent()
        assertEquals("ar", r.player.opens.single().second.audio)
    }

    // A release name's languages are often its subtitles': a file without the chosen language's track moves on, remembered.
    @Test fun aChosenLanguageTheFileLacksMovesOnAndIsRemembered() = runTest {
        val r = rig()
        val claims = rd4k.copy(id = "rd:claims", languages = listOf("en", "ar"))
        val dub = iptv1080.copy(languages = listOf("ar"))
        r.choices.language("movie:1", "ar")
        r.player.respond = { if ("rd:claims" in it) StreamFailure.NoLanguage else null }
        answer(r, claims, dub)
        r.session.play(Place(0))
        runCurrent()
        assertEquals(dub, r.state.copy)
        assertEquals("ar", r.player.opens.last().second.audio)
        assertEquals(setOf("ar"), r.choices.lacking()[claims.id])
        assertEquals(dub, r.plan.state.value.ranking?.pick?.copy)
    }

    @Test fun aBusyProviderMovesOnWithNoMark() = runTest {
        val r = rig()
        r.player.respond = { if ("iptv.test" in it) StreamFailure.Http(458) else null }
        play(r, iptvHdr, Copy("rd:b", Debrid, 1080, codec = "h264", mbps = 8f))
        assertEquals("rd:b", r.state.copy?.id)
        assertTrue(r.choices.marks(tv).isEmpty())
    }

    // AE5
    @Test fun stallsStepDownAndRecoveryOffersTheBetterCopyOnce() = runTest {
        val r = rig()
        play(r, rd4k, iptv1080)
        advance(MIN)
        stallThrice(r)
        assertEquals(iptv1080, r.state.copy)
        assertEquals(r.player.opens[0].second.position + MIN + 3_000, r.player.opens.last().second.position)
        assertEquals("en", r.player.opens.last().second.audio)
        assertEquals(Notice.SteppedDown(iptv1080), r.state.notice)

        r.net.sessionMbps = 40f // over 1.3 x 25
        advance(5 * MIN + 2_000)
        assertEquals(Notice.Offer(OfferKind.Better, rd4k), r.state.notice)
        advance(FilmSession.OFFER_MS) // fades
        assertNull(r.state.notice)
        advance(20 * MIN)
        assertNull(r.state.notice)
        assertEquals(iptv1080, r.state.copy)
    }

    // AE6
    @Test fun noOfferInTheLast15Minutes() = runTest {
        val r = rig()
        play(r, rd4k, iptv1080, from = 120 * MIN - 10 * MIN)
        stallThrice(r)
        r.net.sessionMbps = 40f
        advance(6 * MIN)
        assertNull(r.state.notice)
    }

    @Test fun aFailedSwitchReturnsToTheCurrentCopyAndTheOfferStaysSpent() = runTest {
        val r = rig()
        play(r, rd4k, iptv1080)
        stallThrice(r)
        r.net.sessionMbps = 40f
        advance(5 * MIN + 2_000)
        r.player.respond = { if ("rd.test" in it) StreamFailure.Http(503) else null }
        val at = r.player.place.position
        assertTrue(r.session.accept())
        runCurrent()
        assertEquals(iptv1080, r.state.copy)
        assertEquals(FilmPhase.Playing, r.state.phase)
        assertEquals(at, r.player.opens.last().second.position)
        advance(10 * MIN)
        assertNull(r.state.notice)
    }

    // AE9
    @Test fun backDuringATrialReturnsAndMarksTheCopyAsNotWorking() = runTest {
        val r = rig(dvTv)
        play(r, dv, iptvHdr)
        assertEquals(iptvHdr, r.state.copy) // a working copy plays; the untested one is offered
        advance(11_000)
        assertEquals(Notice.Offer(OfferKind.Untested, dv), r.state.notice)
        assertTrue(r.session.accept())
        runCurrent()
        assertEquals(dv, r.state.copy)
        assertTrue(r.state.trial)
        advance(5_000)
        val at = r.player.place.position
        assertTrue(r.session.back())
        runCurrent()
        assertEquals(iptvHdr, r.state.copy)
        assertEquals(at, r.player.opens.last().second.position)
        assertEquals(false, r.choices.marks(dvTv)[dv.id]?.works)
    }

    @Test fun aCleanTrialMarksTheCopyAsWorking() = runTest {
        val r = rig(dvTv)
        play(r, dv, iptvHdr)
        advance(11_000)
        r.session.accept()
        runCurrent()
        advance(31_000)
        assertEquals(true, r.choices.marks(dvTv)[dv.id]?.works)
        assertEquals(Notice.TrialResult(dv, works = true), r.state.notice)
    }

    @Test fun stallsOnAPinnedCopyOfferTheLighterOneInsteadOfSteppingDown() = runTest {
        val r = rig()
        r.choices.pin("movie:1", rd4k.id)
        play(r, rd4k, iptv1080)
        stallThrice(r)
        assertEquals(Notice.Offer(OfferKind.Lighter, iptv1080), r.state.notice)
        assertEquals(1, r.player.opens.size)
    }

    @Test fun aRealDebridCopyResumedAfterALongPauseGetsAFreshLink() = runTest {
        val r = rig()
        play(r, rd4k)
        advance(MIN)
        r.session.pause()
        advance(15 * MIN)
        r.session.resume()
        runCurrent()
        assertEquals(2, r.debrid.links)
        assertEquals("https://rd.test/rd:a/2" to Place(MIN, audio = "en"), r.player.opens.last())
    }

    @Test fun everyCopyFailingEndsUnavailable() = runTest {
        val r = rig()
        r.player.respond = { StreamFailure.Http(404) }
        play(r, rd4k, iptv1080)
        assertEquals(FilmPhase.Unavailable, r.state.phase)
        assertTrue(r.choices.marks(tv).isEmpty())
    }

    @Test fun slowCopiesStartTheLibrarysIptvCopyAndLaterOnesDoNotSwitch() = runTest {
        val r = rig()
        backgroundScope.launch {
            delay(6_000)
            answer(r, rd4k, iptv1080)
        }
        r.session.play(Place(0), fallback = iptv1080)
        advance(FilmSession.WAIT_MS)
        assertEquals(listOf("http://iptv.test/iptv:1"), r.urls)
        advance(3_000)
        assertEquals(2, r.state.ranking?.copies?.size)
        assertEquals(iptv1080, r.state.copy)
        assertEquals(1, r.player.opens.size)
    }

    // A copy chosen in Versions waits for its debrid check however long it takes, the copy playing kept meanwhile;
    // only Play's own pick gives the check a short wait while an IPTV copy could play instead.
    @Test fun aChosenDebridCopyWaitsForItsCheck() = runTest {
        val r = rig(debrid = FakeDebrid(answerMs = 20_000))
        val iptv2 = iptv1080.copy(id = "iptv:2")
        play(r, rd4k, iptv1080, iptv2)
        advance(FilmSession.WAIT_MS)
        assertEquals(iptv1080, r.state.copy)
        r.session.choose(rd4k.id)
        advance(FilmSession.WAIT_MS + 1_000)
        assertEquals(iptv1080, r.state.copy)
        advance(20_000)
        assertEquals(rd4k, r.state.copy)
    }

    // KTD13: Real-Debrid's copies, still pending when Play started, join the play when the box has them.
    @Test fun copiesArrivingAfterPlayJoinIt() = runTest {
        val r = rig()
        answer(r, iptv1080)
        r.session.play(Place(0))
        backgroundScope.launch {
            delay(3_000)
            answer(r, iptv1080, rd4k)
        }
        runCurrent()
        assertEquals(iptv1080, r.state.copy)
        advance(3_000)
        assertEquals(listOf(rd4k, iptv1080), r.state.ranking?.copies?.map { it.copy })
        assertTrue(r.state.debrid[rd4k.id] is DebridCheck.Ready)
    }

    // #14: the title page's Plays line, Versions and Play read one plan: what it names is what opens, after one debrid check.
    @Test fun thePlaysLineNamesWhatPlayOpens() = runTest {
        val r = rig(debrid = FakeDebrid(ready = { it != "rd:gone" }))
        val gone = rd4k.copy(id = "rd:gone") // flagged cached, but the debrid no longer has it
        val rd1080 = Copy("rd:b", Debrid, 1080, codec = "hevc", audio = "eac3", channels = 6, mbps = 8f)
        val torrents = mapOf(gone.id to DebridCandidate(gone.id, null, cached = true), rd1080.id to DebridCandidate(rd1080.id, null))
        r.plan.answered(TitleAnswer(TitleCopies(listOf(gone, rd1080, iptv1080), torrents, originalLanguage = "en")))
        runCurrent()
        val plays = r.plan.state.value.plays
        assertEquals(rd1080, plays)
        r.session.play(Place(0))
        runCurrent()
        assertEquals(plays, r.state.copy)
        assertEquals(FilmPhase.Playing, r.state.phase)
        assertEquals(1, r.debrid.checks)
    }
}
