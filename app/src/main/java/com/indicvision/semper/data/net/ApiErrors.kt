package com.indicvision.semper.data.net

import org.json.JSONException
import org.json.JSONObject

/**
 * The error codes the backend puts in FastAPI's `{"detail": "<code>"}` body.
 *
 * Mirrors `backend/app/errors.py` — the two lists are pinned against each other
 * by `backend/tests/test_error_codes.py`, so renaming a code on one side fails
 * the other side's CI instead of silently changing what a 409 means here.
 *
 * Match with [hasCode] rather than `String.contains`: a substring test also
 * matches a code quoted inside a longer human message, which is how a
 * "device conflict" reading of an unrelated 409 gets into a bug report.
 */
object ApiErrors {

    /** The account exists but is not APPROVED (403). */
    const val NOT_APPROVED = "not_approved"

    /** This account is already bound to a different device (409). */
    const val DEVICE_CONFLICT = "device_conflict"

    /** This device is already bound to a different account (409). */
    const val DEVICE_IN_USE = "device_in_use"

    /** No ACTIVE device record for this caller — re-register and retry (409). */
    const val DEVICE_NOT_ACTIVE = "device_not_active"

    /** Single-use nonce missing, expired or replayed (401). */
    const val NONCE_INVALID_OR_REPLAYED = "nonce_invalid_or_replayed"

    /** Device signature did not verify (401). */
    const val BAD_SIGNATURE = "bad_signature"

    /** No such cloud session for this account (404). */
    const val SESSION_NOT_FOUND = "session_not_found"

    /** No such cloud file for this account (404). */
    const val FILE_NOT_FOUND = "file_not_found"

    /** Per-instance token bucket or gateway quota rejected the call (429). */
    const val RATE_LIMITED = "rate_limited"

    /** The account is at its stored-analysis cap (409). Carries a count tail. */
    const val SESSION_QUOTA_EXCEEDED = "session_quota_exceeded"

    /**
     * The `detail` of an error [body], or the trimmed body when it is not the
     * usual JSON envelope — API Gateway and Cloud Run kills answer with plain
     * text or nothing at all, and those must not read as a code.
     */
    fun detailOf(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return try {
            JSONObject(trimmed).optString("detail", trimmed)
        } catch (_: JSONException) {
            trimmed
        }
    }

    /**
     * Whether [body] reports exactly [code].
     *
     * A trailing `: …` counts: `session_quota_exceeded` arrives as
     * "session_quota_exceeded: 5/5 analyses stored." so the caller can show the
     * numbers, and the code in front of the colon is still the whole meaning.
     */
    fun hasCode(body: String, code: String): Boolean {
        val detail = detailOf(body)
        return detail == code || detail.startsWith("$code:")
    }
}
