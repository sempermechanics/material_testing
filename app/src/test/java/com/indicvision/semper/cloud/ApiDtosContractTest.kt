package com.indicvision.semper.cloud

import com.indicvision.semper.data.net.DeviceRegisterRequest
import com.indicvision.semper.data.net.FileCompleteRequest
import com.indicvision.semper.data.net.FileSpecDto
import com.indicvision.semper.data.net.ListSessionsResponse
import com.indicvision.semper.data.net.MeResponse
import com.indicvision.semper.data.net.SessionCreateRequest
import com.indicvision.semper.data.net.SessionCreateResponse
import com.indicvision.semper.data.net.SessionFilesResponse
import com.indicvision.semper.data.net.SessionUploadsResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-contract tests for the DTOs in [ApiDtos.kt].
 *
 * The property names on these classes ARE the JSON contract with the GCP
 * backend (backend/app/models.py + main.py responses) — there is no schema
 * file in between. A rename that compiles fine here silently breaks the
 * backend, so this suite pins the exact field names in both directions,
 * using the same Json configuration as [IndicApi].
 */
class ApiDtosContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ------------------------------------------------------------ responses

    @Test
    fun `me response decodes backend shape including snake_case access_status`() {
        val me = json.decodeFromString<MeResponse>(
            """{"uid":"u1","email":"a@b.com","role":"user","access_status":"APPROVED"}""",
        )
        assertEquals("u1", me.uid)
        assertEquals("APPROVED", me.accessStatus)
    }

    @Test
    fun `session list decodes sessions and quota`() {
        val resp = json.decodeFromString<ListSessionsResponse>(
            """
            {"sessions":[{"sessionId":"s1","localSessionId":"l1","specimen":"sp",
              "status":"COMPLETED","fileCount":2,"completedCount":2,
              "totalBytes":123,"driveFolderId":"df"}],
             "quota":{"used":1,"max":50}}
            """.trimIndent(),
        )
        val s = resp.sessions.single()
        assertEquals("s1", s.sessionId)
        assertEquals("l1", s.localSessionId)
        assertEquals("df", s.driveFolderId)
        assertEquals(1, resp.quota.used)
        assertEquals(50, resp.quota.max)
    }

    @Test
    fun `session create response decodes upload targets`() {
        val resp = json.decodeFromString<SessionCreateResponse>(
            """{"sessionId":"s2","uploads":[
                 {"fileId":"f1","uploadUrl":"https://u","chunkSize":33554432}]}""",
        )
        assertEquals("s2", resp.sessionId)
        assertEquals(33554432, resp.uploads.single().chunkSize)
    }

    @Test
    fun `pending uploads decode for the resume path`() {
        val resp = json.decodeFromString<SessionUploadsResponse>(
            """{"sessionId":"s3","status":"UPLOADING","uploads":[
                 {"fileId":"f2","uploadUrl":"https://u2","chunkSize":8388608,
                  "name":"Session.zip","role":"bundle","sizeBytes":42}]}""",
        )
        val u = resp.uploads.single()
        assertEquals("bundle", u.role)
        assertEquals(42L, u.sizeBytes)
    }

    @Test
    fun `session files manifest decodes for restore`() {
        val resp = json.decodeFromString<SessionFilesResponse>(
            """{"sessionId":"s4","localSessionId":"l4","files":[
                 {"fileId":"f3","name":"metadata.json","role":"metadata",
                  "sizeBytes":10,"sha256":null,"status":"COMPLETED"}]}""",
        )
        assertEquals("metadata", resp.files.single().role)
        assertNull(resp.files.single().sha256)
    }

    // ------------------------------------------------------------- requests

    @Test
    fun `session create request encodes the exact backend field names`() {
        val encoded = json.encodeToString(
            SessionCreateRequest(
                specimen = "sp",
                files = listOf(FileSpecDto("Session.zip", "bundle", 42L, "a".repeat(64))),
                metrics = mapOf("frameCount" to 2f),
                localSessionId = "local1",
            ),
        )
        // backend/app/models.py: SessionCreate + FileSpec
        for (key in listOf(
            "\"specimen\"",
            "\"files\"",
            "\"metrics\"",
            "\"localSessionId\"",
            "\"name\"",
            "\"role\"",
            "\"bytes\"",
            "\"sha256\"",
        )) {
            assertTrue("missing $key in $encoded", encoded.contains(key))
        }
    }

    @Test
    fun `file complete request encodes the exact backend field names`() {
        val encoded = json.encodeToString(
            FileCompleteRequest(sessionId = "s", driveFileId = "d", bytes = 5L, md5 = "m"),
        )
        // backend/app/models.py: FileComplete
        for (key in listOf("\"sessionId\"", "\"driveFileId\"", "\"bytes\"", "\"md5\"")) {
            assertTrue("missing $key in $encoded", encoded.contains(key))
        }
    }

    @Test
    fun `device register request encodes the exact backend field names`() {
        val encoded = json.encodeToString(
            DeviceRegisterRequest(
                deviceId = "and-12345678",
                publicKeyPem = "pem",
                model = "m",
                osVersion = "o",
                appVersion = "v",
            ),
        )
        // backend/app/models.py: DeviceReg
        for (key in listOf(
            "\"deviceId\"",
            "\"publicKeyPem\"",
            "\"model\"",
            "\"osVersion\"",
            "\"appVersion\"",
        )) {
            assertTrue("missing $key in $encoded", encoded.contains(key))
        }
    }

    @Test
    fun `unknown backend fields are tolerated`() {
        // The backend may add fields at any time; the app must not crash.
        val me = json.decodeFromString<MeResponse>(
            """{"uid":"u","access_status":"PENDING","brand_new_field":123}""",
        )
        assertEquals("PENDING", me.accessStatus)
    }
}
