package com.mohamed3on.iptv.player

import com.mohamed3on.iptv.data.KeyValueStore
import com.mohamed3on.iptv.player.Capacity.Source
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One copy of a film or episode, from IPTV or Real-Debrid, with what is known about it (KTD5). Codec
 * names are ffprobe's; an unknown is empty or 0 and gets the benefit of the doubt.
 */
data class Copy(
    /** Unique within the title and stable across plays: marks and pins key on it. */
    val id: String,
    val source: Source,
    /** Nominal lines: 2160, 1080, 720, 480 (a 3840x1600 scope film is 2160); 0 when unknown. */
    val resolution: Int = 0,
    /** "DV", "HDR10+", "HDR10", "HLG"; empty is SDR. A Dolby Vision copy also lists the layer it falls back to, when named. */
    val hdr: List<String> = emptyList(),
    /** The Dolby Vision profile (5, 7, 8) when known. */
    val dvProfile: Int? = null,
    /** "hevc", "h264", "av1", "vp9", "mpeg2video". */
    val codec: String = "",
    /** The main audio track: "eac3", "ac3", "aac", "truehd", "dts", "dtshd", "dtsx", ... */
    val audio: String = "",
    val channels: Int = 0,
    val atmos: Boolean = false,
    /** Audio languages (ISO 639-1, "multi" for MULTi); empty means the original only. */
    val languages: List<String> = emptyList(),
    /** Known to embed English subtitles (the IPTV probe or a release tag). */
    val englishSubs: Boolean = false,
    /** Average bitrate (size over runtime, or measured); 0 when unknown. */
    val mbps: Float = 0f,
    /** Torrent seeders (Real-Debrid copies): popularity, a last tiebreak only, since a ready copy streams from Real-Debrid. */
    val seeds: Int = 0,
)

/** KTD6's sort; the order is the ranking's. */
enum class Support { Works, Unsure, Wont }

/** A judgement on one copy for one setup (KTD7): it played, or it failed for good. Real-Debrid marks lapse. */
@Serializable
data class Mark(val works: Boolean, val at: Long, val expires: Long = Long.MAX_VALUE)

/** The owner's fixes to what the setup reports (R14), kept per setup fingerprint. */
@Serializable
enum class Correction { PassesTrueHd, PassesDts, IgnoreBandwidth }

/** One copy's place in a [Ranking]. */
class Ranked internal constructor(
    val copy: Copy,
    val support: Support,
    val mark: Mark?,
    /** In the title's original language (R2). */
    val original: Boolean,
    /** Within the typical bandwidth of its source (R4). */
    val fits: Boolean,
    val pinned: Boolean,
    internal val why: String,
    internal val typical: Float?,
    internal val dvMissing: Boolean,
    internal val hdr: Int,
    internal val bits: Float,
    /** [pictureTier]; 4 when unknown. */
    internal val tier: Int,
    internal val sound: Int,
    internal val subs: Boolean,
) {
    /** Why it ranks where it does, for Versions (R12): "Dolby Vision profile unknown", "needs ~75 Mbps, typical 40", "dub". */
    var reason = ""
        internal set
}

/** The copies best first (KTD15), and the few U9 acts on. */
class Ranking internal constructor(
    val copies: List<Ranked>,
    /** What Play uses; null when every copy is ruled out. */
    val pick: Ranked?,
    /** R28: the best untested copy that would beat a working pick, offered once. */
    val offer: Ranked?,
    /** R28: when the pick is untested, the working copy Back returns to. */
    val fallback: Ranked?,
    /** R10: the best working copy that only the bandwidth ruled out. */
    val heavier: Ranked?,
    /** The audio language the copies were ranked for: the title's chosen one (or "Arabic dub first"); null for its original. */
    val language: String? = null,
) {
    /**
     * [r]'s reason worded against [plays], the copy Play opens, when that isn't [pick]
     * (a Real-Debrid copy ranked above it isn't ready): else it would read "lower bitrate" on what plays.
     */
    fun reasonFor(r: Ranked, plays: String?): String {
        val p = copies.firstOrNull { it.copy.id == plays }?.takeIf { it !== pick } ?: return r.reason
        return reason(r, p, language)
    }

    /** R9: the best-ranked working copy lighter than [current], for a step-down. */
    fun lighter(current: Copy): Ranked? = copies.firstOrNull {
        it.support == Support.Works && it.copy.id != current.id &&
            if (it.copy.mbps > 0f && current.mbps > 0f) it.copy.mbps < current.mbps else it.copy.resolution < current.resolution
    }
}

