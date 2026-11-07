package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.Debrid
import com.mohamed3on.iptv.data.Debrids
import com.mohamed3on.iptv.data.DebridCandidate
import com.mohamed3on.iptv.data.DebridCheck
import com.mohamed3on.iptv.data.DebridState
import com.mohamed3on.iptv.data.DebridStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridsTest {
    private class Fake(val name: String, val answers: Map<DebridCandidate, DebridCheck>, state: DebridState = DebridState.Ok) : Debrid {
        override val status = MutableStateFlow(DebridStatus(name, state))
        var asked = 0
        override suspend fun check(candidates: List<DebridCandidate>) = answers.also { asked++ }
        override suspend fun link(ready: DebridCheck.Ready) = "$name:${ready.link}"
    }

    private val best = DebridCandidate("AAA", 0, "Best.mkv")
    private val next = DebridCandidate("BBB", 0, "Next.mkv")
    private val other = DebridCandidate("CCC", 0, "Other.mkv")

    @Test fun `the best copy plays from whichever service holds it, and a copy is not ready only when both say so`() = runTest {
        val tb = Fake("TorBox", mapOf(best to DebridCheck.NotCached, next to DebridCheck.Ready("1", "9"), other to DebridCheck.NotCached))
        val rd = Fake("Real-Debrid", mapOf(best to DebridCheck.Ready("T", "https://rd/l")))
        val both = Debrids(tb, rd, backgroundScope)
        val answers = both.check(listOf(best, next, other))
        assertEquals(DebridCheck.Ready("T", "https://rd/l"), answers[best])
        assertEquals(DebridCheck.Ready("1", "9"), answers[next])
        assertEquals(null, answers[other]) // Real-Debrid didn't say: unknown, not "not ready"
        assertEquals("Real-Debrid:https://rd/l", both.link(answers[best] as DebridCheck.Ready))
        assertEquals("TorBox:9", both.link(answers[next] as DebridCheck.Ready))
    }

    @Test fun `a lapsed service is not asked, the other one serves alone`() = runTest {
        val tb = Fake("TorBox", emptyMap(), DebridState.NotPremium)
        val rd = Fake("Real-Debrid", mapOf(best to DebridCheck.Ready("T", "https://rd/l")))
        val both = Debrids(tb, rd, backgroundScope)
        assertEquals(DebridCheck.Ready("T", "https://rd/l"), both.check(listOf(best))[best])
        assertEquals(0, tb.asked)
        assertEquals("Real-Debrid", both.status.value.name)
    }

    @Test fun `once neither serves, the status is the lapsed service's, so the premium-ended notice names it`() = runTest {
        val tb = Fake("TorBox", emptyMap(), DebridState.NoToken)
        val rd = Fake("Real-Debrid", emptyMap())
        val both = Debrids(tb, rd, backgroundScope)
        rd.status.value = DebridStatus("Real-Debrid", DebridState.NotPremium)
        runCurrent()
        assertEquals(DebridStatus("Real-Debrid", DebridState.NotPremium), both.status.value)
    }
}
