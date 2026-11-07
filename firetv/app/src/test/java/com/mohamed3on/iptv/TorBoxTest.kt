package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.DebridCandidate
import com.mohamed3on.iptv.data.DebridCheck
import com.mohamed3on.iptv.data.TorBox
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class TorBoxTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun reply(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())
    private fun ok(data: String) = reply("""{"success":true,"error":null,"data":$data}""")

    @Test fun `only the copies TorBox holds are added, and the file is found by name`() = runTest {
        val best = DebridCandidate("AAA", 0, "Best.mkv") // ranked first, not held
        val held = DebridCandidate("BBB", 0, "Film.mkv") // no Torrentio flag: TorBox's own answer counts
        ok("[]") // the account is empty
        ok("""[{"hash":"bbb"}]""")
        ok("""{"torrent_id":7}""")
        // TorBox's file ids don't follow the torrent's order: the .nfo is 0, the film 1.
        ok("""{"id":7,"hash":"bbb","download_finished":true,"files":[{"id":1,"short_name":"Film.mkv","size":9},{"id":0,"short_name":"Film.nfo","size":1}]}""")
        val tb = TorBox(OkHttpClient(), { "tok" }, server.url("/v1/api").toString())
        val answers = tb.check(listOf(best, held))
        assertEquals(DebridCheck.NotCached, answers[best])
        val ready = answers[held] as DebridCheck.Ready
        assertEquals(DebridCheck.Ready("7", "1"), ready)
        ok("\"https://cdn.example/film.mkv\"")
        assertEquals("https://cdn.example/film.mkv", tb.link(ready))
        val sent = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.map { it.target }.toList()
        assertEquals("/v1/api/torrents/checkcached?hash=AAA,BBB&format=list", sent[1])
        assertEquals("/v1/api/torrents/requestdl?token=tok&torrent_id=7&file_id=1&zip_link=false", sent[4])
    }

    @Test fun `a torrent TorBox doesn't hold is not cached, not a failure`() = runTest {
        val film = DebridCandidate("CCC", 0, "Film.mkv", cached = true)
        ok("[]") // the account is empty
        ok("""[{"hash":"ccc"}]""") // held, by the cache check
        reply("""{"success":false,"error":"DOWNLOAD_NOT_CACHED","data":null}""")
        val tb = TorBox(OkHttpClient(), { "tok" }, server.url("/v1/api").toString())
        assertEquals(DebridCheck.NotCached, tb.check(listOf(film))[film])
    }
}