/**
 * Ranks a title's [copies] for [setup] (U6): language, then support, then bandwidth, picture, sound,
 * English subtitles for a foreign film, and Real-Debrid on a tie (KTD15); a [pin] beats all of it. With [prefer], a title
 * that has a copy in that language ranks those copies as its language instead.
 * [marks] are this setup's, by copy id; [typicalMbps] is per source, null when unknown (optimistic). Audio the
 * setup doesn't report passing is taken to play: a release name lists only its headline track, and a remux often
 * carries a Dolby one too. Audio it [learnt] doesn't play (the audio's correction, when) only ranks the copy's sound
 * lowest; a copy that itself had no playable track is marked as any failure is.
 */
fun rankCopies(
    copies: List<Copy>,
    originalLanguage: String?,
    setup: SetupProfile,
    typicalMbps: (Source) -> Float?,
    marks: Map<String, Mark> = emptyMap(),
    corrections: Set<Correction> = emptySet(),
    pin: String? = null,
    learnt: Map<Correction, Long> = emptyMap(),
    prefer: String? = null,
    lacking: Map<String, Set<String>> = emptyMap(),
): Ranking {
    val p = setup.corrected(corrections)
    // A copy found to lack a language (its file had no such track) doesn't count as speaking it.
    val has = { c: Copy, l: String -> speaks(c, l, originalLanguage) && audioTrack(l) !in lacking[c.id].orEmpty() }
    val want = prefer?.takeIf { l -> copies.any { has(it, l) } }
    val ignore = Correction.IgnoreBandwidth in corrections
    val iptv = if (ignore) null else typicalMbps(Source.Iptv)
    val rd = if (ignore) null else typicalMbps(Source.Debrid)
    val ranked = copies.map { judge(it, p, marks[it.id], originalLanguage, want?.let { l -> has(it, l) }, if (it.source == Source.Iptv) iptv else rd, it.id == pin, learnt) }.sortedWith(ORDER)
    val pick = ranked.firstOrNull()?.takeIf { it.pinned || it.support != Support.Wont }
    for (r in ranked) r.reason = reason(r, pick, want)
    val working = pick?.takeIf { it.support == Support.Works && !it.pinned }
    return Ranking(
        ranked, pick,
        offer = working?.let { w -> ranked.firstOrNull { it.support == Support.Unsure && it.original == w.original && versus(it, w) < 0 } },
        fallback = pick?.takeIf { it.support == Support.Unsure }?.let { ranked.firstOrNull { it.support == Support.Works } },
        heavier = working?.takeIf { it.fits }?.let { w ->
            ranked.firstOrNull { it.support == Support.Works && it.original == w.original && !it.fits && versus(it, w, fit = false) < 0 }
        },
        want,
    )
}

/** What the setup handles once the owner's [corrections] are applied. */
fun SetupProfile.corrected(corrections: Set<Correction>): SetupProfile {
    var extra = emptySet<Passthrough>()
    if (Correction.PassesTrueHd in corrections) extra = extra + Passthrough.TrueHd
    if (Correction.PassesDts in corrections) extra = extra + setOf(Passthrough.Dts, Passthrough.DtsHd, Passthrough.DtsX)
    return if (extra.isEmpty()) this else copy(passthrough = passthrough + extra)
}

