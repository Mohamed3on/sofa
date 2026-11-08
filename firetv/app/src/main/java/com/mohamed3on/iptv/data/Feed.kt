package com.mohamed3on.iptv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The provider's two fronts and the account, from the feed's `route`. */
@Serializable
data class ProviderRoute(val plain: String, val vpn: String, val user: String, val pass: String)

/** One row of `/api/app/channels`. 🏆 copies share [id], [tvgId], [family] and [rank] with the original. */
@Serializable
data class Channel(
    val id: Int,
    val name: String,
    val logo: String = "",
    val group: String,
    val tvgId: String = "",
    val slot: Boolean = false,
    /** The channel's own non-🏆 group; absent for 🏆-only channels. */
    val home: String? = null,
    val family: String,
    /** Position in the family, 0 = best feed. */
    val rank: Int,
    /** Probed video height, null when never measured. */
    val h: Int? = null,
    /** Name-marker quality tier, 0–5. */
    val q: Int = 0,
    val fixture: Boolean = false,
    val badges: List<String> = emptyList(),
    /** The feed's bitrate in Mbps, measured while watching it; null when never measured. */
    val mbps: Float? = null,
    /** The picture detail its bitrate leaves room for: 0 thin, 1 in between or never measured, 2 rich. */
    val detail: Int = 1,
    /** The provider keeps letting this feed down (the server learns it from the watch log). */
    val flaky: Boolean = false,
) {
    val identity get() = ChannelIdentity(id, group)
}

@Serializable
data class Feed(
    val groups: List<String>,
    val route: ProviderRoute,
    val channels: List<Channel>,
    /** The backup provider's fronts and account, when the server has one; its feeds are ids from [BACKUP_ID]. */
    val backup: ProviderRoute? = null,
    /** The Real-Debrid API token, when the server has one ([RealDebrid] makes every call from the stick, KTD2). */
    val realDebrid: String? = null,
    /** The TorBox API token, when the server has one: [TorBox] then stands in for Real-Debrid, every call from the stick. */
    val torbox: String? = null,
    /** Each 🏆 group with a known logo → its small logo for dark grounds ([com.mohamed3on.iptv.ui.CompName]); none from an older server. */
    val compLogos: Map<String, String> = emptyMap(),
    /** Families whose rows sit out of their groups for now (Favourites keeps them; Search and zap context don't use groups). */
    @kotlinx.serialization.Transient val hidden: Set<String> = emptySet(),
) {
    private val byGroup by lazy { channels.groupBy { it.group } }
    @kotlinx.serialization.Transient private val rowsByGroup = java.util.concurrent.ConcurrentHashMap<String, List<Channel>>()
    private val byIdentity by lazy { channels.associateBy { it.identity } }
    private val byId by lazy { channels.groupBy { it.id } }
    private val byFamily by lazy { channels.groupBy { it.family } }

    /**
     * The rows of [group] as the app shows them, in server order (display, zap and banner order):
     * one per channel family, on the family's top-ranked feed in the group, where the family first
     * appears. The server also lists spare feeds (a 🏆 group carries a best feed and a spare of each
     * channel, for TiviMate); a session fails over through the family by itself, so one row is enough.
     */
    fun channelsIn(group: String): List<Channel> = rowsByGroup.getOrPut(group) {
        val all = byGroup[group].orEmpty()
        val rows = if (hidden.isEmpty() || group == FAVOURITES) all else all.filter { it.family !in hidden }
        val best = rows.groupBy { it.family }.mapValues { (_, feeds) -> feeds.minBy { it.rank } }
        rows.distinctBy { it.family }.map { best.getValue(it.family) }
    }

    /** [channel]'s row in its group as shown: itself, or its family's row when it is a spare feed. */
    fun rowOf(channel: Channel): Channel? = channelsIn(channel.group).let { rows ->
        rows.firstOrNull { it.identity == channel.identity } ?: rows.firstOrNull { it.family == channel.family }
    }

    fun find(identity: ChannelIdentity): Channel? = byIdentity[identity]

    /** Every row carrying [streamId]: the original and its 🏆 copies. */
    fun rowsOf(streamId: Int): List<Channel> = byId[streamId].orEmpty()

    /** The feeds of a family, best first, one row per stream (the home-group row over 🏆 copies). */
    fun familyMembers(family: String): List<Channel> =
        byFamily[family].orEmpty().sortedWith(compareBy({ it.rank }, { it.group != it.home })).distinctBy { it.id }
}

/** A feed the provider only lights for events: "TNT SPORTS (EVENT)", "(LIVE-EVENT)", "(EVENT ONLY)", "( SOLO EVENTOS)". */
private val EVENT_ONLY = Regex("""\(\s*(?:live[- ]?)?events?(?:\s+only)?\s*\)|\(\s*solo\s+eventos\s*\)""", RegexOption.IGNORE_CASE)

/** Whether [channel] is an event-only feed (not an event slot, whose name is the event itself). */
fun isEventOnly(channel: Channel) = !channel.slot && EVENT_ONLY.containsMatchIn(channel.name)

private val json = Json { ignoreUnknownKeys = true }

/** Parses and validates a channel feed; throws on anything that is not a feed with at least one channel. */
fun parseFeed(text: String): Feed {
    val feed = json.decodeFromString(Feed.serializer(), text)
    require(feed.channels.isNotEmpty()) { "feed has no channels" }
    return feed
}

