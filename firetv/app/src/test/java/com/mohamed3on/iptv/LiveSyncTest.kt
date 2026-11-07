package com.mohamed3on.iptv

import android.database.sqlite.SQLiteDatabase
import com.mohamed3on.iptv.data.FeedState
import com.mohamed3on.iptv.data.LiveSync
import com.mohamed3on.iptv.data.SyncError
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
class LiveSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private val box = FakeSofa()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = tmp.newFolder("files")
    }

    @After fun tearDown() = box.close()

    private fun sync() = LiveSync(box.client, dir, MapStore())

    private fun feedJson(name: String) = """
        {"groups":["🏆 Premier League"],"route":{"plain":"http://p","vpn":"http://v","user":"u","pass":"x"},
         "channels":[{"id":950400,"name":"$name","logo":"","group":"🏆 Premier League","tvgId":"sky1.uk","slot":false,
                      "family":"uk|sky 1","rank":0,"h":2160,"q":5,"badges":["4K"],"extra":"ignored"}]}
    """.trimIndent()

    private fun guideGz(title: String, truncate: Boolean = false): ByteArray {
        val db = File(tmp.root, "build-${System.nanoTime()}.db")
        SQLiteDatabase.openOrCreateDatabase(db, null).use {
            it.execSQL("CREATE TABLE programmes(tvg_id TEXT, start INTEGER, stop INTEGER, title TEXT, norm TEXT, descr TEXT, synthetic INTEGER, match INTEGER)")
            it.execSQL("INSERT INTO programmes VALUES ('sky1.uk', 1000, 2000, ?, '', 'd', 0, 1)", arrayOf(title))
            it.execSQL("INSERT INTO programmes VALUES ('sky1.uk', 2000, 3000, 'Next', '', '', 0, 0)")
        }
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(db.readBytes()) } }.toByteArray()
        return if (truncate) gz.copyOf(gz.size / 2) else gz
    }

    private val LiveSync.channelName get() = (state.value as FeedState.Ready).feed.channels.single().name

    @Test fun aChallengePageIsRejectedAndTheOldFeedStays() = runTest {
        box.reply(body = feedJson("Sky 1"))
        box.reply(body = "<!DOCTYPE html><html><title>Just a moment...</title></html>")
        val s = sync()
        assertNull(s.syncChannels())
        val before = File(dir, "feed.json").readBytes()

        assertEquals(SyncError.Malformed, s.syncChannels())
        assertEquals("Sky 1", s.channelName)
        assertArrayEquals(before, File(dir, "feed.json").readBytes())
        sync().apply { loadCached() }.also { assertEquals("Sky 1", it.channelName) }
    }

    @Test fun aTruncatedGuideFailsValidationAndTheOldGuideStays() = runTest {
        box.reply(guideGz("Old match"))
        box.reply(guideGz("New match", truncate = true))
        val s = sync()
        assertNull(s.syncGuide())
        val before = File(dir, "guide.db").readBytes()

        assertEquals(SyncError.Malformed, s.syncGuide())
        assertArrayEquals(before, File(dir, "guide.db").readBytes())
        val nowNext = s.guide.value!!.nowNext("sky1.uk", 1500)
        assertEquals("Old match", nowNext.now?.title)
        assertEquals("Next", nowNext.next?.title)
    }

    @Test fun firstRunWithTheServerUnreachableShowsAnErrorThenRetrySucceeds() = runTest {
        box.down()
        val s = sync()
        s.loadCached()
        assertEquals(FeedState.Loading, s.state.value)

        s.retry()
        assertEquals(FeedState.Failed(SyncError.Offline), s.state.value)

        // The server comes back on the same address for the Retry press.
        box.up()
        box.reply(body = feedJson("Sky 1"))
        box.reply(guideGz("Match"))
        s.retry()
        assertEquals("Sky 1", s.channelName)
    }

    @Test fun firstRunNamesTheServersRejection() = runTest {
        box.reply(403, "Forbidden")
        box.reply(403, "Forbidden")
        val s = sync()
        s.retry()
        assertEquals(FeedState.Failed(SyncError.Rejected(403)), s.state.value)
    }

    @Test fun aStoredEtagIsSentAndA304KeepsTheFeed() = runTest {
        box.reply(body = feedJson("Sky 1"), etag = "\"v1\"")
        box.reply(304)
        val s = sync()
        assertNull(s.syncChannels())
        box.request()
        assertNull(s.syncChannels())
        assertEquals("\"v1\"", box.request()!!.headers["If-None-Match"])
        assertEquals("Sky 1", s.channelName)
    }
}