/** The correction that lets [audio] pass, the key a failure to play it is learnt under: TrueHD's, or DTS's for every DTS kind. */
fun audioFamily(audio: String): Correction? = when (audio) {
    "truehd" -> Correction.PassesTrueHd
    "dts", "dtshd", "dtsx" -> Correction.PassesDts
    else -> null
}

private fun judge(c: Copy, p: SetupProfile, mark: Mark?, language: String?, speaksWanted: Boolean?, typical: Float?, pinned: Boolean, learnt: Map<Correction, Long>): Ranked {
    // Profile 7 plays as its HDR10 base layer (MoviePlayer's Dv7AsHevc): the stick decodes no profile 7 Dolby Vision.
    val dv = "DV" in c.hdr && c.dvProfile != 7
    val hdr10 = "HDR10" in c.hdr || "HDR10+" in c.hdr || c.dvProfile == 7
    val shown = when {
        dv && Hdr.DolbyVision in p.hdr -> 5
        "HDR10+" in c.hdr && Hdr.Hdr10Plus in p.hdr -> 4
        hdr10 && Hdr.Hdr10 in p.hdr -> 3
        "HLG" in c.hdr && Hdr.Hlg in p.hdr -> 2
        dv -> 1 // no named fallback layer: the stick converts it (a profile 5 file plays on today's HDR10 TV)
        else -> 0
    }
    val mime = MIMES[c.codec]
    val needs = PASSTHROUGH[c.audio]
    val failed = audioFamily(c.audio)?.let { learnt[it] }?.takeIf { needs != null && needs !in p.passthrough }
    var support = Support.Works
    val why = when {
        mark != null -> if (mark.works) "works here" else "failed here on ${DAY.format(Instant.ofEpochMilli(mark.at))}".also { support = Support.Wont }
        mime != null && mime !in p.videoCodecs -> "${c.codec.uppercase()} not decoded here".also { support = Support.Wont }
        dv && c.dvProfile != null && c.dvProfile !in p.dolbyVisionProfiles -> "Dolby Vision profile ${c.dvProfile} not supported".also { support = Support.Wont }
        // A TV without Dolby Vision shows a named HDR10 layer: the profile doesn't matter there (a hybrid remux, say).
        dv && c.dvProfile == null && (Hdr.DolbyVision in p.hdr || !hdr10) -> "Dolby Vision profile unknown".also { support = Support.Unsure }
        !dv && hdr10 && shown == 0 -> "TV shows no HDR10".also { support = Support.Unsure }
        failed != null -> "${AUDIO_FAMILIES[audioFamily(c.audio)]} didn’t play here on ${DAY.format(Instant.ofEpochMilli(failed))}"
        else -> ""
    }
    val channels = if (p.maxChannels > 0) min(c.channels, p.maxChannels) else c.channels
    // Atmos over DD+ needs E-AC3-JOC passthrough; a TrueHD copy that plays at all is passed through.
    val atmos = c.atmos && (c.audio != "eac3" || Passthrough.EAc3Joc in p.passthrough)
    return Ranked(
        c, support, mark,
        original = speaksWanted ?: (language == null || c.languages.isEmpty() || language in c.languages || "multi" in c.languages),
        fits = typical == null || c.mbps <= 0f || c.mbps <= typical,
        pinned = pinned, why = why, typical = typical,
        dvMissing = dv && Hdr.DolbyVision !in p.hdr,
        hdr = shown,
        bits = c.mbps * (GAIN[c.codec] ?: if (c.resolution >= 2160) 2f else 1f),
        tier = pictureTier(c) ?: 4,
        sound = if (failed != null) 0 else if (atmos) 3 else if (channels >= 6) 2 else 1,
        subs = language != null && language != "en" && c.englishSubs,
    )
}

/**
 * The picture as the server's `pictureTier` grades it, 0 (Best) to 3 (Low): resolution, capped by codec-adjusted bits per
 * pixel when the bitrate is known; null when the resolution isn't.
 */
