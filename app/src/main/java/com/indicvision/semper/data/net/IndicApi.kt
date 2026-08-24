package com.indicvision.semper.data.net

import android.content.Context
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.data.DevAuth
import com.indicvision.semper.data.DeviceKeyManager
import com.indicvision.semper.util.Digests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.CertificatePinner
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

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
class IndicApi private constructor(context: Context) {

    private val appContext = context.applicationContext

    // Lazy: DeviceKeyManager touches the AndroidKeyStore in its constructor,
    // which only exists on a device. Deferring it keeps every non-signed path
    // (uploads, downloads, probes) constructible in JVM unit tests.
    private val device by lazy { DeviceKeyManager(appContext) }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val base = BuildConfig.INDIC_API_BASE_URL.trimEnd('/').also { url ->
        require(url.isEmpty() || url.startsWith("https://")) {
            "INDIC_API_BASE_URL must be https (or empty to disable cloud): $url"
        }
    }

    /**
     * Cloud calls are possible: a base URL is configured and we are not running
     * under the debug emulator sign-in bypass (which has no Firebase user, so
     * every authenticated call would fail — see [DevAuth]).
     */
    val enabled: Boolean get() = base.isNotBlank() && !DevAuth.active

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val octet = "application/octet-stream".toMediaType()
    private val drive = DriveTransfer(client, downloadClient, octet)

    /**
     * A non-2xx from the Semper backend.
     *
     * [requestId] is the response's `X-Request-Id` when the backend answered at
     * all (absent for a gateway kill with no headers). It is carried into the
     * message so every place that already shows or logs an exception message —
     * the restore worker's failure output, Timber, Crashlytics — becomes
     * joinable with the backend access log without touching those call sites.
     */
    class ApiException(
        val code: Int,
        val detail: String,
        val requestId: String? = null,
    ) : IOException(
        "HTTP $code: $detail" + if (requestId.isNullOrBlank()) "" else " (ref: $requestId)",
    )

    class NotApprovedException : IOException("not_approved")

    /**
     * This account is bound to a *different* device (registration refused).
     * [requestId] is the backend's `X-Request-Id` when it answered — a device
     * rebind is a support conversation, so the log line has to be findable.
     */
    class DeviceConflictException(val requestId: String? = null) : IOException("device_conflict")

    /**
     * The backend has no ACTIVE device record for us — the record was revoked or
     * deleted server-side while we still believed we were registered. Callers
     * should re-register and retry rather than give up.
     */
    class DeviceNotActiveException(val requestId: String? = null) : IOException("device_not_active")

    /** Maps a failed signed-request response to the most specific exception. */
    private fun failSigned(resp: Response): Nothing =
        failSigned(resp.code, IndicApiHttp.bodyText(resp), IndicApiHttp.requestIdOf(resp))

    /**
     * As above when the body has already been read. Matches on the parsed
     * `detail` code ([ApiErrors]) rather than a substring of the whole body.
     */
    @Suppress("ThrowsCount") // one throw per distinct 409 sub-reason, then the fallback
    private fun failSigned(code: Int, body: String, requestId: String?): Nothing {
        if (code == HttpStatus.CONFLICT) {
            if (ApiErrors.hasCode(body, ApiErrors.DEVICE_NOT_ACTIVE)) throw DeviceNotActiveException(requestId)
            if (ApiErrors.hasCode(body, ApiErrors.DEVICE_CONFLICT)) throw DeviceConflictException(requestId)
        }
        throw ApiException(code, body, requestId)
    }

    /** The generic failure for an unsigned call, with its correlation id. */
    private fun apiError(resp: Response): ApiException =
        ApiException(resp.code, IndicApiHttp.bodyText(resp), IndicApiHttp.requestIdOf(resp))

