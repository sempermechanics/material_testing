package com.indicvision.semper.data.net

import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/** Shared OkHttp response helpers for [IndicApi]. */
internal object IndicApiHttp {

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
