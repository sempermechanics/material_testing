package com.indicvision.semper.data.net

import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/** Shared OkHttp response helpers for [IndicApi]. */
internal object IndicApiHttp {

    /** Defensive cap: the header is attacker-influencable in principle. */
    private const val MAX_REQUEST_ID_LEN = 64

    /**
     * The backend's correlation id for this response.
     *
     * `backend/app/main.py` stamps `X-Request-Id` on every response and logs the
     * same value as `requestId` on the structured access line, so quoting it in
     * a failure reason turns "the backup failed" into one greppable log entry.
     * It is an opaque 12-hex token — no account, device or session identity —
     * which is why it is safe to show in the UI and in Crashlytics breadcrumbs.
     */
    fun requestIdOf(resp: Response): String? =
        resp.header("X-Request-Id")?.takeIf { it.isNotBlank() }?.take(MAX_REQUEST_ID_LEN)

    /**
     * [text] with the correlation id appended, when there is one. One
     * implementation so a reason, an exception message and a log line cannot
     * print the reference three different ways. See [requestIdOf].
     */
    fun withRef(text: String, requestId: String?): String =
        if (requestId.isNullOrBlank()) text else "$text (ref: $requestId)"

    /**
     * The generic failure for a Semper-backend call: status, body and the
     * correlation id that joins it to the backend access log.
     *
     * Reads the body, so the caller must not have consumed it.
     */
    fun apiException(resp: Response): IndicApi.ApiException =
        IndicApi.ApiException(resp.code, bodyText(resp), requestIdOf(resp))

    /**
     * [base] + [path] for a backend call. With no backend configured the URL
     * would be the bare [path], which OkHttp rejects with an unchecked
     * IllegalArgumentException that killed the process wherever a caller only
     * expected [IOException]. [IndicApi.CloudNotConfiguredException] is an
     * [IOException], so every caller treats it like offline (TD-90).
     */
    fun endpoint(base: String, path: String): String =
        if (base.isBlank()) throw IndicApi.CloudNotConfiguredException() else base + path

    fun bodyText(resp: Response): String = try {
        resp.body.string()
    } catch (e: IOException) {
        Timber.w(e, "reading error body")
        ""
    }

    fun parseDriveResult(body: String): Pair<String, String?> {
        val obj = org.json.JSONObject(body)
        val id = obj.optString("id")
        val md5 = if (obj.has("md5Checksum")) obj.optString("md5Checksum") else null
        return id to md5
    }
}
