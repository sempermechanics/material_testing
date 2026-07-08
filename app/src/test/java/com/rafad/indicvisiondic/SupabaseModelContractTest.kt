package com.rafad.indicvisiondic
import com.rafad.indicvisiondic.data.AnalysisSessionInsert
import com.rafad.indicvisiondic.data.AnalysisSessionResponse
import com.rafad.indicvisiondic.data.AuthProfile
import com.rafad.indicvisiondic.data.UserProfileInsert

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SUITE: Supabase model contract tests (JVM, no Android required).
 *
 * These tests pin the JSON wire format between the app and the Supabase
 * tables (`auth_profiles`, `analysis_sessions`). The @SerialName
 * annotations ARE the database schema contract — if a column is renamed
 * or a serial name drifts, login/registration breaks silently at
 * runtime. These tests fail at build time instead.
 *
 * Run: ./gradlew testDebugUnitTest
 */
class SupabaseModelContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // AuthProfile — decoded on every login and every splash routing check
    // ------------------------------------------------------------------

    @Test
    fun `AuthProfile decodes full row from auth_profiles`() {
        val row = """
            {
              "access_status": "APPROVED",
              "device_fingerprint": "abc123",
              "hardware_public_key": "BASE64KEY=="
            }
        """.trimIndent()

        val profile = json.decodeFromString<AuthProfile>(row)
        assertEquals("APPROVED", profile.accessStatus)
        assertEquals("abc123", profile.deviceFingerprint)
        assertEquals("BASE64KEY==", profile.hardwarePublicKey)
    }

    @Test
    fun `AuthProfile tolerates missing optional device columns`() {
        // A freshly-registered row may have NULL fingerprint/key columns.
        // Decoding must not throw and must yield nulls (the code treats
        // null fingerprint as "not yet locked to a device").
        val row = """{ "access_status": "PENDING" }"""
        val profile = json.decodeFromString<AuthProfile>(row)
        assertEquals("PENDING", profile.accessStatus)
        assertNull(profile.deviceFingerprint)
        assertNull(profile.hardwarePublicKey)
    }

    @Test
    fun `AuthProfile ignores unknown columns added server-side`() {
        // Adding a column to auth_profiles must never break old clients.
        val row = """
            {
              "access_status": "APPROVED",
              "brand_new_column": 42
            }
        """.trimIndent()
        val profile = json.decodeFromString<AuthProfile>(row)
        assertEquals("APPROVED", profile.accessStatus)
    }

    // ------------------------------------------------------------------
    // UserProfileInsert — sent once at registration
    // ------------------------------------------------------------------

    @Test
    fun `UserProfileInsert serializes with exact database column names`() {
        val insert = UserProfileInsert(
            userId = "uid-1",
            emailAddress = "user@example.com",
            deviceFingerprint = "fp-9",
            hardwarePublicKey = "pk==",
        )
        // encodeDefaults=true forces defaulted fields onto the wire so we
        // can pin their column names too.
        val encoder = Json { encodeDefaults = true }
        val obj = encoder.encodeToString(insert).let { json.parseToJsonElement(it).jsonObject }

        // Keys must match the auth_profiles columns EXACTLY
        assertEquals("uid-1", obj["user_id"]!!.jsonPrimitive.content)
        assertEquals("user@example.com", obj["email_address"]!!.jsonPrimitive.content)
        assertEquals("fp-9", obj["device_fingerprint"]!!.jsonPrimitive.content)
        assertEquals("pk==", obj["hardware_public_key"]!!.jsonPrimitive.content)
        assertEquals("PENDING", obj["access_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `UserProfileInsert omits defaulted access_status with standard Json`() {
        // PINNED BEHAVIOR: kotlinx.serialization does NOT encode defaulted
        // properties by default. With the standard Json used by the
        // Supabase SDK, access_status is absent from the payload and the
        // DATABASE COLUMN DEFAULT decides the initial status. If you ever
        // switch the SDK to encodeDefaults=true, re-verify the DB default
        // and RLS still force PENDING for self-registration.
        val insert = UserProfileInsert(
            userId = "u", emailAddress = "e", deviceFingerprint = "d",
            hardwarePublicKey = "k",
        )
        val obj = json.encodeToString(insert).let { json.parseToJsonElement(it).jsonObject }
        assertNull(obj["access_status"])
    }

    @Test
    fun `UserProfileInsert defaults access_status to PENDING`() {
        // Security invariant: a client can never self-register as APPROVED.
        val insert = UserProfileInsert(
            userId = "u", emailAddress = "e", deviceFingerprint = "d",
            hardwarePublicKey = "k",
        )
        assertEquals("PENDING", insert.accessStatus)
    }

    // ------------------------------------------------------------------
    // AnalysisSession models — used by the upload worker
    // ------------------------------------------------------------------

    @Test
    fun `AnalysisSessionInsert serializes with exact column names`() {
        val session = AnalysisSessionInsert(
            userId = "uid-2",
            userEmail = "u@e.com",
            specimenIdentifier = "Sample_A",
            pointsConverged = 1500,
            avgIterations = 6.5f,
            executionTimeMs = 4200,
        )
        val obj = json.encodeToString(session).let { json.parseToJsonElement(it).jsonObject }

        assertEquals("uid-2", obj["user_id"]!!.jsonPrimitive.content)
        assertEquals("u@e.com", obj["user_email"]!!.jsonPrimitive.content)
        assertEquals("Sample_A", obj["specimen_identifier"]!!.jsonPrimitive.content)
        assertEquals("1500", obj["points_converged"]!!.jsonPrimitive.content)
        assertEquals("4200", obj["execution_time_ms"]!!.jsonPrimitive.content)
    }

    @Test
    fun `AnalysisSessionResponse decodes session_id`() {
        val row = """{ "session_id": "sess-777" }"""
        val resp = json.decodeFromString<AnalysisSessionResponse>(row)
        assertEquals("sess-777", resp.sessionId)
    }
}
