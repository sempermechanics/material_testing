package com.rafad.indicvisiondic

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// This data class helps us parse the row from your auth_profiles table
@Serializable
data class AuthProfile(
    val access_status: String
)

class AuthRepository {

    private val supabase = SupabaseManager.client

    // 1. REGISTRATION
    suspend fun registerUser(emailInput: String, passwordInput: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                supabase.auth.signUpWith(Email) {
                    email = emailInput
                    password = passwordInput
                }
                Result.success("Registration successful! Account is PENDING admin approval.")
            } catch (e: Exception) {
                Result.failure(Exception(e.message ?: "Registration failed"))
            }
        }
    }

    // 2. LOGIN & STATUS CHECK
    suspend fun loginUser(emailInput: String, passwordInput: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // First, authenticate the credentials
                supabase.auth.signInWith(Email) {
                    email = emailInput
                    password = passwordInput
                }

                // Get the user's ID to check their approval status
                val userId = supabase.auth.currentUserOrNull()?.id
                    ?: throw Exception("Login failed: User session not established.")

                // Check the auth_profiles table for their status
                val profile = supabase.postgrest["auth_profiles"]
                    .select { filter { eq("user_id", userId) } }
                    .decodeSingle<AuthProfile>()

                when (profile.access_status) {
                    "APPROVED" -> Result.success("Login Successful!")
                    "PENDING" -> {
                        supabase.auth.signOut() // Log them back out to secure the app
                        Result.failure(Exception("Account is pending Admin approval."))
                    }
                    "REVOKED" -> {
                        supabase.auth.signOut()
                        Result.failure(Exception("Account access has been revoked."))
                    }
                    else -> {
                        supabase.auth.signOut()
                        Result.failure(Exception("Unknown account status."))
                    }
                }
            } catch (e: Exception) {
                supabase.auth.signOut() // Cleanup on failure
                Result.failure(Exception("Invalid Email or Password."))
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
}