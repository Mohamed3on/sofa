package com.mohamed3on.iptv

import android.provider.Settings
import com.mohamed3on.iptv.update.Updater
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class UpdaterTest {
    @get:Rule val tmp = TemporaryFolder()
    private val box = FakeSofa()
    private val app = RuntimeEnvironment.getApplication()
    private val apk = "fake apk bytes".toByteArray()
    private val dm = "fake profile".toByteArray()
    private lateinit var dir: File

    @Before fun setUp() {
        dir = tmp.newFolder("cache")
    }

    @After fun tearDown() = box.close()

    private fun updater(packageName: String = app.packageName) = Updater(
        app, box.client, TestScope(),
        installed = 100, dir = dir, packageOf = { packageName },
    )

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun serve(versionCode: Int, sha256: String = sha(apk), withApk: Boolean = true, dmSha256: String? = null) {
        val dmField = dmSha256?.let { ""","dmSha256":"$it"""" } ?: ""
        box.reply(body = """{"versionCode":$versionCode,"versionName":"1.$versionCode","sha256":"$sha256"$dmField}""")
        if (withApk) box.reply(apk)
        if (dmSha256 != null) box.reply(dm)
    }

    @Test fun theProfileComesAlongWhenItVerifies() = runTest {
        serve(101, dmSha256 = sha(dm))
        val u = updater()
        u.check()
        assertTrue(u.ready.value?.dm?.readBytes()?.contentEquals(dm) == true)
    }

    @Test fun aProfileThatFailsItsChecksumLeavesAPlainUpdate() = runTest {
        serve(101, dmSha256 = sha("other".toByteArray()))
        val u = updater()
        u.check()
        assertEquals("1.101", u.ready.value?.versionName)
        assertNull(u.ready.value?.dm)
        assertEquals(listOf("update.apk"), dir.list()!!.toList())
    }

    @Test fun aNewerValidBuildShowsTheRowAndInstallsNothingUntilPicked() = runTest {
        serve(101)
        val u = updater()
        u.check()
        assertEquals("1.101", u.ready.value?.versionName)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(0, app.packageManager.packageInstaller.allSessions.size)

        u.install(app)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(app).nextStartedActivity.action)
    }

    @Test fun aShaMismatchIsDiscarded() = runTest {
        serve(101, sha256 = sha("other".toByteArray()))
        val u = updater()
        u.check()
        assertNull(u.ready.value)
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test fun anotherAppsPackageIsRefused() = runTest {
        serve(101)
        val u = updater(packageName = "com.example.other")
        u.check()
        assertNull(u.ready.value)
    }

    @Test fun sameOrLowerVersionOr404ShowsNoRow() = runTest {
        val u = updater()
        serve(100, withApk = false)
        u.check()
        serve(99, withApk = false)
        u.check()
        box.reply(404)
        u.check()
        assertNull(u.ready.value)
        assertEquals(3, box.requests) // never fetched an APK
    }
}