fun pictureTier(c: Copy): Int? {
    val (w, h) = when {
        c.resolution >= 2000 -> 3840 to 2160
        c.resolution >= 1000 -> 1920 to 1080
        c.resolution >= 700 -> 1280 to 720
        c.resolution > 0 -> 720 to 480
        else -> return null
    }
    val cap = when {
        h >= 2000 -> 0
        h >= 1000 -> 1
        h >= 700 -> 2
        else -> 3
    }
    val gain = GAIN[c.codec] ?: if (h >= 2000) 2f else 1f
    val bpp = c.mbps * 1_000_000 * gain / (w * h * 24f)
    val bits = when {
        c.mbps <= 0f || bpp >= 0.24f -> 0
        bpp >= 0.14f -> 1
        bpp >= 0.07f -> 2
        else -> 3
    }
    return maxOf(cap, bits)
}

/** Where [a] first differs from [b] in KTD15's quality keys (picture tier between resolution and HDR): -k when [a] is better at key k, +k when worse, 0 on a tie. */
private fun versus(a: Ranked, b: Ranked, fit: Boolean = true): Int = when {
    fit && a.fits != b.fits -> if (a.fits) -1 else 1
    a.copy.resolution != b.copy.resolution -> if (a.copy.resolution > b.copy.resolution) -2 else 2
    // A starved encode loses to a full one before HDR flavours count: 4 Mbps HDR10+ is no match for a 72 Mbps HDR10 remux.
    a.tier != b.tier -> if (a.tier < b.tier) -4 else 4
    a.hdr != b.hdr -> if (a.hdr > b.hdr) -3 else 3
    a.bits != b.bits -> if (a.bits > b.bits) -4 else 4
    a.sound != b.sound -> if (a.sound > b.sound) -5 else 5
    a.subs != b.subs -> if (a.subs) -6 else 6
    a.copy.source != b.copy.source -> if (a.copy.source == Source.Debrid) -7 else 7
    a.copy.seeds != b.copy.seeds -> if (a.copy.seeds > b.copy.seeds) -8 else 8
    else -> 0
}

/** The box's tag for an Egyptian Arabic dub (ISO 639-3), listed beside "ar". */
const val EGYPTIAN = "arz"

/** Whether [c] has audio in [lang]: listed (an Egyptian dub is Arabic too), or the title's [original] in a copy that lists none or "multi". */
fun speaks(c: Copy, lang: String, original: String?): Boolean =
    lang in c.languages || (lang == "ar" && EGYPTIAN in c.languages) || (lang == original && (c.languages.isEmpty() || "multi" in c.languages))

/**
 * The audio languages a title page offers (its original, English, Egyptian, Arabic, in that order): those some copy has, and only when
 * there are two or more. Arabic shows beside Egyptian only when a copy has another dialect.
 */
fun audioChoices(copies: List<Copy>, original: String?): List<String> =
    listOfNotNull(original, "en", EGYPTIAN, "ar").distinct().filter { l ->
        copies.any { if (l == "ar") "ar" in it.languages && EGYPTIAN !in it.languages else speaks(it, l, original) }
    }.takeIf { it.size > 1 }.orEmpty()

/** "English", "Egyptian", "Arabic", else the language's own English name. */
fun audioName(lang: String): String =
    if (lang == EGYPTIAN) "Egyptian" else Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH).ifEmpty { lang.uppercase() }

/** The track to start on for [lang]: an Egyptian dub's is tagged Arabic. */
fun audioTrack(lang: String): String = if (lang == EGYPTIAN) "ar" else lang

private val ORDER = Comparator<Ranked> { a, b ->
    when {
        a.pinned != b.pinned -> if (a.pinned) -1 else 1
        (a.support == Support.Wont) != (b.support == Support.Wont) -> if (a.support == Support.Wont) 1 else -1
        a.original != b.original -> if (a.original) -1 else 1
        a.support != b.support -> a.support.compareTo(b.support)
        else -> versus(a, b)
    }
}

