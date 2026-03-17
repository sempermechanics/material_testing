package com.rafad.indicvisiondic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    @SerialName("id") val id: String,
    @SerialName("email") val email: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("public_key") val publicKey: String,
    @SerialName("is_approved") val isApproved: Boolean = false
)