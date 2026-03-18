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

// Used for fetching data during LOGIN
@Serializable
data class AuthProfile(
    @SerialName("access_status") val accessStatus: String,
    @SerialName("device_fingerprint") val deviceFingerprint: String? = null
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

                // We no longer care if the session is null!
                // The Postgres Trigger just did all the heavy lifting automatically.

                // Force a sign out just to ensure a clean slate, but wrap it in a try-catch
                // so it doesn't crash if they weren't logged in anyway.
                try { supabase.auth.signOut() } catch (e: Exception) { /* Ignore */ }

                Result.success("Registration successful! Account is PENDING admin approval.")
            } catch (e: Exception) {
                Result.failure(Exception(e.message ?: "Registration failed. Check your network."))
            }
        }
    }

    // 2. LOGIN & VAULT CHECK
    suspend fun loginUser(emailInput: String, passwordInput: String, currentDeviceId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                supabase.auth.signInWith(Email) {
                    email = emailInput
                    password = passwordInput
                }

                val userId = supabase.auth.currentUserOrNull()?.id
                    ?: throw Exception("Login failed: User session not established.")

                val profile = supabase.postgrest["auth_profiles"]
                    .select { filter { eq("user_id", userId) } }
                    .decodeSingle<AuthProfile>()

                when (profile.accessStatus) {
                    "APPROVED" -> { /* Proceed */ }
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

                if (profile.deviceFingerprint != null && profile.deviceFingerprint != currentDeviceId) {
                    supabase.auth.signOut()
                    return@withContext Result.failure(Exception("UNAUTHORIZED HARDWARE: Account locked to a different device."))
                }

                Result.success("Secure Login Successful!")

            } catch (e: io.github.jan.supabase.exceptions.HttpRequestException) {
                // 🚀 CATCH NO INTERNET DURING LOGIN
                Result.failure(Exception("No Internet Connection. Please connect to Wi-Fi to log in."))
            } catch (e: Exception) {
                // 🚀 Catch all other errors without crashing
                try { supabase.auth.signOut() } catch (ex: Exception) {}
                Result.failure(Exception(e.message ?: "Invalid Email or Password."))
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