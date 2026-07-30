package com.indicvision.semper.data.net

import android.content.Context
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.data.DevAuth
import com.indicvision.semper.data.DeviceKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Drive resumable chunks must be 256 KiB multiples (except the final one). */
private const val MIN_CHUNK_BYTES = 256 * 1024

/** Upper bound on the per-chunk buffer allocation, whatever the server says. */
private const val MAX_CHUNK_BYTES = 32 * 1024 * 1024

/** How many times a truncated download may resume from the last byte. */
private const val DOWNLOAD_MAX_ATTEMPTS = 5

/** Copy buffer for proxied restore downloads. */
private const val DOWNLOAD_COPY_BUFFER = 1 shl 16

// HTTP status codes this client branches on.
private const val HTTP_OK = 200
private const val HTTP_CREATED = 201
private const val HTTP_PARTIAL_CONTENT = 206
private const val HTTP_RESUME_INCOMPLETE = 308
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_CONFLICT = 409
private const val HTTP_RANGE_NOT_SATISFIABLE = 416

// OkHttp client timeouts, in seconds.
private const val CONNECT_TIMEOUT_S = 30L
private const val WRITE_TIMEOUT_S = 300L
private const val READ_TIMEOUT_S = 60L
private const val DOWNLOAD_READ_TIMEOUT_S = 300L

/**
 * Client for the Semper GCP backend (Cloud Run / FastAPI).
 *
 * Every mutating call carries a Google **ID token** (user proof) plus a
 * challenge-response **device signature** (device proof). File bytes go
 * **directly to Google Drive** via the resumable session URI returned by the
 * broker — they never pass through this client's backend host.
 */
@Suppress("TooManyFunctions") // one method per backend endpoint plus signing helpers
class IndicApi(context: Context) {

    private val appContext = context.applicationContext

    // Lazy: DeviceKeyManager touches the AndroidKeyStore in its constructor,
    // which only exists on a device. Deferring it keeps every non-signed path
    // (uploads, downloads, probes) constructible in JVM unit tests.
    private val device by lazy { DeviceKeyManager(appContext) }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val base = BuildConfig.INDIC_API_BASE_URL.trimEnd('/')

