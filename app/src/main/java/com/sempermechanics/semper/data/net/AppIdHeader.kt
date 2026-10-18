package com.sempermechanics.semper.data.net

import com.sempermechanics.semper.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/** Header the backend reads (`deps.request_app`, backend `app/apps.py`). */
private const val APP_ID_HEADER = "X-App-Id"

/** This build's versionCode, for the backend's version gate and its logs (A6.1). */
private const val APP_VERSION_HEADER = "X-App-Version"

/**
 * Names this app to the Semper backend, by its `applicationId`.
 *
 * Semper and Material Testing share the backend and its accounts, but Android
 * gives each app on a phone its own `ANDROID_ID`, so the backend sees two
 * devices. It binds one phone per app (ADR-010), and this header is how it
 * knows which app's binding a request is for. A request without it is read as
 * Semper, which is what every build before this header was.
 *
 * It also sends `X-App-Version` (the versionCode), so the backend can tell an
 * unsupported build apart and its `minSupportedVersionCode` can be checked
 * against the fleet.
 *
 * Scoped to the API host ([ApiHostInterceptor]): Drive shares this client and
 * has no use for it.
 */
class AppIdHeader(
    apiHost: String = ApiHost.configured,
    private val appId: String = BuildConfig.APPLICATION_ID,
    private val versionCode: Int = BuildConfig.VERSION_CODE,
) : ApiHostInterceptor(apiHost) {

    override fun interceptApiCall(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder().header(APP_VERSION_HEADER, versionCode.toString())
        if (appId.isNotEmpty()) request.header(APP_ID_HEADER, appId)
        return chain.proceed(request.build())
    }
}
