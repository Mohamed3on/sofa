package com.mohamed3on.iptv.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** One guide entry; times are epoch seconds. [synthetic] marks name-as-title filler. */
data class Programme(val start: Long, val stop: Long, val title: String, val descr: String, val synthetic: Boolean, val match: Boolean)

data class NowNext(val now: Programme?, val next: Programme?)

/**
 * A live match's two crests as image URLs, home first; either may be missing. A guide from after live scores also names
 * both teams' short names ("Man Utd"), the match's [key] in [LiveScores.scores] and its 🏆 group [comp]; null in an older guide.
 */
data class Crests(val home: String?, val away: String?, val homeName: String? = null, val awayName: String? = null, val key: String? = null, val comp: String? = null)

/**
 * A guide entry on [tvgId] (a live match, or a search hit); [norm] is the server's search form of its
 * title and [comp] the 🏆 group of the live match it names (the server decides, with the 🏆 groups' own rules).
 */
data class Airing(val tvgId: String, val norm: String, val programme: Programme, val comp: String? = null)

/** The cached guide (the server's SQLite file), opened read-only. */
class GuideDb private constructor(private val db: SQLiteDatabase) : Closeable {
    /** Each 🏆 match title's crests (the server's crests table; none in a guide from before it), read once at open. */
    val crests: Map<String, Crests> = runCatching {
        db.rawQuery(
            "SELECT c.title, home, away, home_name, away_name, home_key || '|' || away_key, p.comp FROM crests c LEFT JOIN " +
                "(SELECT title, MIN(comp) AS comp FROM programmes WHERE comp IS NOT NULL GROUP BY title) p ON p.title = c.title",
            null,
        ).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), Crests(c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6))) }
        }
    }.recoverCatching {
        db.rawQuery("SELECT title, home, away FROM crests", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), Crests(c.getString(1), c.getString(2))) }
        }
    }.getOrDefault(emptyMap())

    /** What airs on [tvgId] at [nowSec] (null in a gap) and what follows it. */
    fun nowNext(tvgId: String, nowSec: Long): NowNext = nowNextOf(programmes(tvgId, nowSec, limit = 2), nowSec)

    private fun nowNextOf(rows: List<Programme>, nowSec: Long): NowNext {
        val now = rows.firstOrNull()?.takeIf { it.start <= nowSec }
        return NowNext(now, if (now != null) rows.getOrNull(1) else rows.firstOrNull())
    }

    /** [nowNext] for many channels at once (a whole group); every id asked for gets an entry. */
    fun nowNextFor(tvgIds: Collection<String>, nowSec: Long): Map<String, NowNext> {
        val ids = tvgIds.distinct()
        val rows = HashMap<String, MutableList<Programme>>(ids.size * 2)
        // Chunked under SQLite's 999-variable limit; the first two rows per channel from now on.
        for (chunk in ids.chunked(500)) {
            db.rawQuery(
                """SELECT tvg_id, start, stop, title, descr, synthetic, "match" FROM (
                     SELECT *, ROW_NUMBER() OVER (PARTITION BY tvg_id ORDER BY start) AS n FROM programmes
                     WHERE tvg_id IN (${chunk.joinToString(",") { "?" }}) AND stop > ?
                   ) WHERE n <= 2 ORDER BY tvg_id, start""",
                (chunk + nowSec.toString()).toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) rows.getOrPut(c.getString(0)) { ArrayList(2) } += c.programme(1)
            }
        }
        return ids.associateWith { nowNextOf(rows[it].orEmpty(), nowSec) }
    }

    /** Live matches of a 🏆 competition airing at [nowSec], never synthetic filler (KTD4). */
    fun airingMatches(nowSec: Long): List<Airing> =
        airings("comp IS NOT NULL AND start <= ? AND stop > ?", nowSec.toString(), nowSec.toString())

    /**
     * Programmes whose search form contains [norm] (normalized like the server's `norm`), running at
     * [nowSec] or starting within a day of it, in start order — so the running ones come first — at
     * most [limit] of them. Never synthetic filler (KTD4, R26).
     */
    fun search(norm: String, nowSec: Long, limit: Int): List<Airing> =
        airings("stop > ? AND start < ? AND norm LIKE ?", nowSec.toString(), (nowSec + 86_400).toString(), "%$norm%", limit = limit)

    /** [limit] -1 is SQLite's "no limit". */
    private fun airings(where: String, vararg args: String, limit: Int = -1): List<Airing> =
        db.rawQuery(
            "SELECT tvg_id, norm, start, stop, title, descr, synthetic, \"match\", comp FROM programmes " +
                "WHERE synthetic = 0 AND $where ORDER BY start LIMIT $limit",
            args,
        ).use { c -> buildList { while (c.moveToNext()) add(Airing(c.getString(0), c.getString(1), c.programme(2), c.getString(8))) } }

    /** Programmes on [tvgId] still running or starting after [fromSec], in start order. */
    fun schedule(tvgId: String, fromSec: Long): List<Programme> = programmes(tvgId, fromSec, limit = -1)

    private fun programmes(tvgId: String, after: Long, limit: Int): List<Programme> =
        db.rawQuery(
            "SELECT start, stop, title, descr, synthetic, \"match\" FROM programmes WHERE tvg_id = ? AND stop > ? ORDER BY start LIMIT $limit",
            arrayOf(tvgId, after.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.programme(0)) } }

    /** start, stop, title, descr, synthetic, match from column [at] on. */
    private fun Cursor.programme(at: Int) =
        Programme(getLong(at), getLong(at + 1), getString(at + 2), getString(at + 3), getInt(at + 4) != 0, getInt(at + 5) != 0)

    override fun close() = db.close()

    companion object {
        /** Opens [file] read-only; throws unless it is SQLite with a non-empty programmes table. */
        fun open(file: File): GuideDb {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("SELECT 1 FROM programmes LIMIT 1", null).use { check(it.moveToFirst()) { "guide is empty" } }
            } catch (e: Exception) {
                db.close()
                throw e
            }
            return GuideDb(db)
        }
    }
}