private fun reason(r: Ranked, pick: Ranked?, want: String?): String = when {
    r === pick -> if (r.pinned) "Your choice" else r.why.ifEmpty { "best for this setup" }
    pick == null || r.support == Support.Wont -> r.why
    !r.original && pick.original -> if (want != null) "no ${audioName(want)} audio" else "dub"
    r.support == Support.Unsure -> r.why
    // Every reason reads this copy against the pick ("1080p vs 4K"); a copy that beats a pinned pick says so.
    else -> versus(r, pick).let { k ->
        when {
            k > 0 -> difference(k, r, pick)
            k < 0 -> difference(-k, r, pick) + if (pick.pinned) " · your choice is pinned" else ""
            else -> "same as the pick"
        }
    }
}

/** How [r] differs from [p] at [versus]' key [k], this copy first: "1080p vs 4K", "Low picture vs Best (4 vs 72 Mbps)". */
private fun difference(k: Int, r: Ranked, p: Ranked): String = when (k) {
    1 -> if (!r.fits) "needs ~${r.copy.mbps.roundToInt()} Mbps, typical ${r.typical?.roundToInt()}" else "fits the bandwidth, the pick needs ~${p.copy.mbps.roundToInt()} Mbps"
    2 -> "${lines(r.copy)} vs ${lines(p.copy)}"
    3 -> if (r.dvMissing && r.hdr < p.hdr) "TV can’t show its Dolby Vision" else "${HDR_SHOWN[r.hdr]} vs ${HDR_SHOWN[p.hdr]}"
    4 -> if (r.tier != p.tier) "${tierName(r.tier)} picture vs ${tierName(p.tier)} (${rate(r, p)})" else rate(r, p)
    5 -> if (r.sound == 0) r.why else "${SOUNDS[r.sound]} vs ${SOUNDS[p.sound]}"
    6 -> if (r.subs) "English subtitles, the pick has none" else "no English subtitles"
    7 -> if (r.copy.source == Source.Iptv) "same, on IPTV" else "same, on Debrid"
    8 -> "same, ${r.copy.seeds} vs ${p.copy.seeds} seeders"
    else -> ""
}

private fun lines(c: Copy) = when {
    c.resolution >= 2000 -> "4K"
    c.resolution > 0 -> "${c.resolution}p"
    else -> "resolution unknown"
}

/** "4 vs 72 Mbps", with codecs when they differ (bitrates compare codec-adjusted). */
private fun rate(r: Ranked, p: Ranked): String {
    fun mbps(c: Copy) = if (c.mbps > 0f) "${c.mbps.roundToInt()}" else "?"
    return if (r.copy.codec != p.copy.codec && r.copy.codec.isNotEmpty() && p.copy.codec.isNotEmpty()) {
        "${mbps(r.copy)} Mbps ${r.copy.codec.uppercase()} vs ${mbps(p.copy)} Mbps ${p.copy.codec.uppercase()}"
    } else "${mbps(r.copy)} vs ${mbps(p.copy)} Mbps"
}

/** The picture tiers' names, best first; [pictureTier] indexes them. */
val TIER_NAMES = listOf("Best", "High", "Medium", "Low")
private fun tierName(t: Int) = TIER_NAMES.getOrElse(t) { "Unknown" }
private val HDR_SHOWN = mapOf(5 to "Dolby Vision", 4 to "HDR10+", 3 to "HDR10", 2 to "HLG", 1 to "converted Dolby Vision", 0 to "SDR")
private val SOUNDS = mapOf(1 to "stereo", 2 to "surround", 3 to "Atmos")

