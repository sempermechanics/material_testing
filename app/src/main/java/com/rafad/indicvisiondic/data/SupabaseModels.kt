package com.rafad.indicvisiondic.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Payload for Supabase `analysis_sessions` table insert. */
@Serializable
data class AnalysisSessionInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("user_email") val userEmail: String,
    @SerialName("specimen_identifier") val specimenIdentifier: String,
    @SerialName("points_converged") val pointsConverged: Int,
    @SerialName("avg_iterations") val avgIterations: Float,
    @SerialName("execution_time_ms") val executionTimeMs: Int,
)

@Serializable
data class AnalysisSessionResponse(
    @SerialName("session_id") val sessionId: String,
)
