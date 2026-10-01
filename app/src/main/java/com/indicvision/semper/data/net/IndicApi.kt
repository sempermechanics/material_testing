package com.indicvision.semper.data.net

import android.content.Context
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.data.account.DevAuth
import com.indicvision.semper.data.account.DeviceKeyManager
import com.indicvision.semper.util.AtomicFiles
import com.indicvision.semper.util.writeVia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.CertificatePinner
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// OkHttp client timeouts, in seconds.
private const val CONNECT_TIMEOUT_S = 30L
private const val WRITE_TIMEOUT_S = 300L
private const val READ_TIMEOUT_S = 60L
private const val DOWNLOAD_READ_TIMEOUT_S = 300L

/** Seat routes take no body; the backend reads the caller from the token. */
private const val EMPTY_JSON = "{}"

/** `GET /v1/sessions` page size. */
private const val SESSIONS_PAGE_SIZE = 100

/**
 * Client for the Semper GCP backend (Cloud Run / FastAPI).
 *
 * Every mutating call carries a Google **ID token** (user proof) plus a
 * challenge-response **device signature** (device proof, [IndicApiSigning]).
 * File bytes go **directly to Google Drive** via the resumable session URI
 * returned by the broker — they never pass through this client's backend host.
 */
@Suppress("TooManyFunctions") // one method per backend endpoint
class IndicApi private constructor(context: Context) : CloudApi {

    private val appContext = context.applicationContext

    // Lazy: DeviceKeyManager touches the AndroidKeyStore in its constructor,
    // which only exists on a device. Deferring it keeps every non-signed path
    // (uploads, downloads, probes) constructible in JVM unit tests.
    private val device by lazy { DeviceKeyManager(appContext) }
    private val json = IndicApiHttp.json

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
    override val enabled: Boolean get() = base.isNotBlank() && !DevAuth.active

    /** The backend URL for [path]; see [IndicApiHttp.endpoint]. */
    private fun endpoint(path: String): String = IndicApiHttp.endpoint(base, path)

