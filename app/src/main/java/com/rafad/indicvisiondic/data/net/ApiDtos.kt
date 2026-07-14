package com.rafad.indicvisiondic.data.net

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the inDIC GCP backend (FastAPI on Cloud Run). Field names match
 * the JSON contract in backend/app/models.py exactly. See
 * docs/CLOUD_ARCHITECTURE_GCP.md for the full API.
 */

@Serializable
data class MeResponse(
    val uid: String,
    val email: String? = null,
    val role: String? = null,
    val access_status: String,
)

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
    val uploads: List<UploadTargetDto>,
)

@Serializable
data class FileCompleteRequest(
    val sessionId: String,
    val driveFileId: String,
    val bytes: Long,
    val md5: String? = null,
)
