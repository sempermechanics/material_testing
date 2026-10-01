package com.indicvision.semper.data.net

import com.indicvision.semper.BuildConfig

/**
 * The Semper backend's host name: what the backend-only interceptors and the
 * certificate pins are scoped to. Drive shares the same OkHttp client and must
 * not get the backend's headers.
 */
internal object ApiHost {

    /** The host of [BuildConfig.INDIC_API_BASE_URL], or "" when no backend is configured. */
    val configured: String = of(BuildConfig.INDIC_API_BASE_URL)

    /** The host of an `https://host/…` [baseUrl]; "" for "". */
    fun of(baseUrl: String): String = baseUrl.trimEnd('/').removePrefix("https://").substringBefore('/')
}