    /**
     * A bearer-authenticated GET. Returns the 200 body; on any other status
     * defers to [onError], which throws the endpoint's most specific exception
     * (the default is a plain [ApiException]).
     */
    private fun authedGet(
        idToken: String,
        url: String,
        onError: (Int, String, String?) -> Nothing = { code, body, ref -> throw ApiException(code, body, ref) },
    ): String {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $idToken")
            .header("X-Device-Id", device.getDeviceId())
            .get().build()
        client.newCall(req).execute().use { resp ->
            return if (resp.code == HttpStatus.OK) {
                resp.body.string()
            } else {
                onError(resp.code, IndicApiHttp.bodyText(resp), IndicApiHttp.requestIdOf(resp))
            }
        }
    }

    // ---------------------------------------------------------------- identity

    /** GET /v1/me. Throws [NotApprovedException] for a PENDING/SUSPENDED user. */
    suspend fun me(idToken: String): MeResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            authedGet(idToken, "$base/v1/me") { code, body, ref ->
                if (code == HttpStatus.FORBIDDEN) throw NotApprovedException()
                if (code == HttpStatus.CONFLICT) throw DeviceConflictException(ref)
                throw ApiException(code, body, ref)
            },
        )
    }

    /** GET /v1/config — resolved product limits for this account. */
    suspend fun getConfig(idToken: String): AppConfigDto = withContext(Dispatchers.IO) {
        json.decodeFromString(
            authedGet(idToken, "$base/v1/config") { code, body, ref ->
                if (code == HttpStatus.FORBIDDEN) throw NotApprovedException() else throw ApiException(code, body, ref)
            },
        )
    }

    /**
     * GET /v1/me/export — everything the cloud holds about this account, as JSON
     * (GDPR Art. 20 portability): profile, registered devices, and every analysis
     * with its file manifest.
     *
     * Streams straight to [dest] rather than into memory: the response is
     * unbounded in principle, and the backend emits it incrementally.
     *
     * Device-signed like account erasure — a full-account dump is high enough
     * consequence that a stolen ID token alone must not trigger it.
     *
     * Distinct from the local "Export my data" zip, which only bundles what is on
     * this device.
     */
    suspend fun exportAccount(idToken: String, dest: java.io.File): Unit = withContext(Dispatchers.IO) {
        val resp = signedRequest(idToken, "GET", "/v1/me/export", ByteArray(0))
        resp.use {
            if (it.code != HttpStatus.OK) failSigned(it)
            val part = java.io.File(dest.parentFile, dest.name + ".part")
            it.body.byteStream().use { input ->
                part.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            // Rename only after the whole body landed: a truncated transfer must
            // not look like a complete export.
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        }
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
                HttpStatus.CREATED, HttpStatus.OK -> Unit
                HttpStatus.CONFLICT -> throw DeviceConflictException(IndicApiHttp.requestIdOf(resp))
                else -> throw apiError(resp)
            }
        }
    }

    // ----------------------------------------------------------- session/files

    /**
     * GET /v1/sessions — the caller's cloud analyses (for sync reconciliation).
     *
     * Follows `nextPageToken` until the account listing is complete so quota
     * reconciliation is not silently truncated by server page size.
     *
     * [verify] makes the backend also confirm each page's session blobs still
     * exist in Drive (catching artifacts deleted straight in Drive). It costs
     * Drive calls per page, so it's for explicit refreshes, not every resume.
     */
    suspend fun listSessions(
        idToken: String,
        verify: Boolean = false,
    ): ListSessionsResponse = withContext(Dispatchers.IO) {
        val all = mutableListOf<CloudSessionDto>()
        var pageToken: String? = null
        var lastQuota = QuotaDto()
        do {
            val qs = buildString {
                append("page_size=100")
                if (verify) append("&verify=true")
                if (!pageToken.isNullOrBlank()) append("&page_token=").append(pageToken)
            }
            val page: ListSessionsResponse = json.decodeFromString(
                authedGet(idToken, "$base/v1/sessions?$qs") { code, body, ref ->
                    if (code == HttpStatus.FORBIDDEN) {
                        throw NotApprovedException()
                    } else {
                        throw ApiException(code, body, ref)
                    }
                },
            )
            all += page.sessions
            lastQuota = page.quota
            pageToken = page.page?.nextPageToken?.takeIf { page.page.hasMore }
        } while (pageToken != null)
        ListSessionsResponse(sessions = all, quota = lastQuota)
    }

    /** POST /v1/sessions (device-signed). Initiates a session + one resumable target per file. */
    suspend fun createSession(
        idToken: String,
        request: SessionCreateRequest,
    ): SessionCreateResponse = withContext(Dispatchers.IO) {
        val bodyBytes = json.encodeToString(request).toByteArray()
        val resp = signedPost(idToken, "/v1/sessions", bodyBytes)
        resp.use {
            if (it.code == HttpStatus.OK) {
                json.decodeFromString(it.body.string())
            } else {
                failSigned(it)
            }
        }
    }

    /**
     * GET /v1/sessions/{sid}/uploads — what still needs uploading.
     *
     * The resume path: an interrupted upload continues into the same session
     * instead of POSTing a new one (which would duplicate the Drive folder and
     * consume another slot of the analysis quota).
     *
     * Device-signed, not just token-authenticated: the response carries Drive
     * resumable upload URIs, which are bearer capabilities to write into the
     * user's Drive folder. The backend requires attestation here for the same
     * reason it does on createSession — a stolen ID token must not be able to
     * recover them.
     */
    suspend fun sessionUploads(
        idToken: String,
        sessionId: String,
    ): SessionUploadsResponse = withContext(Dispatchers.IO) {
        val resp = signedRequest(idToken, "GET", "/v1/sessions/$sessionId/uploads", ByteArray(0))
        resp.use {
            if (it.code == HttpStatus.OK) {
                json.decodeFromString(it.body.string())
            } else {
                failSigned(it)
            }
        }
    }

    /** POST /v1/files/{id}/complete (device-signed). */
    suspend fun completeFile(
        idToken: String,
        fileId: String,
        request: FileCompleteRequest,
    ) = withContext(Dispatchers.IO) {
        val bodyBytes = json.encodeToString(request).toByteArray()
        val resp = signedPost(idToken, "/v1/files/$fileId/complete", bodyBytes)
        resp.use { if (it.code != HttpStatus.OK) failSigned(it) }
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
     * Device-attested like writes: fresh nonce + ECDSA over method/path/empty
     * body per attempt. `Range` is an unsigned header (not part of the signed
     * message) so resume offsets can change without rehashing the body.
     *
     * Writes to a sibling `.part` file and renames on success. If the transfer
     * drops mid-stream, retries with `Range: bytes=N-` so already-received
     * bytes are kept (backend forwards Range to Drive and returns 206).
     */
    /**
     * Fetch only `[rangeStart, rangeStart + length)` of an object.
     *
     * Restore uses this to read a legacy backup's central directory and then just the
     * prefix of entries it needs, instead of the whole archive.
     */
    suspend fun downloadRange(
        idToken: String,
        fileId: String,
        dest: java.io.File,
        rangeStart: Long,
        length: Long,
    ) = drive.downloadFile(
        fileId,
        dest,
        base,
        expectedBytes = length,
        rangeStart = rangeStart,
    ) { path ->
        val nonce = fetchChallenge(idToken)
        signedHeaders(idToken, "GET", path, ByteArray(0), nonce)
    }

    suspend fun downloadFile(
        idToken: String,
        fileId: String,
        dest: java.io.File,
        expectedBytes: Long = -1L,
        onBytes: suspend (haveBytes: Long) -> Unit = {},
    ) = drive.downloadFile(
        fileId,
        dest,
        base,
        expectedBytes = expectedBytes,
        onBytes = onBytes,
    ) { path ->
        val nonce = fetchChallenge(idToken)
        signedHeaders(idToken, "GET", path, ByteArray(0), nonce)
    }

    // ------------------------------------------------------------------- admin

    /** GET /v1/admin/users?status=… (admin ID token; no device signature). */
    suspend fun listUsers(idToken: String, status: String = ""): List<AdminUserDto> = withContext(Dispatchers.IO) {
        val url = if (status.isBlank()) "$base/v1/admin/users" else "$base/v1/admin/users?status=$status"
        json.decodeFromString<AdminUsersResponse>(
            authedGet(idToken, url) { code, body, ref ->
                if (code == HttpStatus.FORBIDDEN) {
                    throw ApiException(HttpStatus.FORBIDDEN, "not_admin", ref)
                } else {
                    throw ApiException(code, body, ref)
                }
            },
        ).users
    }

    /** POST /v1/admin/users/{uid}/{action} — device-attested (approve/revoke). */
    suspend fun setUserStatus(idToken: String, uid: String, action: String) = withContext(Dispatchers.IO) {
        signedPost(idToken, "/v1/admin/users/$uid/$action", ByteArray(0)).use { resp ->
            if (resp.code != HttpStatus.OK) throw apiError(resp)
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
            "GET" -> builder.get()
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
        resp.use { if (it.code != HttpStatus.OK) failSigned(it) }
    }

    /**
     * DELETE /v1/sessions/{id} — erase an analysis from the cloud: the Drive
     * folder (raw images, .dat, csv, report) and all Firestore metadata.
     * Permanent; there is no undo.
     */
    suspend fun deleteSession(idToken: String, sessionId: String) = withContext(Dispatchers.IO) {
        val resp = signedRequest(idToken, "DELETE", "/v1/sessions/$sessionId", ByteArray(0))
        resp.use {
            if (it.code == HttpStatus.OK) return@use
            val body = IndicApiHttp.bodyText(it)
            // A 404 is only "already erased" when OUR backend says so
            // (`session_not_found`). A bare 404 means the route isn't reachable —
            // accepting that as success would delete the local copy and orphan
            // the cloud data forever.
            if (it.code == HttpStatus.NOT_FOUND && ApiErrors.hasCode(body, ApiErrors.SESSION_NOT_FOUND)) {
                return@use
            }
            failSigned(it.code, body, IndicApiHttp.requestIdOf(it))
        }
    }

    /** POST /v1/challenge → single-use nonce bound to (uid, deviceId). */
    private fun fetchChallenge(idToken: String): String {
        val req = Request.Builder().url("$base/v1/challenge")
            .header("Authorization", "Bearer $idToken")
            .header("X-Device-Id", device.getDeviceId())
            .post(ByteArray(0).toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != HttpStatus.OK) throw apiError(resp)
            val nonce: ChallengeResponse = json.decodeFromString(resp.body.string())
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
        val msg = (nonce + method + path).toByteArray() + Digests.sha256(bodyBytes)
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
     * go straight to Drive — not through the backend.
     * Returns (driveFileId, localMd5Hex) — md5 is always set for `:complete`.
     */
    suspend fun uploadResumable(
        uploadUrl: String,
        file: java.io.File,
        chunkSize: Int,
        onBytes: (Long) -> Unit = {},
    ): Pair<String, String> = drive.uploadResumable(uploadUrl, file, chunkSize, onBytes)

    companion object {
        // One connection pool + dispatcher shared by every IndicApi instance.
        // The class is constructed per worker/repo (many times), and a fresh
        // OkHttpClient each time would throw away TLS session reuse and
        // keep-alive. downloadClient shares this pool via newBuilder().
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS) // large chunk PUTs to Drive
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .apply {
                val pins = BuildConfig.INDIC_API_CERT_PINS.trim()
                val host = runCatching {
                    BuildConfig.INDIC_API_BASE_URL.trimEnd('/')
                        .removePrefix("https://")
                        .substringBefore('/')
                }.getOrNull().orEmpty()
                if (pins.isNotEmpty() && host.isNotEmpty()) {
                    val pinner = CertificatePinner.Builder().also { b ->
                        pins.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { b.add(host, it) }
                    }.build()
                    certificatePinner(pinner)
                }
            }
            .build()

        /** Longer read idle for large Session.zip / legacy restores through the proxy. */
        private val downloadClient = client.newBuilder()
            .readTimeout(DOWNLOAD_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        @Volatile
        private var instance: IndicApi? = null

        /**
         * Process-wide client. Shares OkHttp pools and DeviceKeyManager; call sites
         * must not construct [IndicApi] directly.
         */
        fun get(context: Context): IndicApi {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: IndicApi(context.applicationContext).also { instance = it }
            }
        }
    }
}
