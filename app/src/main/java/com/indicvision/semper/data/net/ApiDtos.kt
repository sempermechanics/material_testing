// A couple of default numeric literals (limits/versions) read clearest inline.
@file:Suppress("MagicNumber")

package com.indicvision.semper.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the Semper GCP backend (FastAPI on Cloud Run). Field names match
 * the JSON contract in backend/app/models.py exactly. See
 * docs/backend/CLOUD_ARCHITECTURE_GCP.md for the full API.
 */

@Serializable
data class MeResponse(
    val uid: String,
    val email: String? = null,
    val role: String? = null,
    @SerialName("access_status") val accessStatus: String,
)

/** Resolved product limits from GET /v1/config (per-user override → fleet default). */
@Serializable
data class AppConfigDto(
    val maxSessions: Int = 0,
    val maxFilesPerSession: Int = 0,
    val maxFrames: Int = 0,
    /** Version gate for the `.dat` archive codec — see [AppRemoteConfig] and
     * [com.indicvision.semper.data.SessionZip]'s class doc. Missing on an older
     * backend deploy this app talks to → false (fail closed, matches the
     * default already used for every field here). */
    val datCodecEncodingEnabled: Boolean = false,
    /** `demo` or `licensed`. Empty on a backend deploy predating the
     * plan→mode rename; [AppRemoteConfig] falls back to [plan] in that case,
     * so a blank here is "not told", not "demo". */
    val mode: String = "",
    /** Pre-rename spelling of [mode]: `demo` or `professional`. Still sent by
     * the backend alongside `mode` for builds that predate the rename, and
     * still read here as the fallback when `mode` is absent. Missing from both
     * → demo (fail closed). */
    val plan: String = "demo",
    val cloudBackupEnabled: Boolean = false,
    val shareEnabled: Boolean = false,
    val licensePrefix: String = "",
    /** `""`, `"individual"`, or `"institution"` — display/support metadata
     * only, not a gating input. Entitlements (cloudBackupEnabled/shareEnabled/
     * mode) are identical for an individual and an institution seat; this
     * field exists so Settings can show e.g. "Activated via university.edu"
     * and so support tickets can tell the two shapes apart. A backend
     * predating the rename sends `"campus"`; [LicenseEntitlements] normalises
     * it. */
    val licenseKind: String = "",
    /** `perpetual` or `timed`. Empty on a backend predating duration; the
     * cache infers it from whether an expiry arrived. */
    val licenseDuration: String = "",
    /** ISO-8601 instant the license stops, or null when perpetual. Advisory —
     * the app warns from it but never gates on it, because a cached date can
     * be arbitrarily stale and a renewal may have landed while offline. */
    val licenseExpiresAt: String? = null,
    /** ISO-8601 instant entitlement actually ends: expiry plus the grace
     * window. Null when perpetual. */
    val licenseGraceEndsAt: String? = null,
    /** Past expiry but still fully entitled. Nothing is withdrawn — this only
     * says a renewal is overdue. */
    val inGrace: Boolean = false,
)

@Serializable
data class LicenseActivateRequest(val key: String)

@Serializable
data class LicenseActivateResponse(val config: AppConfigDto)

@Serializable
data class DeviceRegisterRequest(
    val deviceId: String,
    val publicKeyPem: String,
    val model: String = "",
    val osVersion: String = "",
    val appVersion: String = "",
)

@Serializable
data class ChallengeResponse(val nonce: String)

@Serializable
data class FileSpecDto(
    val name: String,
    val role: String, // "raw" | "processed" | "reports" | "metadata"
    val bytes: Long,
    val sha256: String,
)

@Serializable
data class SessionCreateRequest(
    val specimen: String,
    val files: List<FileSpecDto>,
    val metrics: Map<String, Float> = emptyMap(),
    /** The app's local analysis id, so cloud sessions can be matched back to it. */
    val localSessionId: String = "",
)

@Serializable
data class CloudSessionDto(
    val sessionId: String,
    val localSessionId: String = "",
    val specimen: String? = null,
    val status: String? = null,
    val fileCount: Int = 0,
    val completedCount: Int = 0,
    val totalBytes: Long = 0,
    val driveFolderId: String? = null,
)

@Serializable
data class QuotaDto(val used: Int = 0, val max: Int = 0)

@Serializable
data class ListSessionsResponse(
    val sessions: List<CloudSessionDto> = emptyList(),
    val quota: QuotaDto = QuotaDto(),
    val page: PageDto? = null,
)

@Serializable
data class PageDto(
    val size: Int = 0,
    val count: Int = 0,
    val nextPageToken: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class UploadTargetDto(
    val fileId: String,
    val uploadUrl: String,
    val chunkSize: Int,
)

@Serializable
data class SessionCreateResponse(
    val sessionId: String,
    /**
     * PROVISIONING while the backend opens the Drive resumable sessions in a
     * Cloud Task; UPLOADING once [uploads] is populated. Nullable so an older
     * backend that always provisions inline still parses.
     */
    val status: String? = null,
    val uploads: List<UploadTargetDto> = emptyList(),
)

@Serializable
data class FileCompleteRequest(
    val sessionId: String,
    val driveFileId: String,
    val bytes: Long,
    val md5: String? = null,
)

/** A file still awaiting bytes, with the resumable URI to continue into. */
@Serializable
data class PendingUploadDto(
    val fileId: String,
    val uploadUrl: String,
    val chunkSize: Int = 8 * 1024 * 1024,
    val name: String = "",
    val role: String = "",
    val sizeBytes: Long = 0,
)

@Serializable
data class SessionUploadsResponse(
    val sessionId: String,
    val status: String? = null,
    /** Set when status is PROVISION_FAILED — why the targets were never opened. */
    val provisionError: String? = null,
    val uploads: List<PendingUploadDto> = emptyList(),
)

@Serializable
data class CloudFileDto(
    val fileId: String,
    val name: String = "",
    val role: String = "",
    val sizeBytes: Long = 0,
    val sha256: String? = null,
    val status: String? = null,
)

@Serializable
data class SessionFilesResponse(
    val sessionId: String,
    val localSessionId: String = "",
    val specimen: String? = null,
    val status: String? = null,
    val files: List<CloudFileDto> = emptyList(),
)

@Serializable
data class AdminUserDto(
    val uid: String,
    val email: String? = null,
    val displayName: String? = null,
    val role: String? = null,
    @SerialName("access_status") val accessStatus: String? = null,
    val activeDeviceId: String? = null,
)

@Serializable
data class AdminUsersResponse(val users: List<AdminUserDto>)
