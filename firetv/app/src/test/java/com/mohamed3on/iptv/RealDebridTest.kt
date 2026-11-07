package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.AddGate
import com.mohamed3on.iptv.data.DebridCandidate
import com.mohamed3on.iptv.data.DebridCheck
import com.mohamed3on.iptv.data.DebridError
import com.mohamed3on.iptv.data.DebridState
import com.mohamed3on.iptv.data.RealDebrid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class RealDebridTest {
    private val server = MockWebServer()
    // fileIdx 0 would be the sample (id 1); the file name picks the film (id 2).
    private val film = DebridCandidate("ABC123", 0, "Film.mkv", cached = true)

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.close()

    private fun rd(gate: AddGate = AddGate(), now: () -> Long = System::currentTimeMillis, onInfringing: (String) -> Unit = {}) =
        RealDebrid(OkHttpClient(), { "tok" }, server.url("/rest").toString(), now, gate, onInfringing)
    private fun TestScope.clock(): () -> Long = { 1_000_000 + testScheduler.currentTime }
    private fun response(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()
    private fun reply(code: Int, body: String = "") = server.enqueue(response(code, body))
    private fun addsAnswer(code: Int, body: String) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest) =
            if (request.url.encodedPath.endsWith("addMagnet")) response(code, body) else response(204)
    }
    private fun info(status: String) {
        val done = status == "downloaded"
        reply(200, """{"status":"$status","files":[{"id":1,"path":"/Sample.mkv","bytes":1,"selected":0},
            {"id":2,"path":"/Film.mkv","bytes":9,"selected":${if (done) 1 else 0}}],"links":${if (done) """["https://real-debrid.com/d/L1"]""" else "[]"}}""")
    }
    private fun requests() = generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()
    private fun paths() = requests().map { "${it.method} ${it.url.encodedPath}" }

    @Test fun `a torrent already in the account skips addMagnet and links fresh`() = runTest {
        val rd = rd()
        reply(200, """[{"id":"T1","hash":"abc123","status":"downloaded"}]""")
        info("downloaded")
        val ready = rd.check(listOf(film))[film] as DebridCheck.Ready
        reply(200, """{"download":"https://dl.example/film.mkv"}""")
        assertEquals("https://dl.example/film.mkv", rd.link(ready))
        assertEquals(listOf("GET /rest/torrents", "GET /rest/torrents/info/T1", "POST /rest/unrestrict/link"), paths())
    }

    @Test fun `a cached magnet is kept with its named file selected, an uncached one is deleted`() = runTest {
        reply(204)
        reply(201, """{"id":"N1"}""")
        info("waiting_files_selection")
        reply(204)
        info("downloaded")
        val rd = rd()
        assertEquals(DebridCheck.Ready("N1", "https://real-debrid.com/d/L1"), rd.check(listOf(film))[film])
        val sent = requests()
        assertEquals("files=2", sent[3].body?.utf8())
        assertEquals(5, sent.size) // no delete
        // Prewarmed: Play finds it with no calls, so only the unrestrict is left.
        assertTrue(rd.check(listOf(film))[film] is DebridCheck.Ready)
        assertEquals(emptyList<String>(), paths())

        reply(204)
        reply(201, """{"id":"N2"}""")
        info("waiting_files_selection")
        reply(204)
        info("downloading")
        reply(204)
        assertEquals(DebridCheck.NotCached, rd().check(listOf(film))[film])
        assertEquals("DELETE /rest/torrents/delete/N2", paths().last())
    }

    @Test fun `errors map to permanent or transient and set the source status`() = runTest {
        val infringing = mutableListOf<String>()
        reply(204)
        reply(451, """{"error":"infringing_file","error_code":35}""")
        assertTrue((rd(onInfringing = { infringing += it }).check(listOf(film))[film] as DebridCheck.Failed).error.permanent)
        assertEquals(listOf("ABC123"), infringing) // for the box's list

        var rd = rd()
        reply(204)
        reply(503, """{"error":"too_many_active_downloads","error_code":21}""")
        assertEquals(false, (rd.check(listOf(film))[film] as DebridCheck.Failed).error.permanent)
        assertEquals(DebridState.TooManyDownloads, rd.status.value.state)

        rd = rd()
        reply(401, """{"error":"bad_token","error_code":8}""")
        assertEquals(emptyMap<DebridCandidate, DebridCheck>(), rd.check(listOf(film)))
        assertEquals(DebridState.TokenInvalid, rd.status.value.state)

        reply(503, """{"error":"unavailable_file","error_code":24}""")
        val e = runCatching { rd.link(DebridCheck.Ready("T1", "https://real-debrid.com/d/L1")) }.exceptionOrNull() as DebridError
        assertEquals(false, e.permanent)
    }

    @Test fun `a rate-limited addMagnet stops every add in the process for a minute`() = runTest {
        server.dispatcher = addsAnswer(429, """{"error":"too_many_requests","error_code":34}""")
        val gate = AddGate()
        val rd = rd(gate, clock())
        val six = List(6) { DebridCandidate("h$it", 0, cached = true) }
        assertTrue(rd.check(six).values.single().let { it is DebridCheck.Failed && !it.error.permanent })
        assertEquals(DebridState.RateLimited, rd.status.value.state)
        rd.check(six) // still within the pause: no adds, from this client or another
        rd(gate, clock()).check(six)
        assertEquals(1, paths().count { it.endsWith("addMagnet") })
    }

    @Test fun `only cached copies are added, one at a time 4 s apart, at most 3`() = runTest {
        server.dispatcher = addsAnswer(451, """{"error":"infringing_file","error_code":35}""")
        val infringing = mutableListOf<String>()
        val copies = listOf(DebridCandidate("u0", 0)) + List(4) { DebridCandidate("c$it", 0, cached = true) }
        val out = rd(now = clock(), onInfringing = { infringing += it }).check(copies)
        assertEquals(listOf("c0", "c1", "c2"), infringing)
        assertEquals(copies.subList(1, 4).toSet(), out.keys)
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test fun `an account copy plays at once instead of waiting for an add's turn`() = runTest {
        val gate = AddGate()
        val now = clock()
        gate.lastAdd = now() // another title's add just happened
        reply(200, """[{"id":"T1","hash":"abc123","status":"downloaded"}]""")
        info("downloaded")
        val top = DebridCandidate("TOP", 0, cached = true)
        assertEquals(setOf(film), rd(gate, now).check(listOf(top, film)).keys)
        assertEquals(0L, testScheduler.currentTime)
        assertTrue(paths().none { it.endsWith("addMagnet") })
    }

    @Test fun `an ended premium, read from the account or a refused unrestrict, stops every Real-Debrid call`() = runTest {
        val free = rd()
        reply(200, """{"type":"free","expiration":"2026-11-01T00:00:00.000Z"}""")
        free.readAccount()
        assertEquals(DebridState.NotPremium, free.status.value.state)
        assertTrue(free.check(listOf(film)).isEmpty())
        val refused = rd()
        reply(503, """{"error":"hoster_not_free","error_code":20}""")
        runCatching { refused.link(DebridCheck.Ready("T1", "https://real-debrid.com/d/L1")) }
        assertEquals(DebridState.NotPremium, refused.status.value.state)
        assertEquals(listOf("GET /rest/user", "POST /rest/unrestrict/link"), paths())
    }
}
