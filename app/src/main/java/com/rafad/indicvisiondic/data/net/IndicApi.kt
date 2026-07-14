package com.rafad.indicvisiondic.data.net

import android.content.Context
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.data.DeviceKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Client for the inDIC GCP backend (Cloud Run / FastAPI).
 *
 * Every mutating call carries a Google **ID token** (user proof) plus a
 * challenge-response **device signature** (device proof). File bytes go
 * **directly to Google Drive** via the resumable session URI returned by the
 * broker — they never pass through this client's backend host.
 */
class IndicApi(context: Context) {

    private val appContext = context.applicationContext
    private val device = DeviceKeyManager(appContext)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val base = BuildConfig.INDIC_API_BASE_URL.trimEnd('/')
    val enabled: Boolean get() = base.isNotBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS) // large chunk PUTs to Drive
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val octet = "application/octet-stream".toMediaType()

    class ApiException(val code: Int, val detail: String) : IOException("HTTP $code: $detail")
    class NotApprovedException : IOException("not_approved")
    class DeviceConflictException : IOException("device_conflict")

    // ---------------------------------------------------------------- identity

    /** GET /v1/me. Throws [NotApprovedException] for a PENDING/SUSPENDED user. */
    suspend fun me(idToken: String): MeResponse = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/me")
            .header("Authorization", "Bearer $idToken").get().build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> json.decodeFromString(resp.body!!.string())
                403 -> throw NotApprovedException()
                else -> throw ApiException(resp.code, resp.bodyText())
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
                201, 200 -> Unit
                409 -> throw DeviceConflictException()
                else -> throw ApiException(resp.code, resp.bodyText())
            }
        }
    }

    // ----------------------------------------------------------- session/files

    /** POST /v1/sessions (device-signed). Initiates a session + one resumable target per file. */
    suspend fun createSession(idToken: String, request: SessionCreateRequest): SessionCreateResponse =
        withContext(Dispatchers.IO) {
            val bodyBytes = json.encodeToString(request).toByteArray()
            val resp = signedPost(idToken, "/v1/sessions", bodyBytes)
            resp.use {
                if (it.code == 200) json.decodeFromString(it.body!!.string())
                else throw ApiException(it.code, it.bodyText())
            }
        }

    /** POST /v1/files/{id}/complete (device-signed). */
    suspend fun completeFile(idToken: String, fileId: String, request: FileCompleteRequest) =
        withContext(Dispatchers.IO) {
            val bodyBytes = json.encodeToString(request).toByteArray()
            val resp = signedPost(idToken, "/v1/files/$fileId/complete", bodyBytes)
            resp.use { if (it.code != 200) throw ApiException(it.code, it.bodyText()) }
        }

    // ------------------------------------------------------- device-signed POST

    private fun signedPost(idToken: String, path: String, bodyBytes: ByteArray): Response {
        val nonce = fetchChallenge(idToken)
        val headers = signedHeaders(idToken, "POST", path, bodyBytes, nonce)
        val req = Request.Builder().url("$base$path")
            .headers(headers)
            .post(bodyBytes.toRequestBody(jsonMedia)).build()
        return client.newCall(req).execute()
    }

    /** POST /v1/challenge → single-use nonce bound to (uid, deviceId). */
    private fun fetchChallenge(idToken: String): String {
        val req = Request.Builder().url("$base/v1/challenge")
            .header("Authorization", "Bearer $idToken")
            .header("X-Device-Id", device.getDeviceId())
            .post(ByteArray(0).toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != 200) throw ApiException(resp.code, resp.bodyText())
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
    suspend fun uploadResumable(uploadUrl: String, file: java.io.File, chunkSize: Int): Pair<String, String?> =
        withContext(Dispatchers.IO) {
            val total = file.length()
            var offset = queryResumeOffset(uploadUrl, total)
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(chunkSize)
                while (offset < total) {
                    raf.seek(offset)
                    val n = raf.read(buf, 0, minOf(chunkSize.toLong(), total - offset).toInt())
                    if (n <= 0) throw IOException("unexpected EOF at $offset/$total")
                    val end = offset + n - 1
                    val req = Request.Builder().url(uploadUrl)
                        .header("Content-Range", "bytes $offset-$end/$total")
                        .put(buf.toRequestBody(octet, 0, n)).build()
                    client.newCall(req).execute().use { resp ->
                        when (resp.code) {
                            308 -> offset = end + 1 // Resume Incomplete
                            200, 201 -> {
                                val bodyStr = resp.body?.string().orEmpty()
                                return@withContext parseDriveResult(bodyStr)
                            }
                            else -> throw ApiException(resp.code, resp.body?.string().orEmpty())
                        }
                    }
                }
            }
            throw IOException("upload finished without a final Drive response")
        }

    /** Ask Drive how many bytes it already has: PUT bytes-* /total with an empty body. */
    private fun queryResumeOffset(uploadUrl: String, total: Long): Long {
        val req = Request.Builder().url(uploadUrl)
            .header("Content-Range", "bytes */$total")
            .put(ByteArray(0).toRequestBody(octet)).build()
        client.newCall(req).execute().use { resp ->
            return when (resp.code) {
                308 -> resp.header("Range")?.substringAfterLast('-')?.toLongOrNull()?.plus(1) ?: 0L
                200, 201 -> total // already complete
                else -> 0L // start fresh (e.g. 404 expired handled by caller retry)
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
    } catch (e: Exception) {
        Timber.w(e, "reading error body"); ""
    }
}