private val MIMES = mapOf(
    "hevc" to "video/hevc", "h264" to "video/avc", "av1" to "video/av01", "vp9" to "video/x-vnd.on2.vp9", "mpeg2video" to "video/mpeg2", "vc1" to "video/wvc1",
)
/** H.264-equivalent bitrate, as `pictureTier` on the server scales it. */
private val GAIN = mapOf("h264" to 1f, "hevc" to 2f, "vp9" to 1.8f, "av1" to 2.4f, "mpeg2video" to 0.6f)
/** Audio the stick can't decode: it plays only if passed through. */
private val PASSTHROUGH = mapOf("truehd" to Passthrough.TrueHd, "dts" to Passthrough.Dts, "dtshd" to Passthrough.DtsHd, "dtsx" to Passthrough.DtsX)
private val AUDIO_FAMILIES = mapOf(Correction.PassesTrueHd to "TrueHD", Correction.PassesDts to "DTS")
/** "5 Nov", as marks and learnt failures are dated. */
internal val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(ZoneId.systemDefault())

/**
 * The judgements the ranker reads, kept per setup or title in [store] and in memory after the first
 * read: marks (KTD7) per fingerprint and copy, corrections (R14) per fingerprint, pins (KTD12) per title.
 */
class CopyChoices(private val store: KeyValueStore, private val now: () -> Long = System::currentTimeMillis) {
    private val marks: MutableMap<String, MutableMap<String, Mark>> by lazy {
        read<Map<String, Map<String, Mark>>>(MARKS).orEmpty()
            // IPTV failures saved before they lapsed (never-expiring) lapse a day after they were made, like new ones.
            .mapValuesTo(mutableMapOf()) { (_, m) ->
                m.mapValues { (id, k) -> if (id.startsWith("iptv:") && !k.works && k.expires == Long.MAX_VALUE) k.copy(expires = k.at + IPTV_FAIL_MS) else k }
                    .filterValues { it.expires > now() }.toMutableMap()
            }
            .apply { values.removeAll { it.isEmpty() } }
    }
    private val corrections: MutableMap<String, Set<Correction>> by lazy { read<Map<String, Set<Correction>>>(CORRECTIONS).orEmpty().toMutableMap() }
    private val pins: MutableMap<String, String> by lazy { read<Map<String, String>>(PINS).orEmpty().toMutableMap() }
    private val lacking: MutableMap<String, Set<String>> by lazy { read<Map<String, Set<String>>>(LACKING).orEmpty().toMutableMap() }
    private val languages: MutableMap<String, String> by lazy { read<Map<String, String>>(LANGUAGES).orEmpty().toMutableMap() }
    private val learnt: MutableMap<String, Map<Correction, Long>> by lazy { read<Map<String, Map<Correction, Long>>>(LEARNT).orEmpty().toMutableMap() }

    /** Ranks [copies] of [titleId] for [setup] with this setup's marks, corrections and learnt audio, the title's pin and its audio language. */
    fun rank(titleId: String, copies: List<Copy>, originalLanguage: String?, setup: SetupProfile, typicalMbps: (Source) -> Float?) =
        rankCopies(copies, originalLanguage, setup, typicalMbps, marks(setup), corrections(setup), pin(titleId), learnt(setup), prefer(titleId, copies), lacking())

    /** The audio language for [titleId]: chosen on its page, else with "Arabic dub first" an Egyptian dub when it has one, else Arabic. */
    @Synchronized fun prefer(titleId: String, copies: List<Copy>): String? = languages[titleId]
        ?: if (dubFirst) EGYPTIAN.takeIf { copies.any { EGYPTIAN in it.languages } } ?: "ar" else null

    /** Copy ids with the audio languages their files were found to lack. */
    @Synchronized fun lacking(): Map<String, Set<String>> = lacking

    /** Records that [copy]'s file has no [lang] track, whatever its name claims. */
    @Synchronized fun lacks(copy: Copy, lang: String) {
        lacking[copy.id] = lacking[copy.id].orEmpty() + lang
        write<Map<String, Set<String>>>(LACKING, lacking)
    }

