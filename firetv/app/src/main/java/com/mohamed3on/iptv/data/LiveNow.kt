package com.mohamed3on.iptv.data

import com.mohamed3on.iptv.ui.feedFlag

/** A Live now row: a confirmed match ([programme] set) or an unconfirmed event slot. [via] is the 🏆 row it was found on. */
data class LiveEntry(
    val title: String, val programme: Programme?, val via: Channel, val tune: Channel, val comp: String? = null,
    /** Other channels showing the same match, one per family, best first; listed under its row. */
    val alsoOn: List<Channel> = emptyList(),
) {
    val confirmed get() = programme != null
}

/** Live now (R22): confirmed matches, then unconfirmed event slots. */
data class LiveNow(val matches: List<LiveEntry>, val slots: List<LiveEntry>) {
    val entries get() = matches + slots
    /** The zap context a Live now tune carries: Up/Down walk these, one stop per channel. */
    val context get() = entries.map { it.tune }.distinctBy { it.family }

    /** The match [channel] shows, as its row's channel or one listed under it. */
    fun matchOf(channel: Channel) = entries.firstOrNull { e -> e.tune.family == channel.family || e.alsoOn.any { it.family == channel.family } }

    /** What Up/Down walk after tuning [channel]: the channels showing its match when there are several, else [context]. */
    fun contextFor(channel: Channel): List<Channel> = matchOf(channel)?.takeIf { it.alsoOn.isNotEmpty() }?.let { SameMatch(listOf(it.tune) + it.alsoOn) } ?: context
}

/** One match's channels as a zap context, so the banner can say so. */
class SameMatch(channels: List<Channel>) : List<Channel> by channels

/**
 * Live now from the guide's [airing] matches: one row per (normalized title, start) on a channel in a
 * 🏆 group, by kick-off, each with its channels best first ([BEST_FIRST]), every one on its family's
 * top-ranked feed, abroad ones only when their picture holds up ([withinBar]). Then 🏆 event slots whose
 * name reads as a fixture, one per name.
 */
fun Feed.liveNow(airing: List<Airing>): LiveNow {
    val byTvgId = airing.groupBy { it.tvgId }
    val trophy = channels.filter { it.group.startsWith(TROPHY) }
    // Once per channel: its 🏆 copies share the listing. Other channels with the same title join it in sameMatchOnce.
    val seen = HashSet<Triple<String, String, Long>>()
    val matches = buildList {
        for (c in trophy) {
            if (c.tvgId.isEmpty()) continue
            for (a in byTvgId[c.tvgId].orEmpty()) {
                if (seen.add(Triple(c.family, a.norm, a.programme.start))) add(LiveEntry(a.programme.title, a.programme, c, bestOf(c), a.comp))
            }
        }
    }
    // An event channel named for a confirmed match, in any group ("Live | Atlético Madrid vs. Barcelona | … | DE: SOCCER PPV
    // 104"), is listed under it: a team word from each side, so the club channel "LA LIGA - ATLETICO MADRID" is not.
    val named = channels.filter { it.slot && it.fixture }
    val folded = HashSet<String>()
    val confirmed = sameMatchOnce(matches).map { m ->
        val (home, away) = sidesOf(m.title) ?: return@map m
        val more = named.filter { s -> teamWords(s.name).let { w -> w.any { it in home } && w.any { it in away } } }
        folded += more.map { it.family }
        val alsoOn = (m.alsoOn + more.map { bestOf(it) }).distinctBy { it.family }.filter { it.family != m.tune.family }
        m.copy(alsoOn = alsoOn)
    }.map { m ->
        val all = withinBar((listOf(m.tune) + m.alsoOn).sortedWith(BEST_FIRST))
        m.copy(tune = all.first(), alsoOn = all.drop(1))
    }.sortedBy { it.programme!!.start }
    val slots = trophy.filter { it.slot && it.fixture && it.family !in folded }.distinctBy { it.name }.map { LiveEntry(it.name, null, it, bestOf(it)) }
    return LiveNow(confirmed, slots)
}

/**
 * A match's channels as its 🏆 group orders them: always-on channels before event slots (an idle slot's marker
 * can't be verified), steady before ones the provider keeps letting down, then the server's effective picture
 * ([Picture]), then language.
 */
private val PICTURE = compareBy(BETTER_PICTURE) { c: Channel -> c.pic }
private val BEST_FIRST = compareBy<Channel>({ it.slot }, { it.flaky }).then(PICTURE).thenBy { languageRank(it) }

/**
 * A match's channels from outside the preferred regions only when 1080p or better and at least as good a
 * picture as its worst in-region channel; all of them when no in-region channel shows it. Order is kept.
 */
internal fun withinBar(channels: List<Channel>): List<Channel> {
    val worst = channels.filter { !it.abroad }.maxWithOrNull(PICTURE) ?: return channels
    return channels.filter { !it.abroad || (it.pic.h >= 1080 && PICTURE.compare(it, worst) <= 0) }
}