    /**
     * Cloud calls are possible: a base URL is configured and we are not running
     * under the debug emulator sign-in bypass (which has no Firebase user, so
     * every authenticated call would fail — see [DevAuth]).
     */
    val enabled: Boolean get() = base.isNotBlank() && !DevAuth.active

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS) // large chunk PUTs to Drive
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    /** Longer read idle for large Session.zip / legacy restores through the proxy. */
    private val downloadClient = client.newBuilder()
        .readTimeout(DOWNLOAD_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val octet = "application/octet-stream".toMediaType()

    class ApiException(val code: Int, val detail: String) : IOException("HTTP $code: $detail")
    class NotApprovedException : IOException("not_approved")

    /** This account is bound to a *different* device (registration refused). */
    class DeviceConflictException : IOException("device_conflict")

    /**
     * The backend has no ACTIVE device record for us — the record was revoked or
     * deleted server-side while we still believed we were registered. Callers
     * should re-register and retry rather than give up.
     */
    class DeviceNotActiveException : IOException("device_not_active")

    /** Maps a failed signed-request response to the most specific exception. */
    @Suppress("ThrowsCount") // one throw per distinct 409 sub-reason, then the fallback
    private fun failSigned(code: Int, body: String): Nothing {
        if (code == HTTP_CONFLICT && body.contains("device_not_active")) throw DeviceNotActiveException()
        if (code == HTTP_CONFLICT && body.contains("device_conflict")) throw DeviceConflictException()
        throw ApiException(code, body)
    }

    /**
     * A bearer-authenticated GET. Returns the 200 body; on any other status
     * defers to [onError], which throws the endpoint's most specific exception
     * (the default is a plain [ApiException]).
     */
    private fun authedGet(
        idToken: String,
        url: String,
        onError: (Int, String) -> Nothing = { code, body -> throw ApiException(code, body) },
    ): String {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $idToken").get().build()
        client.newCall(req).execute().use { resp ->
            return if (resp.code == HTTP_OK) resp.body!!.string() else onError(resp.code, resp.bodyText())
        }
    }

    // ---------------------------------------------------------------- identity

    /** GET /v1/me. Throws [NotApprovedException] for a PENDING/SUSPENDED user. */
    suspend fun me(idToken: String): MeResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            authedGet(idToken, "$base/v1/me") { code, body ->
                if (code == HTTP_FORBIDDEN) throw NotApprovedException() else throw ApiException(code, body)
            },
        )
    }

    /**
     * POST /v1/devices/register. Registers this device's public key.
     * 201 → registered, 409 → another device already bound (needs admin rebind).
     * Requires an APPROVED user.
     */
    suspend fun registerDevice(idToken: String) = withContext(Dispatchers.IO) {
        val body = DeviceRegisterRequest(
            deviceId = device.getDeviceId(),
            publicKeyPem = device.getPublicKeyPem(),
            model = android.os.Build.MODEL ?: "",
            osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            appVersion = BuildConfig.VERSION_NAME,
        )
        val req = Request.Builder().url("$base/v1/devices/register")
            .header("Authorization", "Bearer $idToken")
            .post(json.encodeToString(body).toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                HTTP_CREATED, HTTP_OK -> Unit
                HTTP_CONFLICT -> throw DeviceConflictException()
                else -> throw ApiException(resp.code, resp.bodyText())
            }
        }
    }

    // ----------------------------------------------------------- session/files

    /**
     * GET /v1/sessions — the caller's cloud analyses (for sync reconciliation).
     *
     * [verify] makes the backend also confirm each session's blobs still exist
     * in Drive (catching artifacts deleted straight in Drive, which the
     * Firestore index alone can't see). It costs a Drive call per session, so
     * it's for explicit refreshes, not every resume.
     */
    suspend fun listSessions(
        idToken: String,
        verify: Boolean = false,
    ): ListSessionsResponse = withContext(Dispatchers.IO) {
        val url = if (verify) "$base/v1/sessions?verify=true" else "$base/v1/sessions"
        json.decodeFromString(
            authedGet(idToken, url) { code, body ->
                if (code == HTTP_FORBIDDEN) throw NotApprovedException() else throw ApiException(code, body)
            },
        )
    }

    /** POST /v1/sessions (device-signed). Initiates a session + one resumable target per file. */
    suspend fun createSession(
        idToken: String,
        request: SessionCreateRequest,
    ): SessionCreateResponse = withContext(Dispatchers.IO) {
        val bodyBytes = json.encodeToString(request).toByteArray()
        val resp = signedPost(idToken, "/v1/sessions", bodyBytes)
        resp.use {
            if (it.code == HTTP_OK) {
                json.decodeFromString(it.body!!.string())
            } else {
                failSigned(it.code, it.bodyText())
            }
        }
    }

    /**
     * GET /v1/sessions/{sid}/uploads — what still needs uploading.
     *
     * The resume path: an interrupted upload continues into the same session
     * instead of POSTing a new one (which would duplicate the Drive folder and
     * consume another slot of the analysis quota).
     */
    suspend fun sessionUploads(
        idToken: String,
        sessionId: String,
    ): SessionUploadsResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(authedGet(idToken, "$base/v1/sessions/$sessionId/uploads"))
    }

    /** POST /v1/files/{id}/complete (device-signed). */
    suspend fun completeFile(
        idToken: String,
        fileId: String,
        request: FileCompleteRequest,
    ) = withContext(Dispatchers.IO) {
        val bodyBytes = json.encodeToString(request).toByteArray()
        val resp = signedPost(idToken, "/v1/files/$fileId/complete", bodyBytes)
        resp.use { if (it.code != HTTP_OK) failSigned(it.code, it.bodyText()) }
    }

    // ----------------------------------------------------------------- restore

    /** GET /v1/sessions/{sid}/files — the manifest for one cloud analysis. */
    suspend fun listSessionFiles(
        idToken: String,
        sessionId: String,
    ): SessionFilesResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(authedGet(idToken, "$base/v1/sessions/$sessionId/files"))
    }

    /**
     * GET /v1/files/{id}/content — stream a file back from Drive into [dest].
     * These bytes are proxied by the backend (Drive has no anonymous download),
     * so this is the one path where the backend touches file content.
     *
     * Writes to a sibling `.part` file and renames on success. If the transfer
     * drops mid-stream, retries with `Range: bytes=N-` so already-received
     * bytes are kept (backend forwards Range to Drive and returns 206).
     */
    @Suppress("CyclomaticComplexMethod") // one branch per HTTP status × resume/retry outcome
    suspend fun downloadFile(idToken: String, fileId: String, dest: java.io.File) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = java.io.File(dest.parentFile, "${dest.name}.part")
        var attempt = 0
        while (true) {
            attempt++
            val offset = if (part.exists()) part.length() else 0L
            try {
                val builder = Request.Builder()
                    .url("$base/v1/files/$fileId/content")
                    .header("Authorization", "Bearer $idToken")
                    .get()
                if (offset > 0L) builder.header("Range", "bytes=$offset-")
                downloadClient.newCall(builder.build()).execute().use { resp ->
                    when (resp.code) {
                        HTTP_OK, HTTP_PARTIAL_CONTENT -> {
                            // 200 = full body (fresh start / proxy ignored Range) → overwrite;
                            // 206 = partial → append to the bytes already on disk.
                            val append = resp.code == HTTP_PARTIAL_CONTENT
                            java.io.FileOutputStream(part, append).use { out ->
                                resp.body!!.byteStream().use { input -> input.copyTo(out, DOWNLOAD_COPY_BUFFER) }
                            }
                        }
                        HTTP_RANGE_NOT_SATISFIABLE -> {
                            // Stale offset (partial longer than the object). Restart once.
                            if (offset > 0L && attempt < DOWNLOAD_MAX_ATTEMPTS) {
                                part.delete()
                                throw IOException("range_not_satisfiable; restarting $fileId")
                            }
                            throw ApiException(resp.code, resp.bodyText())
                        }
                        else -> throw ApiException(resp.code, resp.bodyText())
                    }
                }
                if (dest.exists() && !dest.delete()) {
                    Timber.w("Could not replace existing download target %s", dest)
                }
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                return@withContext
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                if (attempt >= DOWNLOAD_MAX_ATTEMPTS) throw e
                Timber.w(
                    e,
                    "download %s interrupted at %d bytes (attempt %d); resuming",
                    fileId,
                    if (part.exists()) part.length() else 0L,
                    attempt,
                )
            }
        }
    }

    // ------------------------------------------------------------------- admin

    /** GET /v1/admin/users?status=… (admin ID token; no device signature). */
    suspend fun listUsers(idToken: String, status: String = ""): List<AdminUserDto> = withContext(Dispatchers.IO) {
        val url = if (status.isBlank()) "$base/v1/admin/users" else "$base/v1/admin/users?status=$status"
        json.decodeFromString<AdminUsersResponse>(
            authedGet(idToken, url) { code, body ->
                if (code == HTTP_FORBIDDEN) {
                    throw ApiException(HTTP_FORBIDDEN, "not_admin")
                } else {
                    throw ApiException(code, body)
                }
            },
        ).users
    }

    /** POST /v1/admin/users/{uid}/{action} where action is "approve" or "revoke". */
    suspend fun setUserStatus(idToken: String, uid: String, action: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/admin/users/$uid/$action")
            .header("Authorization", "Bearer $idToken")
            .post(ByteArray(0).toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != HTTP_OK) throw ApiException(resp.code, resp.bodyText())
        }
    }

    // ------------------------------------------------------- device-signed POST

    private fun signedPost(idToken: String, path: String, bodyBytes: ByteArray): Response =
        signedRequest(idToken, "POST", path, bodyBytes)

    /** A device-signed request. The signature covers method + path + body hash. */
    private fun signedRequest(
        idToken: String,
        method: String,
        path: String,
        bodyBytes: ByteArray,
    ): Response {
        val nonce = fetchChallenge(idToken)
        val headers = signedHeaders(idToken, method, path, bodyBytes, nonce)
        val builder = Request.Builder().url("$base$path").headers(headers)
        when (method) {
            "POST" -> builder.post(bodyBytes.toRequestBody(jsonMedia))
            // No body: the backend hashes empty bytes, so we must send none.
            "DELETE" -> builder.delete()
            else -> builder.method(method, bodyBytes.toRequestBody(jsonMedia))
        }
        return client.newCall(builder.build()).execute()
    }

    /**
     * DELETE /v1/me — erase the account and every analysis it owns from the
     * cloud. The caller must sign out immediately afterwards; any further
     * authenticated call would create a fresh, empty profile.
     */
    suspend fun deleteAccount(idToken: String) = withContext(Dispatchers.IO) {
        val resp = signedRequest(idToken, "DELETE", "/v1/me", ByteArray(0))
        // No 404-is-fine shortcut here: this endpoint never legitimately 404s,
        // so a 404 means the route isn't reachable (e.g. not published on the
        // API Gateway). Treating that as success would wipe the local copy while
        // leaving every byte in the cloud.
        resp.use { if (it.code != HTTP_OK) failSigned(it.code, it.bodyText()) }
    }

    /**
     * DELETE /v1/sessions/{id} — erase an analysis from the cloud: the Drive
     * folder (raw images, .dat, csv, report) and all Firestore metadata.
     * Permanent; there is no undo.
     */
    suspend fun deleteSession(idToken: String, sessionId: String) = withContext(Dispatchers.IO) {
        val resp = signedRequest(idToken, "DELETE", "/v1/sessions/$sessionId", ByteArray(0))
        resp.use {
            if (it.code == HTTP_OK) return@use
            val body = it.bodyText()
            // A 404 is only "already erased" when OUR backend says so
            // (`session_not_found`). A bare 404 means the route isn't reachable —
            // accepting that as success would delete the local copy and orphan
            // the cloud data forever.
            if (it.code == HTTP_NOT_FOUND && body.contains("session_not_found")) return@use
            failSigned(it.code, body)
        }
    }

    /** POST /v1/challenge → single-use nonce bound to (uid, deviceId). */
    private fun fetchChallenge(idToken: String): String {
        val req = Request.Builder().url("$base/v1/challenge")
            .header("Authorization", "Bearer $idToken")
            .header("X-Device-Id", device.getDeviceId())
            .post(ByteArray(0).toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != HTTP_OK) throw ApiException(resp.code, resp.bodyText())
            val nonce: ChallengeResponse = json.decodeFromString(resp.body!!.string())
            return nonce.nonce
        }
    }

    /** Signature over (nonce || METHOD || path) ++ SHA-256(body) — matches backend/app/deps.py. */
    private fun signedHeaders(
        idToken: String,
        method: String,
        path: String,
        bodyBytes: ByteArray,
        nonce: String,
    ): Headers {
        val msg = (nonce + method + path).toByteArray() + MessageDigest.getInstance("SHA-256").digest(bodyBytes)
        val sig = device.signMessage(msg)
        return Headers.Builder()
            .add("Authorization", "Bearer $idToken")
            .add("X-Device-Id", device.getDeviceId())
            .add("X-Nonce", nonce)
            .add("X-Signature", sig)
            .build()
    }

    // ------------------------------------------------- direct-to-Drive uploads

    /**
     * Resumable upload of [file] to a Drive [uploadUrl], in [chunkSize] chunks
     * (multiple of 256 KiB). Resumes from the server offset on reconnect. Bytes
     * go straight to Drive — not through the backend. Returns (driveFileId, md5).
     */
    suspend fun uploadResumable(
        uploadUrl: String,
        file: java.io.File,
        chunkSize: Int,
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        val total = file.length()
        // The buffer is allocated at chunk size — clamp what the server
        // sent so a misconfigured value can never OOM the app. Drive needs
        // chunks in 256 KiB multiples (except the last).
        val chunk = chunkSize.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)

        // Where does Drive want us to continue — or does it already have the
        // whole file? A file fully uploaded in a prior attempt (but whose
        // completeFile never ran) reports COMPLETE here; return its resource
        // instead of trying to re-send zero bytes and failing.
        val probe = probeStatus(uploadUrl, total)
        probe.result?.let { return@withContext it }
        var offset = probe.offset

        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(chunk)
            while (offset < total) {
                raf.seek(offset)
                val n = raf.read(buf, 0, minOf(chunk.toLong(), total - offset).toInt())
                if (n <= 0) throw IOException("unexpected EOF at $offset/$total")
                val end = offset + n - 1
                val req = Request.Builder().url(uploadUrl)
                    .header("Content-Range", "bytes $offset-$end/$total")
                    .put(buf.toRequestBody(octet, 0, n)).build()
                client.newCall(req).execute().use { resp ->
                    when (resp.code) {
                        HTTP_RESUME_INCOMPLETE -> offset = end + 1
                        HTTP_OK, HTTP_CREATED -> {
                            val bodyStr = resp.body?.string().orEmpty()
                            return@withContext parseDriveResult(bodyStr)
                        }
                        else -> throw ApiException(resp.code, resp.body?.string().orEmpty())
                    }
                }
            }
        }

        // Loop reached `total` without a final 200/201 — the last bytes were
        // already on Drive from a previous attempt. Re-probe to finalize and
        // get the resource, rather than failing.
        probeStatus(uploadUrl, total).result
            ?: throw IOException("upload finished without a final Drive response")
    }

    /** Current state of a resumable session: continue at [offset], or already [result]. */
    private data class UploadProbe(val offset: Long, val result: Pair<String, String?>?)

    /** Ask Drive what it already has: PUT `bytes * /total` with an empty body. */
    private fun probeStatus(uploadUrl: String, total: Long): UploadProbe {
        val req = Request.Builder().url(uploadUrl)
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(octet)).build()
        client.newCall(req).execute().use { resp ->
            return when (resp.code) {
                // Resume Incomplete: Range tells us the last byte received (may be absent = nothing yet).
                HTTP_RESUME_INCOMPLETE ->
                    UploadProbe(resp.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L, null)
                // Already complete — the body is the Drive file resource.
                HTTP_OK, HTTP_CREATED -> UploadProbe(total, parseDriveResult(resp.body?.string().orEmpty()))
                // 404/410 = session expired; start fresh (caller re-inits on retry).
                else -> UploadProbe(0L, null)
            }
        }
    }

    private fun parseDriveResult(body: String): Pair<String, String?> {
        val obj = org.json.JSONObject(body)
        val id = obj.optString("id")
        val md5 = if (obj.has("md5Checksum")) obj.optString("md5Checksum") else null
        return id to md5
    }

    private fun Response.bodyText(): String = try {
        body?.string().orEmpty()
    } catch (e: IOException) {
        Timber.w(e, "reading error body")
        ""
    }
}