    private val drive = DriveTransfer(client, downloadClient, IndicApiHttp.OCTET_MEDIA)
    private val signing = IndicApiSigning(
        client,
        ::endpoint,
        deviceId = { device.getDeviceId() },
        sign = { device.signMessage(it) },
    )

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
        val body: String,
        val requestId: String? = null,
    ) : IOException(IndicApiHttp.withRef("HTTP $code: $body", requestId)) {

        val parsedDetail: String get() = ApiErrors.detailOf(body)
    }

    class NotApprovedException : IOException(ApiErrors.NOT_APPROVED)

    /**
     * No backend is configured (`INDIC_API_BASE_URL` is empty). An [IOException],
     * so every caller treats it like offline instead of crashing (TD-90).
     */
    class CloudNotConfiguredException : IOException("Cloud backend is not configured (INDIC_API_BASE_URL).")

    /** The Terms this build carries are older than the ones the server publishes (409). */
    class TermsVersionMismatchException(val requestId: String? = null) : IOException(ApiErrors.TERMS_VERSION_MISMATCH)

    /**
     * This account is bound to a *different* device (registration refused).
     * [requestId] is the backend's `X-Request-Id` when it answered — a device
     * rebind is a support conversation, so the log line has to be findable.
     */
    class DeviceConflictException(val requestId: String? = null) : IOException(ApiErrors.DEVICE_CONFLICT)

    /**
     * This device is already bound to a different account (409 from `GET /v1/me`).
     */
    class DeviceInUseException(val requestId: String? = null) : IOException(ApiErrors.DEVICE_IN_USE)

    /**
     * Every floating seat on the institution's license is in use right now.
     *
     * Not an account problem: the caller is still on the roster and still
     * entitled to a seat as soon as one frees. Distinct from [ApiException] so
     * callers cannot render it as a generic failure.
     */
    class NoSeatAvailableException : IOException(ApiErrors.NO_FLOATING_SEAT)

    /**
     * The backend has no ACTIVE device record for us — the record was revoked or
     * deleted server-side while we still believed we were registered. Callers
     * should re-register and retry rather than give up.
     */
    class DeviceNotActiveException(val requestId: String? = null) : IOException(ApiErrors.DEVICE_NOT_ACTIVE)

    /**
     * Drive no longer knows the resumable upload link (404 or 410 on the status
     * probe): the link expired, or the upload session was cancelled. Sending
     * bytes to it cannot work, and neither can a retry with the same link; the
     * cloud session has to be opened again.
     */
    class UploadLinkExpiredException(val code: Int) : IOException("Drive upload link expired (HTTP $code)")

    // ---------------------------------------------------------- request helpers

    /**
     * A token-authenticated call: the ID token and this device's id, then
     * [route]'s URL, method and body. [read] takes a 200; any other answer goes
     * to [onRefusal], which throws the route's most specific exception (a plain
     * [ApiException] unless it says otherwise) or accepts the answer.
     */
    private suspend fun <T> bearer(
        idToken: String,
        route: Request.Builder.() -> Unit,
        onRefusal: (Refusal) -> T = Refusal::fail,
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().bearer(idToken, device.getDeviceId()).apply(route).build()
        client.newCall(request).execute().use { resp -> answer(resp, onRefusal, read) }
    }

    /**
     * A device-signed call ([IndicApiSigning]). [read] takes a 200; any other
     * answer goes to [onRefusal], by default [failSigned].
     */
    private suspend fun <T> signed(
        idToken: String,
        call: SignedCall,
        onRefusal: (Refusal) -> T = ::failSigned,
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        signing.execute(idToken, call).use { resp -> answer(resp, onRefusal, read) }
    }

    private fun <T> answer(resp: Response, onRefusal: (Refusal) -> T, read: (Response) -> T): T =
        if (resp.code == HttpStatus.OK) read(resp) else onRefusal(Refusal.of(resp))

    private inline fun <reified T> decode(resp: Response): T = json.decodeFromString(resp.body.string())

    private inline fun <reified T> jsonBody(value: T): RequestBody =
        json.encodeToString(value).toRequestBody(IndicApiHttp.JSON_MEDIA)

    private inline fun <reified T> jsonBytes(value: T): ByteArray = json.encodeToString(value).toByteArray()

    /**
     * Maps a refused signed call to the most specific exception, matching the
     * parsed `detail` code ([ApiErrors]) rather than a substring of the body.
     */
    private fun failSigned(refusal: Refusal): Nothing {
        val detail = if (refusal.code == HttpStatus.CONFLICT) ApiErrors.detailOf(refusal.body) else null
        throw when {
            detail == null -> refusal.exception()
            ApiErrors.isCode(detail, ApiErrors.DEVICE_NOT_ACTIVE) -> DeviceNotActiveException(refusal.requestId)
            ApiErrors.isCode(detail, ApiErrors.DEVICE_IN_USE) -> DeviceInUseException(refusal.requestId)
            ApiErrors.isCode(detail, ApiErrors.DEVICE_CONFLICT) -> DeviceConflictException(refusal.requestId)
            else -> refusal.exception()
        }
    }

    /** The 403 mapping of the token-authenticated routes only an approved account reaches. */
    private fun approvedOnly(refusal: Refusal): Nothing =
        if (refusal.code == HttpStatus.FORBIDDEN) throw NotApprovedException() else refusal.fail()

    /** A signed GET's headers per attempt, for a download that sends its own requests. */
    private fun signedGet(idToken: String): (path: String) -> Headers =
        { path -> signing.headersFor(idToken, SignedCall("GET", path)) }

    // ---------------------------------------------------------------- identity

    /** GET /v1/me. Throws [NotApprovedException] for a PENDING/SUSPENDED user. */
    override suspend fun me(idToken: String): MeResponse = bearer(
        idToken,
        route = { url(endpoint("/v1/me")) },
        onRefusal = { refusal ->
            when (refusal.code) {
                HttpStatus.FORBIDDEN -> throw NotApprovedException()
                HttpStatus.CONFLICT -> throwForMeConflict(refusal.body, refusal.requestId)
                else -> refusal.fail()
            }
        },
    ) { decode(it) }

    /**
     * GET /v1/config — resolved product limits for this account.
     *
     * At launch the status check and the cloud reconcile both ask for it within
     * ~100 ms, so a call that arrives while one is running shares its answer
     * (docs/perf/request-volume.md, Pass 2). Both callers hold the same signed-in
     * user's token.
     */
    override suspend fun getConfig(idToken: String): AppConfigDto = configFlight.run {
        bearer(idToken, { url(endpoint("/v1/config")) }, ::approvedOnly) { decode(it) }
    }

    private val configFlight = SingleFlight<AppConfigDto>()

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
    override suspend fun exportAccount(idToken: String, dest: File): Unit =
        signed(idToken, SignedCall("GET", "/v1/me/export")) { resp ->
            // Promoted only after the whole body landed: a truncated transfer
            // must not look like a complete export.
            AtomicFiles.writeVia(dest) { part ->
                resp.body.byteStream().use { input ->
                    part.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
        }

    /**
     * POST /v1/devices/register. Registers this device's public key.
     * 201 → registered, 409 → another device already bound (needs admin rebind).
     * Requires an APPROVED user. The one call that sends no `X-Device-Id`
     * header: the id is in the body.
     */
    override suspend fun registerDevice(idToken: String) = withContext(Dispatchers.IO) {
        val body = DeviceRegisterRequest(
            deviceId = device.getDeviceId(),
            publicKeyPem = device.getPublicKeyPem(),
            model = android.os.Build.MODEL ?: "",
            osVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            appVersion = BuildConfig.VERSION_NAME,
        )
        val request = Request.Builder().url(endpoint("/v1/devices/register"))
            .header(IndicApiHttp.AUTHORIZATION, "Bearer $idToken")
            .post(jsonBody(body)).build()
        client.newCall(request).execute().use { resp ->
            when (resp.code) {
                HttpStatus.CREATED, HttpStatus.OK -> Unit
                HttpStatus.CONFLICT -> throw DeviceConflictException(IndicApiHttp.requestIdOf(resp))
                else -> throw IndicApiHttp.apiException(resp)
            }
        }
    }

    /**
     * POST /v1/licenses/activate — redeem a license key (individual or
     * institution; the backend tells them apart by the key itself).
     * Bearer + `X-Device-Id` like [registerDevice], **not** device-signed: the
     * backend route is `current_user` + a plain `X-Device-Id` header, no
     * challenge/nonce/signature. Every subsequent signed/bearer call still
     * re-validates the resulting lock (see [AppRemoteConfig] /
     * `revalidate_device_lock` in the backend) — this call only kicks it off.
     *
     * Throws [ApiException] with the backend's error code as `detail` for a
     * mismatch/revoked/exhausted key (`license_email_mismatch`,
     * `license_device_mismatch`, `license_revoked`, `license_seat_disabled`,
     * `license_seats_exhausted`, `license_already_redeemed`, `license_not_found`).
     */
    override suspend fun activateLicense(idToken: String, key: String): AppConfigDto = bearer(
        idToken,
        route = { url(endpoint("/v1/licenses/activate")).post(jsonBody(LicenseActivateRequest(key = key))) },
    ) { decode<LicenseActivateResponse>(it).config }

    /**
     * POST /v1/licenses/checkout — take or renew a floating seat.
     *
     * Calling it again IS the heartbeat: renewing does not consume a second
     * seat, so there is no separate route to get that wrong. Call it every
     * [AppConfigDto.leaseHeartbeatMinutes] while work is in progress.
     *
     * Throws [NoSeatAvailableException] when the pool is full. That is not a
     * problem with the account — the member is still eligible and still in
     * demo — so it is a distinct type rather than a generic [ApiException],
     * to stop a caller rendering "something went wrong" for it.
     */
    override suspend fun checkoutLease(idToken: String): AppConfigDto = seatCall(idToken, "checkout")

    /** POST /v1/licenses/release — give a floating seat back. Idempotent. */
    override suspend fun releaseLease(idToken: String): AppConfigDto = seatCall(idToken, "release")

    private suspend fun seatCall(idToken: String, action: String): AppConfigDto = bearer(
        idToken,
        route = { url(endpoint("/v1/licenses/$action")).post(EMPTY_JSON.toRequestBody(IndicApiHttp.JSON_MEDIA)) },
        onRefusal = { refusal ->
            if (refusal.code == HttpStatus.CONFLICT && refusal.hasCode(ApiErrors.NO_FLOATING_SEAT)) {
                throw NoSeatAvailableException()
            }
            refusal.fail()
        },
    ) { decode<LicenseActivateResponse>(it).config }

    // ---------------------------------------------------------- legal / consent

    /**
     * POST /v1/me/terms — record clickwrap acceptance of [version].
     *
     * Plain bearer auth (like [registerDevice]): the gate runs at registration,
     * before a device is registered and before an operator has approved the
     * account, so it can require neither attestation nor APPROVED status.
     * Throws [TermsVersionMismatchException] when the server no longer serves
     * [version] — the app is older than the published Terms.
     */
    override suspend fun acceptTerms(idToken: String, version: String): Unit = bearer(
        idToken,
        route = { url(endpoint("/v1/me/terms")).post(jsonBody(TermsAcceptanceBody(version))) },
        onRefusal = { refusal ->
            if (refusal.code == HttpStatus.CONFLICT) throw TermsVersionMismatchException(refusal.requestId)
            refusal.fail()
        },
    ) {}

    /** PUT /v1/me/consents — grant or withdraw the optional product-improvement consent. */
    override suspend fun setImprovementConsent(idToken: String, granted: Boolean): Unit =
        bearer(idToken, { url(endpoint("/v1/me/consents")).put(jsonBody(ConsentUpdateBody(granted))) }) {}

    // ----------------------------------------------------------- session/files

    /**
     * GET /v1/sessions — the caller's cloud analyses (for sync reconciliation).
     *
     * Follows `nextPageToken` until the account listing is complete so quota
     * reconciliation is not silently truncated by server page size. The quota
     * is the last page's.
     *
     * [verify] makes the backend also confirm each page's session blobs still
     * exist in Drive (catching artifacts deleted straight in Drive). It costs
     * Drive calls per page, so it's for explicit refreshes, not every resume.
     */
    override suspend fun listSessions(idToken: String, verify: Boolean): ListSessionsResponse {
        val pages = fetchAllPages(
            fetch = { token ->
                val query = buildString {
                    append("page_size=$SESSIONS_PAGE_SIZE")
                    if (verify) append("&verify=true")
                    if (token != null) append('&').append(pageTokenParam(token))
                }
                bearer(idToken, { url(endpoint("/v1/sessions?$query")) }, ::approvedOnly) {
                    decode<ListSessionsResponse>(it)
                }
            },
            pageOf = { it.page },
        )
        return ListSessionsResponse(sessions = pages.flatMap { it.sessions }, quota = pages.last().quota)
    }

    /** POST /v1/sessions (device-signed). Initiates a session + one resumable target per file. */
    override suspend fun createSession(idToken: String, request: SessionCreateRequest): SessionCreateResponse =
        signed(idToken, SignedCall("POST", "/v1/sessions", jsonBytes(request))) { decode(it) }

    /**
     * GET /v1/sessions/{sid}/uploads — what still needs uploading, every page
     * of it. A later page's query string is inside the signature.
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
    override suspend fun sessionUploads(idToken: String, sessionId: String): SessionUploadsResponse =
        fetchAllPages(
            fetch = { token ->
                val path = "/v1/sessions/$sessionId/uploads" + pageTokenQuery(token)
                signed(idToken, SignedCall("GET", path)) { decode<SessionUploadsResponse>(it) }
            },
            pageOf = { it.page },
        ).merged()

    /** POST /v1/files/{id}/complete (device-signed). */
    override suspend fun completeFile(idToken: String, fileId: String, request: FileCompleteRequest) =
        signed(idToken, SignedCall("POST", "/v1/files/$fileId/complete", jsonBytes(request))) {}

    /**
     * PUT /v1/sessions/{sid}/metadata — replace a backed-up analysis's
     * metadata.json with [metadataJson], for a change made after the backup
     * (ADR-013). Device-signed; any non-200 throws [ApiException].
     */
    override suspend fun replaceSessionMetadata(idToken: String, sessionId: String, metadataJson: String) =
        signed(idToken, SignedCall("PUT", "/v1/sessions/$sessionId/metadata", metadataJson.toByteArray())) {}

    // ----------------------------------------------------------------- restore

    /**
     * GET /v1/sessions/{sid}/files — the manifest for one cloud analysis, every
     * page of it: a restore from a truncated manifest would quietly miss files.
     */
    override suspend fun listSessionFiles(idToken: String, sessionId: String): SessionFilesResponse =
        fetchAllPages(
            fetch = { token ->
                bearer(idToken, { url(endpoint("/v1/sessions/$sessionId/files" + pageTokenQuery(token))) }) {
                    decode<SessionFilesResponse>(it)
                }
            },
            pageOf = { it.page },
        ).merged()

    /**
     * Fetch only `[rangeStart, rangeStart + length)` of an object.
     *
     * Restore uses this to read a legacy backup's central directory and then just the
     * prefix of entries it needs, instead of the whole archive.
     */
    override suspend fun downloadRange(idToken: String, fileId: String, dest: File, rangeStart: Long, length: Long) =
        drive.downloadFile(
            fileId,
            dest,
            endpoint(""), // the base; DriveTransfer appends its own paths
            expectedBytes = length,
            rangeStart = rangeStart,
            signedGetHeaders = signedGet(idToken),
        )

    /**
     * GET /v1/files/{id}/content — stream a file back from Drive into [dest]
     * ([DriveTransfer.downloadFile]). These bytes are proxied by the backend
     * (Drive has no anonymous download), so this is the one path where the
     * backend touches file content.
     *
     * Device-attested like writes: a fresh nonce and signature per window.
     * `Range` is an unsigned header (not part of the signed message) so resume
     * offsets can change without rehashing the body.
     */
    override suspend fun downloadFile(
        idToken: String,
        fileId: String,
        dest: File,
        expectedBytes: Long,
        onBytes: suspend (haveBytes: Long) -> Unit,
    ) = drive.downloadFile(
        fileId,
        dest,
        endpoint(""), // the base; DriveTransfer appends its own paths
        expectedBytes = expectedBytes,
        onBytes = onBytes,
        signedGetHeaders = signedGet(idToken),
    )

    // ------------------------------------------------------------------- admin

    /** GET /v1/admin/users?status=… (admin ID token; no device signature). */
    override suspend fun listUsers(idToken: String, status: String): List<AdminUserDto> = bearer(
        idToken,
        route = { url(endpoint(if (status.isBlank()) "/v1/admin/users" else "/v1/admin/users?status=$status")) },
        onRefusal = { refusal ->
            if (refusal.code == HttpStatus.FORBIDDEN) {
                throw ApiException(HttpStatus.FORBIDDEN, ApiErrors.NOT_ADMIN, refusal.requestId)
            }
            refusal.fail()
        },
    ) { decode<AdminUsersResponse>(it).users }

    /** POST /v1/admin/users/{uid}/{action} — device-attested (approve/revoke). */
    override suspend fun setUserStatus(idToken: String, uid: String, action: String) =
        signed(idToken, SignedCall("POST", "/v1/admin/users/$uid/$action"), Refusal::fail) {}

    // ----------------------------------------------------------------- erasure

    /**
     * DELETE /v1/me — erase the account and every analysis it owns from the
     * cloud. The caller must sign out immediately afterwards; any further
     * authenticated call would create a fresh, empty profile.
     *
     * No 404-is-fine shortcut here: this endpoint never legitimately 404s, so
     * a 404 means the route isn't reachable (e.g. not published on the API
     * Gateway). Treating that as success would wipe the local copy while
     * leaving every byte in the cloud.
     */
    override suspend fun deleteAccount(idToken: String) = signed(idToken, SignedCall("DELETE", "/v1/me")) {}

    /**
     * DELETE /v1/sessions/{id} — erase an analysis from the cloud: the Drive
     * folder (raw images, .dat, csv, report) and all Firestore metadata.
     * Permanent; there is no undo.
     *
     * A 404 is only "already erased" when OUR backend says so
     * (`session_not_found`). A bare 404 means the route isn't reachable —
     * accepting that as success would delete the local copy and orphan the
     * cloud data forever.
     */
    override suspend fun deleteSession(idToken: String, sessionId: String) = signed(
        idToken,
        SignedCall("DELETE", "/v1/sessions/$sessionId"),
        onRefusal = { refusal ->
            if (refusal.code != HttpStatus.NOT_FOUND || !refusal.hasCode(ApiErrors.SESSION_NOT_FOUND)) {
                failSigned(refusal)
            }
        },
    ) {}

    // ------------------------------------------------- direct-to-Drive uploads

    /**
     * Resumable upload of [file] to a Drive [uploadUrl], in [chunkSize] chunks
     * (multiple of 256 KiB). Resumes from the server offset on reconnect. Bytes
     * go straight to Drive — not through the backend.
     * Returns [DriveUpload.toPair]: (driveFileId, localMd5Hex), md5 always set for `:complete`.
     */
    override suspend fun uploadResumable(
        uploadUrl: String,
        file: File,
        chunkSize: Int,
        onBytes: (Long) -> Unit,
    ): Pair<String, String> = drive.uploadResumable(uploadUrl, file, chunkSize, onBytes).toPair()

    companion object {
        // One connection pool + dispatcher shared by every IndicApi instance.
        // The class is constructed per worker/repo (many times), and a fresh
        // OkHttpClient each time would throw away TLS session reuse and
        // keep-alive. downloadClient shares this pool via newBuilder().
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS) // large chunk PUTs to Drive
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            // Application interceptors, so each sees the logical call once
            // rather than once per redirect hop. Retry first, so a retried
            // request gets a freshly read App Check token rather than replaying
            // the one that may have expired while it waited. downloadClient
            // inherits both through newBuilder() below.
            .addInterceptor(RetryOnTransient())
            .addInterceptor(AppCheckHeader())
            // Which app's device binding a call is for (ADR-010).
            .addInterceptor(AppIdHeader())
            .addInterceptor(ClientNonce.ServerDateObserver(ApiHost.configured))
            .apply { certificatePins()?.let(::certificatePinner) }
            .build()

        /** `INDIC_API_CERT_PINS` for the backend host, or null when there are none. */
        private fun certificatePins(): CertificatePinner? {
            val pins = BuildConfig.INDIC_API_CERT_PINS.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val host = ApiHost.configured
            if (pins.isEmpty() || host.isEmpty()) return null
            return CertificatePinner.Builder().apply { pins.forEach { add(host, it) } }.build()
        }

        /** Longer read idle for large Session.zip / legacy restores through the proxy. */
        private val downloadClient = client.newBuilder()
            .readTimeout(DOWNLOAD_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        @Volatile
        private var instance: IndicApi? = null

        /** Maps a 409 from `GET /v1/me` to the most specific device-binding exception. */
        internal fun throwForMeConflict(body: String, requestId: String?): Nothing {
            val err: Throwable = when {
                ApiErrors.hasCode(body, ApiErrors.DEVICE_IN_USE) -> DeviceInUseException(requestId)
                ApiErrors.hasCode(body, ApiErrors.DEVICE_CONFLICT) -> DeviceConflictException(requestId)
                else -> ApiException(HttpStatus.CONFLICT, body, requestId)
            }
            throw err
        }

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
