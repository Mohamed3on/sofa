package com.mohamed3on.iptv.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.mohamed3on.iptv.BuildConfig
import com.mohamed3on.iptv.data.SofaServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * A verified newer build in the cache. [dm] is its dex metadata (the baseline profile), when it came
 * too. [askedPermission]: Settings was opened, so the row explains why.
 */
data class UpdateReady(val versionCode: Long, val versionName: String, val apk: File, val dm: File? = null, val askedPermission: Boolean = false)

@Serializable
private class Manifest(val versionCode: Long, val versionName: String, val sha256: String, val dmSha256: String? = null)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Self-update (R15, R16). [check] fetches the version manifest and, when it is newer than [installed],
 * downloads the APK to [dir], verifies its sha256 and that it is this app's own package (KTD11), then
 * publishes [ready]. Its dex metadata (.dm, the baseline profile) comes along when the manifest names
 * one and it verifies: installed with the APK, the platform compiles the app's hot paths at install,
 * so the first minutes after an update run compiled. Without it the update installs just the same. Nothing installs until [install] is called from the row. Every failure is silent:
 * no row, retried on the next check. Never logs a URL (it carries the token).
 */
class Updater(
    private val context: Context,
    private val server: SofaServer,
    private val scope: CoroutineScope,
    private val installed: Long = BuildConfig.VERSION_CODE.toLong(),
    private val dir: File = context.cacheDir,
    @Suppress("DEPRECATION")
    private val packageOf: (File) -> String? = { context.packageManager.getPackageArchiveInfo(it.path, 0)?.packageName },
) {
    private val lock = Mutex()
    private val _ready = MutableStateFlow<UpdateReady?>(null)
    val ready: StateFlow<UpdateReady?> = _ready

    /** Called on launch and on resume; a check already in flight makes this a no-op. */
    suspend fun check() {
        if (!lock.tryLock()) return
        try {
            withContext(Dispatchers.IO) { runCatching { fetch() }.onFailure { Log.w(TAG, "check failed: ${it.javaClass.simpleName}") } }
        } finally {
            lock.unlock()
        }
    }

    private fun fetch() {
        val apk = File(dir, "update.apk")
        val dm = File(dir, DM)
        val manifest = server.get("version") { json.decodeFromString<Manifest>(it.string()) }.getOrThrow()
        Log.i(TAG, "server ${manifest.versionCode}, installed $installed")
        if (manifest.versionCode <= installed) {
            apk.delete() // an update that is now installed
            dm.delete()
            return
        }
        if (_ready.value?.versionCode == manifest.versionCode) return

        // Ends in .apk: the platform's APK reader can refuse other names, which would fail the package check.
        val tmp = File(dir, "update-download.apk")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            server.get("apk") { body -> DigestInputStream(body.byteStream(), digest).use { input -> tmp.outputStream().use { input.copyTo(it) } } }.getOrThrow()
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(manifest.sha256, ignoreCase = true)) {
                Log.w(TAG, "download failed its checksum")
                return
            }
            val pkg = packageOf(tmp)
            if (pkg != context.packageName) {
                Log.w(TAG, "download is not this app (${pkg ?: "unreadable"})")
                return
            }
            if (!tmp.renameTo(apk)) {
                Log.w(TAG, "could not keep the download")
                return
            }
            dm.delete()
            val withDm = manifest.dmSha256?.let { runCatching { download("dm", dm, it) }.getOrDefault(false) } == true
            Log.i(TAG, "update ${manifest.versionName} ready${if (withDm) " with its profile" else ""}")
            _ready.value = UpdateReady(manifest.versionCode, manifest.versionName, apk, dm.takeIf { withDm })
        } finally {
            tmp.delete()
        }
    }

    /** Downloads [kind] to [to] when it matches [sha256]; false (and nothing left behind) otherwise. */
    private fun download(kind: String, to: File, sha256: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        server.get(kind) { body -> DigestInputStream(body.byteStream(), digest).use { input -> to.outputStream().use { input.copyTo(it) } } }.getOrElse { return false }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.equals(sha256, ignoreCase = true).also { if (!it) to.delete() }
    }

    /**
     * The row was picked. Without the install permission, opens this app's unknown-sources setting
     * first; otherwise hands the APK to a PackageInstaller session, whose confirm screen [InstallReceiver] opens.
     */
    fun install(from: Context) {
        val update = _ready.value ?: return
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            _ready.value = update.copy(askedPermission = true)
            from.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .apply { if (from !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val installer = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(context.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, update.apk.length()).use { out ->
                        update.apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    // Named after the APK, so the platform pairs them; gone after a failed install (InstallReceiver).
                    update.dm?.takeIf { it.exists() }?.let { dm ->
                        session.openWrite("base.dm", 0, dm.length()).use { out ->
                            dm.inputStream().use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    }
                    val callback = PendingIntent.getBroadcast(
                        context, id, Intent(context, InstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    File(context.filesDir, RELAUNCH).createNewFile()
                    session.commit(callback.intentSender)
                }
            }.onFailure { Log.w(TAG, "install session failed: ${it.javaClass.simpleName}") }
        }
    }
}

/** The PackageInstaller session's result: opens the system confirm screen when it asks for one. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                Log.w(TAG, "install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                // The profile is never worth an update: the next try installs the APK alone.
                File(context.cacheDir, DM).delete()
                File(context.filesDir, RELAUNCH).delete()
            }
        }
    }
}

/**
 * Installing an update kills the app and nothing restarts it. When the update came from the Update row,
 * this opens the player again. Fire OS lets a background app start an activity only if it may draw
 * over other apps, granted once over adb: `appops set com.mohamed3on.iptv SYSTEM_ALERT_WINDOW allow`.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!File(context.filesDir, RELAUNCH).delete()) return
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

private const val TAG = "Updater"
private const val DM = "update.dm"
private const val RELAUNCH = "relaunch-after-update"
