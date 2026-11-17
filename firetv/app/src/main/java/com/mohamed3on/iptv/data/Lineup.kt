package com.mohamed3on.iptv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The favourites group: first among the groups while it has channels. */
const val FAVOURITES = "Favourites"

/**
 * The viewer's own lineup, shared by their devices ([StateSync]): favourite channels, shown as a group of their own
 * (copies, like the 🏆 groups'), and each group's channels in the order they were arranged. Both
 * are by family, so they outlive a refresh that renumbers streams. Favourites also lead their own group
 * (not the 🏆 groups, which stay quality-sorted for Live now). [version] moves on each change.
 */
class Lineup(private val store: KeyValueStore) {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private var favourites = read(FAVOURITES_KEY)

    fun isFavourite(family: String) = family in favourites

    /** Reads it again: another device's changes came in ([StateSync]). */
    fun reload() {
        favourites = read(FAVOURITES_KEY)
        _version.value++
    }

    fun toggleFavourite(family: String) {
        favourites = if (family in favourites) favourites - family else favourites + family
        write(FAVOURITES_KEY, favourites)
    }

    /** Moves [family] one place down (or up) in [group], whose rows' families are [families]; never past an end, nor across the favourites at the top. */
    fun move(group: String, families: List<String>, family: String, down: Boolean) {
        val i = families.indexOf(family)
        val j = i + if (down) 1 else -1
        if (i < 0 || j !in families.indices || isFavourite(families[j]) != isFavourite(family)) return
        write(ORDER_KEY + group, families.toMutableList().apply { this[i] = this[j]; this[j] = family })
    }

    /** [feed] with the favourites group first and each arranged group in its order, favourites first; channels new since come last. */
    fun applyTo(feed: Feed): Feed {
        val favs = favourites.mapNotNull { feed.familyMembers(it).firstOrNull()?.copy(group = FAVOURITES) }
        val channels = feed.channels + favs
        val sorted = channels.groupBy { it.group }.mapValues { (group, rows) ->
            val order = read(ORDER_KEY + group)
            val arranged = if (order.isEmpty()) rows else rows.sortedBy { order.indexOf(it.family).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            (if (group.startsWith("🏆")) arranged else arranged.sortedBy { it.family !in favourites }).iterator()
        }
        return feed.copy(
            groups = (if (favs.isEmpty()) emptyList() else listOf(FAVOURITES)) + feed.groups,
            channels = channels.map { sorted.getValue(it.group).next() },
        )
    }

    private fun read(key: String) = store.get(key)?.split('\n')?.filter(String::isNotEmpty).orEmpty()

    private fun write(key: String, families: List<String>) {
        store.put(key, families.joinToString("\n").ifEmpty { null })
        _version.value++
    }

    private companion object {
        const val FAVOURITES_KEY = "lineup.favourites"
        const val ORDER_KEY = "lineup.order."
    }
}
