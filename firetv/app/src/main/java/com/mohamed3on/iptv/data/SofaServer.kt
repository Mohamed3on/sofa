package com.mohamed3on.iptv.data

import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Why a call to the box failed: every call's failure is one of these. Never carries the URL or token (the repo is public);
 * no stack trace or suppressed exceptions, as the objects are shared.
 */
sealed class SyncError : Exception(null, null, false, false) {
    /** No response: no network, DNS failure, refused, timed out, or cut off mid-download. */
    data object Offline : SyncError()
    /** Any status but 2xx (and 304 where an ETag was sent). */
    data class Rejected(val status: Int) : SyncError()
    /** A 2xx whose body is not what was asked for (e.g. a Cloudflare challenge page). */
    data object Malformed : SyncError()
}

/** A [SofaServer.download]'s outcome: the copy sent with its ETag was still current, or the new one is saved. */
sealed interface Download {
    data object Unchanged : Download
    data class Saved(val etag: String?) : Download
}

/**
 * The one client for the Sofa server (/api/app/…): its address, the app token, JSON bodies and status mapping. Every call
 * answers a [Result] whose failure is a [SyncError]. Calls block: run them off the main thread.
 */
class SofaServer(private val http: OkHttpClient, private val baseUrl: String, private val token: String) {
    /** GET /api/app/[path] (its query in it); [read] takes a 2xx answer's body. */
    fun <T> get(path: String, read: (ResponseBody) -> T): Result<T> = call(request(path)) { read(it.body) }

    /** POSTs [body] to /api/app/[path]; [read] takes a 2xx answer's body. */
    fun <T> post(path: String, body: JsonElement, read: (ResponseBody) -> T): Result<T> =
        call(request(path).post(body.toString().toRequestBody(JSON))) { read(it.body) }

    /** GET /api/app/[path] into [to], sending [etag] as If-None-Match. */
    fun download(path: String, to: File, etag: String?): Result<Download> =
        call(request(path).apply { etag?.let { header("If-None-Match", it) } }, notModified = Download.Unchanged) { res ->
            to.outputStream().use { res.body.byteStream().copyTo(it) }
            Download.Saved(res.header("ETag"))
        }

    private fun request(path: String) = Request.Builder().url("$baseUrl/api/app/$path${if ('?' in path) '&' else '?'}t=$token")

    /** A GET that got no response is asked once more a second later: a blip (Wi-Fi, the tunnel) otherwise strands a page on its error. */
    private fun <T> call(request: Request.Builder, notModified: T? = null, read: (Response) -> T): Result<T> {
        val built = request.build()
        return once(built, notModified, read).let {
            if (it.exceptionOrNull() != SyncError.Offline || built.method != "GET") it else { Thread.sleep(1_000); once(built, notModified, read) }
        }
    }

    private fun <T> once(request: Request, notModified: T?, read: (Response) -> T): Result<T> {
        val error = try {
            http.newCall(request).execute().use { res ->
                when {
                    res.code == 304 && notModified != null -> return Result.success(notModified)
                    !res.isSuccessful -> SyncError.Rejected(res.code)
                    else -> try {
                        return Result.success(read(res))
                    } catch (e: IOException) {
                        throw e
                    } catch (e: Exception) {
                        SyncError.Malformed
                    }
                }
            }
        } catch (e: IOException) {
            SyncError.Offline
        }
        return Result.failure(error)
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/**
 * Files kept current from the box, each with the ETag it came with (etag.<kind> in [store]). A download goes to a temp
 * file, is validated, then moved over the old file, so a failed or malformed refresh keeps the previous data (R25).
 */
class BoxFiles(private val server: SofaServer, private val store: KeyValueStore, private val now: () -> Long) {
    /**
     * Brings [file] up to date from /api/app/[kind] ([query] on its URL) unless it is younger than [maxAge]; [install]
     * validates and loads the download, moving its file over [file] with its second argument. Null when current.
     */
    fun sync(kind: String, file: File, maxAge: Duration, query: String = "", install: (File, keep: (File) -> Unit) -> Unit): SyncError? {
        if (file.exists() && (now() - file.lastModified()).milliseconds < maxAge) return null
        val tmp = File(file.parentFile, "$kind.download")
        try {
            val etag = store.get("etag.$kind")?.takeIf { file.exists() }
            when (val d = server.download(if (query.isEmpty()) kind else "$kind?$query", tmp, etag).getOrElse { return it as SyncError }) {
                Download.Unchanged -> file.setLastModified(now())
                is Download.Saved -> try {
                    install(tmp) { new ->
                        if (!new.renameTo(file)) throw IOException("rename failed")
                        store.put("etag.$kind", d.etag)
                    }
                } catch (e: Exception) {
                    return SyncError.Malformed
                }
            }
            return null
        } finally {
            tmp.delete()
        }
    }
}
