package com.indicvision.semper.data.net

import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/** Shared OkHttp response helpers for [IndicApi]. */
internal object IndicApiHttp {

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

    /** Defensive cap: the header is attacker-influencable in principle. */
    private const val MAX_REQUEST_ID_LEN = 64
}
