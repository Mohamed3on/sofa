package com.mohamed3on.iptv.data

import java.text.Normalizer

/** The server's search form of a title: accents stripped, lowercased ("München" → "munchen"). */
fun searchNorm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

/** A programme found by search, on the best feed of the channel family airing it. */
data class SearchHit(val programme: Programme, val tune: Channel, val comp: String?)

/** What a search for [query] found: channels by name (event/PPV slots among them), then programmes (live first, then by start). */
data class SearchResults(val query: String, val channels: List<Channel>, val hits: List<SearchHit>) {
    val count get() = channels.size + hits.size
}

private const val CHANNEL_CAP = 8
private const val HIT_CAP = 60
/** Guide rows read for [HIT_CAP] hits: one programme can sit on several feeds' guides. */
private const val ROW_CAP = 300

/** Each family's best feed with its name in search form, and the feed by guide id: built once per feed. */
private class SearchIndex(val feed: Feed, val names: List<Pair<String, Channel>>, val byTvgId: Map<String, List<Channel>>)

@Volatile private var index: SearchIndex? = null

// By identity: Feed is a data class, and hashing every channel on each search is what this avoids.
private fun Feed.searchIndex(): SearchIndex = index?.takeIf { it.feed === this } ?: SearchIndex(
    this,
    channels.distinctBy { it.family }.map { familyMembers(it.family).first() }.map { searchNorm(displayName(it.name)) to it },
    channels.filter { it.tvgId.isNotEmpty() }.groupBy { it.tvgId },
).also { index = it }

/** Builds the search index ahead of the first query (off the main thread). */
fun Feed.prepareSearch() {
    searchIndex()
}

/** Every word starts a word of [name] ("city" in "Manchester City", not in "Felicity"). */
private fun startsWords(name: String, words: List<String>): Boolean {
    val parts = name.split(NON_WORD)
    return words.all { w -> parts.any { it.startsWith(w) } }
}
private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
private const val SOON_SEC = 3 * 3600

/**
 * Search (R7, R26): channels whose name has every typed word, one per family on its best feed
 * (names where the words start a word first, then server order), at most [CHANNEL_CAP]; then
 * programmes in the guide from now to a day ahead whose title contains the query anywhere, one row
 * per (channel family, programme), so a channel's HD, FHD and 4K feeds count as one, at most
 * [HIT_CAP]. Programmes rank by time first: on now, then within [SOON_SEC], then later. Within each,
 * titles where the words start a word, then 🏆 competition matches, then other live matches (the
 * server's rules: no replays, highlights or shows), then by start.
 */
fun Feed.search(guide: GuideDb?, query: String, nowSec: Long): SearchResults {
    // Dictation can end with a full stop ("Liverpool."); typed queries never have one.
    val q = searchNorm(query).trim().trimEnd('.', '!', '?').trim()
    val words = q.split(' ').filter(String::isNotEmpty)
    val index = searchIndex()
    val channels = index.names.filter { (name, _) -> words.all(name::contains) }
        .sortedBy { (name, _) -> !startsWords(name, words) }
        .take(CHANNEL_CAP).map { it.second }
    val seen = HashSet<Triple<String, String, Long>>()
    val ranked = guide?.search(q, nowSec, ROW_CAP).orEmpty().sortedWith(
        compareBy(
            { a -> if (a.programme.start <= nowSec) 0 else if (a.programme.start < nowSec + SOON_SEC) 1 else 2 },
            { a -> !startsWords(a.norm, words) },
            { a -> a.comp == null },
            { a -> !a.programme.match },
            { a -> a.programme.start },
        ),
    )
    val hits = ranked.asSequence().flatMap { a ->
        index.byTvgId[a.tvgId].orEmpty().map { it.family }.distinct()
            .filter { seen.add(Triple(it, a.norm, a.programme.start)) }
            .map { SearchHit(a.programme, familyMembers(it).first(), a.comp) }
    }.take(HIT_CAP).toList()
    return SearchResults(query, channels, hits)
}

/** The typed words, as the search reads them (dictation's closing full stop dropped). */
fun searchWords(query: String) = searchNorm(query).trim().trimEnd('.', '!', '?').split(' ').filter(String::isNotEmpty)
