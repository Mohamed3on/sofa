package com.mohamed3on.iptv

import com.mohamed3on.iptv.player.Capacity.Source.Iptv
import com.mohamed3on.iptv.player.Capacity.Source.RealDebrid
import com.mohamed3on.iptv.player.Copy
import com.mohamed3on.iptv.player.CopyChoices
import com.mohamed3on.iptv.player.Correction
import com.mohamed3on.iptv.player.Hdr
import com.mohamed3on.iptv.player.Passthrough
import com.mohamed3on.iptv.player.SetupProfile
import com.mohamed3on.iptv.player.Support
import com.mohamed3on.iptv.player.EGYPTIAN
import com.mohamed3on.iptv.player.audioChoices
import com.mohamed3on.iptv.player.rankCopies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyRankerTest {
    /** Today's TV: HDR10, HLG and HDR10+, no Dolby Vision; the stick decodes DV 5 and 8; the Beam takes DD+ Atmos. */
    private val hdr10Tv = SetupProfile(
        "Built-in Screen", setOf(Hdr.Hdr10, Hdr.Hlg, Hdr.Hdr10Plus), setOf(4, 5, 8, 9, 10),
        setOf("video/hevc", "video/avc", "video/av01"), setOf(Passthrough.Ac3, Passthrough.EAc3, Passthrough.EAc3Joc), 8,
    )
    private val oled = hdr10Tv.copy(displayName = "LG OLED", hdr = hdr10Tv.hdr + Hdr.DolbyVision)

    private val dvRemux = Copy("rd:dv", RealDebrid, 2160, listOf("DV"), dvProfile = 8, codec = "hevc", audio = "eac3", channels = 6, atmos = true, mbps = 60f)
    private val hdr10Web = Copy("rd:web", RealDebrid, 2160, listOf("HDR10"), codec = "hevc", audio = "eac3", channels = 6, atmos = true, mbps = 25f)
    private val iptv1080 = Copy("iptv:1", Iptv, 1080, codec = "h264", audio = "aac", channels = 2, mbps = 6f)

    private fun rank(copies: List<Copy>, setup: SetupProfile = hdr10Tv, original: String? = "en", typical: Float? = null) =
        rankCopies(copies, original, setup, { typical })

    @Test fun ae1NoDolbyVisionOnTheTvPicksTheHdr10Copy() {
        val r = rank(listOf(dvRemux, hdr10Web, iptv1080))
        assertEquals(hdr10Web, r.pick?.copy)
        val why = r.copies.first { it.copy == dvRemux }.reason
        assertTrue(why, "Dolby Vision" in why)
    }

    @Test fun aStarvedHdr10PlusRipLosesToAFullHdr10Remux() {
        val rip = Copy("rd:rip", RealDebrid, 2160, listOf("DV", "HDR10+", "HDR10"), codec = "hevc", audio = "ac3", channels = 6, mbps = 4f)
        val remux = Copy("rd:remux", RealDebrid, 2160, listOf("HDR10"), codec = "hevc", audio = "eac3", channels = 6, mbps = 72f)
        assertEquals(remux, rank(listOf(rip, remux)).pick?.copy)
    }

    @Test fun ae2ADolbyVisionTvPicksTheDolbyVisionCopy() {
        assertEquals(dvRemux, rank(listOf(dvRemux, hdr10Web, iptv1080), oled).pick?.copy)
    }

    @Test fun ae3TypicalBandwidthRulesOutTheHeavyRemux() {
        val remux = hdr10Web.copy(id = "rd:remux", mbps = 75f)
        val r = rank(listOf(remux, hdr10Web), typical = 40f)
        assertEquals(hdr10Web, r.pick?.copy)
        assertEquals("needs ~75 Mbps, typical 40", r.copies.first { it.copy == remux }.reason)
        assertEquals(remux, r.heavier?.copy) // R10's offer, should the line recover
        assertEquals(hdr10Web, r.lighter(remux)?.copy) // R9's step-down
    }

    @Test fun ae4TheOriginalLanguageBeatsABetterDub() {
        val arabic = iptv1080.copy(languages = listOf("ar"))
        val french = hdr10Web.copy(languages = listOf("fr"))
        val r = rank(listOf(french, arabic), original = "ar")
        assertEquals(arabic, r.pick?.copy)
        assertEquals("dub", r.copies.first { it.copy == french }.reason)
    }

    @Test fun aChosenLanguageBeatsABetterOriginalOnlyWhenACopyHasIt() {
        val dub = iptv1080.copy(languages = listOf("ar", "multi"))
        val r = rankCopies(listOf(hdr10Web, dub), "en", hdr10Tv, { null }, prefer = "ar")
        assertEquals(dub, r.pick?.copy)
        assertEquals("no Arabic audio", r.copies.first { it.copy == hdr10Web }.reason)
        // No Arabic copy: the usual ranking.
        assertEquals(hdr10Web, rankCopies(listOf(hdr10Web, iptv1080), "en", hdr10Tv, { null }, prefer = "ar").pick?.copy)
    }

    @Test fun egyptianDubsAreArabicAndGetTheirOwnChip() {
        val egyptian = iptv1080.copy(id = "rd:eg", languages = listOf("ar", EGYPTIAN))
        val gulf = iptv1080.copy(id = "rd:sa", languages = listOf("ar"))
        assertEquals(listOf("en", EGYPTIAN), audioChoices(listOf(hdr10Web, egyptian), "en")) // Arabic only beside another dialect
        assertEquals(listOf("en", EGYPTIAN, "ar"), audioChoices(listOf(hdr10Web, egyptian, gulf), "en"))
        assertEquals(emptyList<String>(), audioChoices(listOf(hdr10Web), "en"))
        assertEquals(egyptian, rankCopies(listOf(hdr10Web, gulf, egyptian), "en", hdr10Tv, { null }, prefer = EGYPTIAN).pick?.copy)
        val choices = CopyChoices(MapStore())
        choices.dubFirst = true
        assertEquals(EGYPTIAN, choices.prefer("movie:1", listOf(gulf, egyptian)))
        choices.language("movie:1", "en")
        assertEquals("en", choices.prefer("movie:1", listOf(gulf, egyptian)))
    }

    @Test fun ae7ATrueHdCorrectionOverridesWhatThatSetupLearnt() {
        val trueHd = hdr10Web.copy(id = "rd:truehd", audio = "truehd", channels = 8, mbps = 60f)
        val copies = listOf(trueHd, hdr10Web)
        val choices = CopyChoices(MapStore())
        choices.learn(hdr10Tv, Correction.PassesTrueHd, failed = true)
        val learnt = choices.rank("movie:1", copies, "en", hdr10Tv) { null }
        assertEquals(trueHd, learnt.pick?.copy) // only demoted: the remux may carry a Dolby track too
        assertTrue(learnt.pick!!.reason, learnt.pick!!.reason.startsWith("TrueHD didn’t play here"))

        choices.correct(hdr10Tv, Correction.PassesTrueHd, true)
        val corrected = choices.rank("movie:1", copies, "en", hdr10Tv) { null }
        assertEquals(trueHd, corrected.pick?.copy)
        assertEquals("best for this setup", corrected.pick?.reason)
    }

    @Test fun ae9AnUnknownDolbyVisionProfileIsOfferedNotPlayed() {
        val dvUnknown = dvRemux.copy(dvProfile = null)
        val r = rank(listOf(dvUnknown, hdr10Web), oled)
        assertEquals(hdr10Web, r.pick?.copy)
        assertEquals(dvUnknown, r.offer?.copy)
        assertEquals(Support.Unsure, r.offer?.support)
    }

    @Test fun marksHoldPerSetupAndRealDebridMarksLapseAfter30Days() {
        var now = 1_000_000_000_000L
        val store = MapStore()
        val choices = CopyChoices(store) { now }
        choices.mark(hdr10Tv, hdr10Web, works = false)
        choices.mark(hdr10Tv, iptv1080, works = false)
        val r = choices.rank("movie:1", listOf(hdr10Web, iptv1080), "en", hdr10Tv) { null }
        assertNull(r.pick)
        assertTrue(r.copies[0].reason, r.copies[0].reason.startsWith("failed here on"))
        assertEquals(hdr10Web, choices.rank("movie:1", listOf(hdr10Web, iptv1080), "en", oled) { null }.pick?.copy)

        now += 31L * 24 * 3_600_000
        val later = CopyChoices(store) { now } // a fresh load prunes too
        assertEquals(hdr10Web, later.rank("movie:1", listOf(hdr10Web, iptv1080), "en", hdr10Tv) { null }.pick?.copy)
        assertEquals(Support.Works, later.rank("movie:1", listOf(iptv1080), "en", hdr10Tv) { null }.copies[0].support) // an IPTV failure lapses after a day
    }

    @Test fun aPinBeatsTheRanking() {
        val choices = CopyChoices(MapStore())
        choices.pin("movie:1", iptv1080.id)
        assertEquals(iptv1080, choices.rank("movie:1", listOf(hdr10Web, iptv1080), "en", hdr10Tv) { null }.pick?.copy)
        assertEquals(hdr10Web, choices.rank("movie:2", listOf(hdr10Web, iptv1080), "en", hdr10Tv) { null }.pick?.copy)
    }

    @Test fun anUntestedOriginalPlaysBeforeAWorkingDubWhichIsTheWayBack() {
        val dvUnknown = dvRemux.copy(dvProfile = null)
        val dub = hdr10Web.copy(languages = listOf("fr"))
        val r = rank(listOf(dub, dvUnknown, dvUnknown.copy(id = "rd:dv2", mbps = 30f)))
        assertEquals(dvUnknown, r.pick?.copy)
        assertEquals(dub, r.fallback?.copy)
        assertNull(r.offer)
    }

    @Test fun tiesGoToEnglishSubtitlesForAForeignFilmThenRealDebrid() {
        val iptvTwin = hdr10Web.copy(id = "iptv:2", source = Iptv)
        assertEquals(hdr10Web, rank(listOf(iptvTwin, hdr10Web)).pick?.copy)

        val subbed = iptvTwin.copy(englishSubs = true)
        assertEquals(subbed, rank(listOf(hdr10Web, subbed), original = "ko").pick?.copy)
        val subbed720 = iptv1080.copy(resolution = 720, englishSubs = true)
        assertEquals(hdr10Web, rank(listOf(subbed720, hdr10Web), original = "ko").pick?.copy)
    }
}