/** English (UK and Ireland before the US), then Arabic, then German and Spanish, then the rest. */
private fun languageRank(c: Channel) = when (feedFlag(c)) {
    "🇬🇧", "🇮🇪" -> 0
    "🇺🇸", "🇨🇦", "🇦🇺", "🇳🇿" -> 1
    "🇪🇬" -> 2
    "🇩🇪", "🇪🇸" -> 3
    else -> 4
}

/** A fixture's two sides as team words ("Atletico Madrid vs Barcelona" → atletico madrid, barcelona), or null. */
private fun sidesOf(fixture: String): Pair<Set<String>, Set<String>>? {
    val parts = VERSUS.find(fixture)?.let { listOf(fixture.take(it.range.first), fixture.drop(it.range.last + 1)) }
        ?: fixture.split(" - ").takeIf { it.size == 2 } ?: return null
    return (teamWords(parts[0]) to teamWords(parts[1])).takeIf { it.first.isNotEmpty() && it.second.isNotEmpty() }
}

/**
 * Broadcasters title one match differently ("Live: LaLiga - Athletic Bilbao - Real Sociedad",
 * "Athletic Bilbao vs Real Sociedad - Spanish La Liga"), so rows are one match when they share the
 * competition, overlap in time and name two of the same teams' words. The first row (best channel)
 * is kept; it shows the clearest title of the group (a "v"/"vs" one, then the shortest).
 */
private fun sameMatchOnce(matches: List<LiveEntry>): List<LiveEntry> {
    val groups = mutableListOf<MutableList<LiveEntry>>()
    for (m in matches) {
        val words = teamWords(m.title)
        val group = groups.firstOrNull { g ->
            val first = g.first()
            first.comp == m.comp && first.programme!!.start < m.programme!!.stop && m.programme.start < first.programme.stop &&
                (teamWords(first.title) intersect words).size >= 2
        }
        if (group != null) group += m else groups += mutableListOf(m)
    }
    return groups.map { g ->
        val title = g.map { it.title }.sortedWith(compareBy({ !VERSUS.containsMatchIn(it) }, { it.length })).first()
        g.first().copy(title = fixtureOf(title), alsoOn = g.drop(1).map { it.tune }.distinctBy { it.family }.filter { it.family != g.first().tune.family })
    }
}

private val VERSUS = Regex("\\s(v|vs\\.?)\\s", RegexOption.IGNORE_CASE)
private val LIVE_PREFIX = Regex("^live\\b\\s*[:\\-]?\\s*", RegexOption.IGNORE_CASE)
private val SUPERSCRIPT = Regex("[\\u1D2C-\\u1DBF\\u2070-\\u209F\\u00B2\\u00B3\\u00B9]+")

/** A guide title without the broadcaster's superscript markers ("Eagles @ Commanders ᴸᶦᵛᵉ"). */
internal fun cleanTitle(title: String): String = title.replace(SUPERSCRIPT, "").trim()

/**
 * A programme's title as the banner and rows show it: a live match by its fixture, and a re-run titled
 * fixture first ("Leeds United vs Tottenham Hotspur - English Premier League 2026/2027 - Week 10") by
 * the fixture too; a highlights or coverage title ("LALIGA Match Highlights: Real Betis v Malaga") stays whole.
 */
internal fun shownTitle(p: Programme): String {
    if (p.match) return fixtureOf(p.title)
    val head = p.title.substringBefore(" - ")
    return cleanTitle(if (VERSUS.containsMatchIn(head) && ':' !in head) head else p.title)
}

/**
 * Just the fixture from a broadcaster's title: "LALIGA EA SPORTS 2026/2027 MD11 : Getafe CF vs
 * Sevilla FC" → "Getafe CF vs Sevilla FC", "Athletic Bilbao vs Real Sociedad - Spanish LaLiga
 * 2026/2027 - Week 11" → "Athletic Bilbao vs Real Sociedad". Other titles are cleaned like channel names.
 */
internal fun fixtureOf(title: String): String {
    var t = title.replace(SUPERSCRIPT, "").replace(LIVE_PREFIX, "").trim()
    t.substringAfterLast(":", "").trim().let { if (it.contains(" - ") || VERSUS.containsMatchIn(it)) t = it }
    if (VERSUS.containsMatchIn(t)) return t.split(" - ").firstOrNull { VERSUS.containsMatchIn(it) }?.trim() ?: t
    // No fixture: a coverage title, often shouted with a feed prefix ("IT: DAZN DIRETTA GOL SERIE A HD").
    return if (t.contains(" - ")) t else displayName(t)
}
/** Words that name a competition or the coverage, never a team. */
private val NOT_TEAM = setOf(
    "live", "liga", "laliga", "league", "premier", "spanish", "english", "italian", "german", "french", "serie",
    "bundesliga", "ligue", "champions", "europa", "conference", "football", "sports", "sport", "primera", "primeira",
    "matchday", "round", "final", "copa", "coppa", "cup", "pokal", "championship",
)
private val WORD = Regex("\\p{L}{4,}")
private fun teamWords(title: String): Set<String> =
    WORD.findAll(searchNorm(title))
        .map { it.value }.filterNot { it in NOT_TEAM }.toSet()

private fun Feed.bestOf(channel: Channel) = familyMembers(channel.family).firstOrNull() ?: channel
