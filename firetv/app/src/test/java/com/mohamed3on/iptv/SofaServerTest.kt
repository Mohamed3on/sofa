package com.mohamed3on.iptv

import com.mohamed3on.iptv.data.SyncError
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class SofaServerTest {
    private val box = FakeSofa()

    @After fun tearDown() = box.close()

    /** Every caller tells a failure apart the same way: the box refused (with its status), sent something unreadable, or was out of reach. */
    @Test fun everyFailureIsOneSyncError() {
        fun call() = box.client.get("title?id=movie:1") { Json.parseToJsonElement(it.string()) }.exceptionOrNull()
        box.reply(429)
        assertEquals(SyncError.Rejected(429), call())
        box.reply(body = "<!DOCTYPE html><html><title>Just a moment...</title></html>")
        assertEquals(SyncError.Malformed, call())
        box.down()
        assertEquals(SyncError.Offline, call())
    }
}
