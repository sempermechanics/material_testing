package com.indicvision.semper.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * How [IndicApi] sends a call and reads the answer: token-authenticated
 * ([bearer]) or device-signed ([signed]), on the IO dispatcher.
 *
 * [deviceId] and [sign] are this device's key (`DeviceKeyManager`), as
 * functions so the AndroidKeyStore is only touched once a call is made.
 */
internal class IndicApiCalls(
    private val client: OkHttpClient,
    endpoint: (path: String) -> String,
    private val deviceId: () -> String,
    sign: (message: ByteArray) -> String,
) {
    private val signing = IndicApiSigning(client, endpoint, deviceId, sign)

    /**
     * A token-authenticated call: the ID token and this device's id, then
     * [route]'s URL, method and body. [read] takes a 200; any other answer goes
     * to [onRefusal], which throws the route's most specific exception (a plain
     * [IndicApi.ApiException] unless it says otherwise) or accepts the answer.
     */
    suspend fun <T> bearer(
        idToken: String,
        route: Request.Builder.() -> Unit,
        onRefusal: (Refusal) -> T = Refusal::fail,
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().bearer(idToken, deviceId()).apply(route).build()
        client.newCall(request).execute().use { resp -> answer(resp, onRefusal, read) }
    }

    /**
     * A device-signed call ([IndicApiSigning]). [read] takes a 200; any other
     * answer goes to [onRefusal], by default [failSigned].
     */
    suspend fun <T> signed(
        idToken: String,
        call: SignedCall,
        onRefusal: (Refusal) -> T = Refusal::failSigned,
        read: (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        signing.execute(idToken, call).use { resp -> answer(resp, onRefusal, read) }
    }

    /** A signed GET's headers per attempt, for a download that sends its own requests. */
    fun signedGet(idToken: String): (path: String) -> Headers =
        { path -> signing.headersFor(idToken, SignedCall("GET", path)) }

    private fun <T> answer(resp: Response, onRefusal: (Refusal) -> T, read: (Response) -> T): T =
        if (resp.code == HttpStatus.OK) read(resp) else onRefusal(Refusal.of(resp))
}

/**
 * Maps a refused signed call to the most specific exception, matching the
 * parsed `detail` code ([ApiErrors]) rather than a substring of the body.
 */
internal fun Refusal.failSigned(): Nothing {
    val detail = if (code == HttpStatus.CONFLICT) ApiErrors.detailOf(body) else null
    throw when {
        detail == null -> exception()
        ApiErrors.isCode(detail, ApiErrors.DEVICE_NOT_ACTIVE) -> IndicApi.DeviceNotActiveException(requestId)
        ApiErrors.isCode(detail, ApiErrors.DEVICE_IN_USE) -> IndicApi.DeviceInUseException(requestId)
        ApiErrors.isCode(detail, ApiErrors.DEVICE_CONFLICT) -> IndicApi.DeviceConflictException(requestId)
        else -> exception()
    }
}

/** The 403 mapping of the token-authenticated routes only an approved account reaches. */
internal fun Refusal.failApprovedOnly(): Nothing =
    if (code == HttpStatus.FORBIDDEN) throw IndicApi.NotApprovedException() else fail()
