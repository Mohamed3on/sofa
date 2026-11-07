package com.mohamed3on.iptv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The viewer's arrangement of the Movies & Shows rows, kept on this device by row key: the order they were moved into and
 * the rows hidden. A row never moved (a new Letterboxd list, Dubbed in Arabic once it fills) keeps the box's place for it.
 * [version] moves on each change.
 */
class RowLayout(private val store: KeyValueStore) {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    val hidden: Set<String> get() = read(HIDDEN_KEY).toSet()

    /** [rows] in the box's order, arranged: the moved ones take the places they hold among them in their moved order; hidden ones go. */
    fun <T> arrange(rows: List<T>, key: (T) -> String): List<T> {
        val order = read(ORDER_KEY)
        val moved = rows.filter { key(it) in order }.sortedBy { order.indexOf(key(it)) }.iterator()
        val hide = hidden
        return rows.map { if (key(it) in order) moved.next() else it }.filter { key(it) !in hide }
    }

    /** Moves [key] one place down (or up) among the rows on screen, [shown] (keys, in order); never past an end. */
    fun move(shown: List<String>, key: String, down: Boolean) {
        val i = shown.indexOf(key)
        val j = i + if (down) 1 else -1
        if (i < 0 || j !in shown.indices) return
        write(ORDER_KEY, shown.toMutableList().apply { this[i] = this[j]; this[j] = key })
    }

    fun hide(key: String) = write(HIDDEN_KEY, read(HIDDEN_KEY) - key + key)

    fun showHidden() = write(HIDDEN_KEY, emptyList())

    private fun read(key: String) = store.get(key)?.split('\n')?.filter(String::isNotEmpty).orEmpty()

    private fun write(key: String, keys: List<String>) {
        store.put(key, keys.joinToString("\n").ifEmpty { null })
        _version.value++
    }

    private companion object {
        const val ORDER_KEY = "movies.rowOrder"
        const val HIDDEN_KEY = "movies.hiddenRows"
    }
}
