package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.SofaServer
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** The box in tests: a local server giving the replies queued on it, and the app's own [client] pointed at it. */
class FakeSofa : AutoCloseable {
    private var web = MockWebServer().apply { start() }
    val client = SofaServer(OkHttpClient(), web.url("/").toString().trimEnd('/'), "tok")

    fun reply(code: Int = 200, body: String = "", etag: String? = null) =
        web.enqueue(MockResponse.Builder().code(code).body(body).apply { etag?.let { setHeader("ETag", it) } }.build())

    fun reply(bytes: ByteArray) = web.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())

    /** The next request the box got; null when none came within [ms]. */
    fun request(ms: Long = 5_000): RecordedRequest? = web.takeRequest(ms, TimeUnit.MILLISECONDS)

    val requests get() = web.requestCount

    /** The box goes out of reach; [up] brings it back on the same address. */
    fun down() = web.close()

    fun up() {
        web = MockWebServer().apply { start(InetAddress.getByName(web.hostName), web.port) }
    }

    override fun close() = web.close()
}
