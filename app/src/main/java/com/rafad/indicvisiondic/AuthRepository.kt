package com.rafad.indicvisiondic

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import android.util.Log

// Used for fetching data during LOGIN
@Serializable
data class AuthProfile(
    @SerialName("access_status") val accessStatus: String,
    @SerialName("device_fingerprint") val deviceFingerprint: String? = null,
    @SerialName("hardware_public_key") val hardwarePublicKey: String? = null // 🚀 NEW: We need to read the current key!
)
// Used ONLY for injecting hardware keys after registration
@Serializable
data class HardwareKeysUpdate(
    @SerialName("device_fingerprint") val deviceFingerprint: String,
    @SerialName("hardware_public_key") val hardwarePublicKey: String
)
// Used for sending data during REGISTRATION
@Serializable
data class UserProfileInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("email_address") val emailAddress: String,
    @SerialName("device_fingerprint") val deviceFingerprint: String,
    @SerialName("hardware_public_key") val hardwarePublicKey: String,
    @SerialName("access_status") val accessStatus: String = "PENDING"
)
class AuthRepository {

    private val supabase = SupabaseManager.client

    // 1. REGISTRATION (Atomic Version)
    // 1. REGISTRATION (Atomic Version)
    suspend fun registerUser(
        emailInput: String,
        passwordInput: String,
        deviceId: String,
        publicKey: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // We send everything in ONE single request
                supabase.auth.signUpWith(Email) {
                    email = emailInput
                    password = passwordInput
                    // Inject hardware keys directly into the Supabase user metadata
                    data = buildJsonObject {
                        put("device_fingerprint", deviceId)
                        put("hardware_public_key", publicKey)
                    }
                }

                try { supabase.auth.signOut() } catch (e: Exception) { /* Ignore */ }

                Result.success("Registration successful! Account is PENDING admin approval.")
            } catch (e: Exception) {
                val errorMsg = e.message ?: ""
                // 🚀 Clean Network Error Interceptor
                if (errorMsg.contains("UnknownHostException", ignoreCase = true) ||
                    errorMsg.contains("resolve host", ignoreCase = true) ||
                    errorMsg.contains("Failed to connect", ignoreCase = true)) {
                    Result.failure(Exception("No internet connection. Please connect to a network to register."))
                } else {
                    Result.failure(Exception("Registration failed: $errorMsg"))
                }
            }
        }
    }

    // 2. LOGIN & VAULT CHECK
    suspend fun loginUser(emailInput: String, passwordInput: String, currentDeviceId: String, currentPublicKey: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("inDIC_Auth_Diag", "========================================")
                Log.d("inDIC_Auth_Diag", "🔐 INITIATING SECURE LOGIN")
                Log.d("inDIC_Auth_Diag", "-> Local Device ID presented by phone: $currentDeviceId")

                supabase.auth.signInWith(Email) {
                    email = emailInput
                    password = passwordInput
                }

                val userId = supabase.auth.currentUserOrNull()?.id
                    ?: throw Exception("Login failed: User session not established.")

                val profile = supabase.postgrest["auth_profiles"]
                    .select { filter { eq("user_id", userId) } }
                    .decodeSingle<AuthProfile>()

                Log.d("inDIC_Auth_Diag", "-> Supabase Vault Device ID: ${profile.deviceFingerprint}")

                when (profile.accessStatus) {
                    "APPROVED" -> { Log.d("inDIC_Auth_Diag", "-> Status: APPROVED") }
                    "PENDING" -> return@withContext Result.failure(Exception("Account is pending Admin approval."))
                    "REVOKED" -> {
                        supabase.auth.signOut()
                        return@withContext Result.failure(Exception("Account access has been revoked."))
                    }
                    else -> {
                        supabase.auth.signOut()
                        return@withContext Result.failure(Exception("Unknown account status."))
                    }
                }

                // 🚀 THE HARDWARE LOCK GATE
                if (profile.deviceFingerprint != null && profile.deviceFingerprint != currentDeviceId) {
                    Log.e("inDIC_Auth_Diag", "❌ HARDWARE MISMATCH DETECTED!")
                    Log.e("inDIC_Auth_Diag", "Expected: ${profile.deviceFingerprint}")
                    Log.e("inDIC_Auth_Diag", "Received: $currentDeviceId")
                    supabase.auth.signOut()
                    return@withContext Result.failure(Exception("UNAUTHORIZED HARDWARE: Account locked to a different device."))
                }

                // 🚀 THE SELF-HEALING KEYSTORE
                if (profile.hardwarePublicKey != currentPublicKey) {
                    Log.d("inDIC_Auth_Diag", "🩹 KeyStore wipe detected. Healing public key in database...")
                    supabase.postgrest["auth_profiles"].update(
                        mapOf("hardware_public_key" to currentPublicKey)
                    ) {
                        filter { eq("user_id", userId) }
                    }
                }

                Log.d("inDIC_Auth_Diag", "✅ LOGIN SUCCESSFUL!")
                Log.d("inDIC_Auth_Diag", "========================================")
                Result.success("Secure Login Successful!")

            } catch (e: Exception) {
                val errorMsg = e.message ?: ""

                // 🚀 Clean Network Error Interceptor
                if (errorMsg.contains("UnknownHostException", ignoreCase = true) ||
                    errorMsg.contains("resolve host", ignoreCase = true) ||
                    errorMsg.contains("Failed to connect", ignoreCase = true)) {
                    Result.failure(Exception("No internet connection. Please connect to Wi-Fi or cellular data to log in."))
                } else {
                    try { supabase.auth.signOut() } catch (ex: Exception) {}

                    // If it isn't a network error, it's usually a bad password.
                    // We return a clean message instead of Supabase's JSON error strings.
                    if (errorMsg.contains("Invalid login credentials", ignoreCase = true)) {
                        Result.failure(Exception("Invalid Email or Password."))
                    } else {
                        Result.failure(Exception(errorMsg))
                    }
                }
            }
        }
    }

    // 3. FORGOT PASSWORD
    suspend fun resetPassword(emailInput: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                supabase.auth.resetPasswordForEmail(emailInput)
                Result.success("Password reset link sent to your email.")
            } catch (e: Exception) {
                Result.failure(Exception(e.message ?: "Failed to send reset email."))
            }
        }
    }
    // Fetches the user's current status from the database
    suspend fun checkUserAccessStatus(currentDeviceId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val userId = supabase.auth.currentUserOrNull()?.id
                    ?: return@withContext Result.failure(Exception("No active session."))

                val profile = supabase.postgrest["auth_profiles"]
                    .select { filter { eq("user_id", userId) } }
                    .decodeSingle<AuthProfile>()

                if (profile.deviceFingerprint != currentDeviceId && profile.deviceFingerprint != null) {
                    supabase.auth.signOut()
                    return@withContext Result.failure(Exception("UNAUTHORIZED HARDWARE"))
                }

                when (profile.accessStatus) {
                    "APPROVED" -> Result.success("APPROVED")
                    "PENDING" -> Result.success("PENDING")
                    "REVOKED" -> {
                        supabase.auth.signOut()
                        Result.failure(Exception("Account access has been revoked."))
                    }
                    else -> Result.failure(Exception("Unknown status."))
                }
            } catch (e: io.github.jan.supabase.exceptions.HttpRequestException) {
                // 🚀 THE MAGIC BULLET: If we have no internet, we return a special OFFLINE code!
                Result.success("OFFLINE_CACHE_APPROVED")
            } catch (e: Exception) {
                Result.failure(Exception("Could not verify account status."))
            }
        }
    }
}