private val FEED_PREFIX = Regex("""^[^\s:|]{1,6}[:|]\s*""")
private const val SUP_FROM = "ᴬᴮᶜᴰᴱᶠᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿˢᵀᵁⱽᵂᵃᵇᵈᵉᵍʰⁱʲᵏˡᵐⁿᵒᵖʳᵗᵘᵛʷˣʸᶻ⁰¹²³⁴⁵⁶⁷⁸⁹"
private const val SUP_TO = "ABCDEFGHIJKLMNOPRSTUVWabdeghijklmnoprtuvwxyz0123456789"
/** Words that only tell feeds of one channel apart (quality, codec, fps, uplink; "3840P+" too); the badges say it better. */
private val FEED_WORDS = setOf(
    "8k", "4k", "hd", "sd", "fhd", "uhd", "hdr", "raw", "vip", "hevc", "fps", "50", "60",
    "3840p", "2160p", "1080p", "720p", "(sat)", "(mobil)", "(mobile)", "(bk)", "sat",
)
/** All-caps words of 4+ letters are re-cased ("SPORTS" → "Sports"), except these acronyms. */
private val ACRONYMS = setOf("DAZN", "ESPN", "UEFA", "FIFA", "MUTV", "LFCTV", "NBCSN")
/** Short all-caps words that are words, not acronyms. */
private val SHORT_WORDS = setOf("SKY", "ONE", "TWO", "TEN", "MAX", "MIX", "THE", "AND")
private val SPACES = Regex("\\s+")
private val BALL_AS_O = Regex("(?<=[A-Z])⚽|⚽(?=[A-Z])")
private val FPS_WORD = Regex("(?i)\\d+fps")
private val CONNECTOR = Regex("^[&+/|·:-]+$")
/** An event slot's name is fields between bars: "Next | Lorient - Brest | Ligue 1 | 2026-11-01 | 20:00 (GMT) | 8K EXCLUSIVE | GB: DAZN PPV 58". */
private val SLOT_FIELDS = Regex("""\s+\|\s+""")
/** Fields that say when or where rather than what: a date, a time, a quality pitch, the provider's own channel code. */
private val SLOT_NOISE = Regex("""^(\d{4}-\d{2}-\d{2}|\d{1,2}:\d{2}\b.*|.*\bEXCLUSIVE\b.*|[A-Z]{2,3}:\s.*)$""")
/** Names are cleaned once each: rows, tiles and the banner ask for the same few hundred over and over. */
private val displayNames = java.util.concurrent.ConcurrentHashMap<String, String>()

/**
 * A channel name as people say it: the provider's feed prefix and quality markers dropped
 * ("SS: beIN SP⚽RTS 6 4K" → "beIN Sports 6"), shouting words re-cased, acronyms kept.
 * Event slots, whose name is the event, keep their wording, without the date, time and feed code.
 */
fun displayName(raw: String): String = displayNames.getOrPut(raw) { cleanName(raw) }

/** Marks a competition's group ("🏆 Premier League"), which the groups column lists after Favourites. */
const val TROPHY = "🏆"

/** A channel group's name as shown: the 🏆 marker is for sorting, not for reading. */
fun groupName(group: String) = group.removePrefix(TROPHY).trimStart()

private fun cleanName(raw: String): String {
    // An event slot reads as the event: "Next · Lorient - Brest · Ligue 1".
    val fields = raw.split(SLOT_FIELDS)
    if (fields.size >= 3) fields.filterNot { SLOT_NOISE.matches(it.trim()) }.joinToString(" · ").takeIf { it.isNotBlank() }?.let { return it }
    val unsuper = { w: String -> w.map { c -> SUP_FROM.indexOf(c).let { if (it >= 0) SUP_TO[it] else c } }.joinToString("") }
    val words = raw.replace(FEED_PREFIX, "").split(SPACES).mapNotNull { token ->
        // a word spelled in superscript ("ᴬʳᵉⁿᵃ") arrives as "Arena"; markers ("ᴿᴬᵂ", "³⁸⁴⁰ᴾ") are dropped below
        val spelled = token.isNotEmpty() && token.all { it in SUP_FROM }
        val w = unsuper(token).replace(BALL_AS_O, "O").replace("⚽", "o")
        val letters = w.filter { it.isLetter() }
        when {
            w.isEmpty() || w.lowercase().trimEnd('+') in FEED_WORDS || FPS_WORD.matches(w) -> null
            letters.isNotEmpty() && letters.all { it in 'A'..'Z' } && w !in ACRONYMS && (letters.length >= 4 || w in SHORT_WORDS) ->
                w.lowercase().replaceFirstChar(Char::uppercase)
            spelled -> w.replaceFirstChar(Char::uppercase)
            else -> w
        }
    }
    // A connector left dangling once markers are gone ("SKY SPORTS 1 ⁴ᵏ & ³⁸⁴⁰ᴾ") goes too; "Drake & Josh" keeps its.
    val shown = words.dropWhile { CONNECTOR.matches(it) }.dropLastWhile { CONNECTOR.matches(it) }.joinToString(" ").trim()
    return if (shown.isEmpty()) raw.trim() else shown
}