    /** Chooses [lang] as [titleId]'s audio (null: back to the default); a pinned copy gives way to it. */
    @Synchronized fun language(titleId: String, lang: String?) {
        if (lang == null) languages.remove(titleId) else languages[titleId] = lang
        write<Map<String, String>>(LANGUAGES, languages)
        pin(titleId, null)
    }

    /** The owner's "Arabic dub first" (Options): every title's copies with Arabic audio rank first and play in Arabic. */
    var dubFirst: Boolean
        @Synchronized get() = store.get(DUB_FIRST) == "1"
        @Synchronized set(on) = store.put(DUB_FIRST, if (on) "1" else null)

    /** The audio [setup] was found not to play (no track of a file could), by the correction that would pass it, with when. */
    @Synchronized fun learnt(setup: SetupProfile): Map<Correction, Long> = learnt[setup.fingerprint].orEmpty()

    /** Records that [family]'s audio didn't play on [setup] ([failed]), or forgets that; nothing for a blank reading. */
    @Synchronized fun learn(setup: SetupProfile, family: Correction, failed: Boolean) {
        if (setup.isBlank) return
        val next = learnt(setup).let { if (failed) it + (family to now()) else it - family }
        if (next.isEmpty()) learnt.remove(setup.fingerprint) else learnt[setup.fingerprint] = next
        write<Map<String, Map<Correction, Long>>>(LEARNT, learnt)
    }

    @Synchronized fun marks(setup: SetupProfile): Map<String, Mark> =
        marks[setup.fingerprint]?.filterValues { it.expires > now() }.orEmpty()

    /** Records that [copy] played ([works]) or failed for good on [setup]; nothing for a blank reading. */
    @Synchronized fun mark(setup: SetupProfile, copy: Copy, works: Boolean) {
        if (setup.isBlank) return
        val at = now()
        marks.getOrPut(setup.fingerprint) { mutableMapOf() }[copy.id] =
            // A failed IPTV copy is tried again after a day: a busy or dead provider link can look like a bad file.
            Mark(works, at, when { copy.source == Source.Debrid -> at + RD_MARK_MS; works -> Long.MAX_VALUE; else -> at + IPTV_FAIL_MS })
        write<Map<String, Map<String, Mark>>>(MARKS, marks)
    }

    @Synchronized fun clearMark(setup: SetupProfile, copyId: String) {
        if (marks[setup.fingerprint]?.remove(copyId) != null) write<Map<String, Map<String, Mark>>>(MARKS, marks)
    }

    @Synchronized fun corrections(setup: SetupProfile): Set<Correction> = corrections[setup.fingerprint].orEmpty()

    @Synchronized fun correct(setup: SetupProfile, correction: Correction, on: Boolean) {
        if (setup.isBlank) return
        val next = corrections(setup).let { if (on) it + correction else it - correction }
        if (next.isEmpty()) corrections.remove(setup.fingerprint) else corrections[setup.fingerprint] = next
        write<Map<String, Set<Correction>>>(CORRECTIONS, corrections)
    }

    @Synchronized fun pin(titleId: String): String? = pins[titleId]

    /** Pins [copyId] for [titleId], or clears the pin when null. */
    @Synchronized fun pin(titleId: String, copyId: String?) {
        if (copyId == null) pins.remove(titleId) else pins[titleId] = copyId
        write<Map<String, String>>(PINS, pins)
    }

    private inline fun <reified T> read(key: String): T? = store.get(key)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }
    private inline fun <reified T> write(key: String, value: T) = store.put(key, json.encodeToString(value))

    private companion object {
        const val MARKS = "copies.marks"
        const val CORRECTIONS = "copies.corrections"
        const val PINS = "copies.pins"
        const val LEARNT = "copies.learnt"
        const val DUB_FIRST = "copies.dubFirst"
        const val LANGUAGES = "copies.languages"
        const val LACKING = "copies.lacking"
        const val RD_MARK_MS = 30L * 24 * 3_600_000
        const val IPTV_FAIL_MS = 24L * 3_600_000
        val json = Json { ignoreUnknownKeys = true }
    }
}
