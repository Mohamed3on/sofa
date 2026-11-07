package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.TraktSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktSyncTest {
    private val box = FakeSofa()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = MapStore()

    @After fun tearDown() {
        scope.cancel()
        box.close()
    }

    private fun trakt() = TraktSync(box.client, store, scope) { 1_762_372_800_000 }
    private fun sent() = generateSequence { box.request(100) }.map { it.body?.utf8() }.toList()

    @Test fun `plays finished offline are kept and sent later`() = runBlocking {
        box.reply(503) // Trakt not signed in on the box, or the box out of reach
        trakt().finished("tv:1396:s1e2")
        assertEquals("/api/app/trakt-history?t=tok", box.request()?.target)
        box.reply(204)
        trakt().flush() // a later run, from what was kept
        assertEquals(listOf("""{"title":"tv:1396:s1e2","at":1762372800000}"""), sent())
        assertNull(store.get("trakt.finished"))
    }
}
