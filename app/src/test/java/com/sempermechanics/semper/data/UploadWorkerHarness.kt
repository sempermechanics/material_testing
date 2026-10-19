package com.sempermechanics.semper.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.FileCompleteRequest
import com.sempermechanics.semper.data.net.PendingUploadDto
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.SessionCreateRequest
import com.sempermechanics.semper.data.net.SessionCreateResponse
import com.sempermechanics.semper.data.net.SessionUploadsResponse
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.util.Digests
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The scripted backend and the prepared staging the [DicUploadWorker] tests run
 * against ([DicUploadSeams]): one session, its staging complete and verified, so
 * a run goes straight to the cloud unless a test takes part of it away.
 */
open class UploadWorkerHarness {

    protected val context: Application = ApplicationProvider.getApplicationContext()
    protected val api = UploadApi()
    protected lateinit var sessionDir: File
    protected lateinit var staging: File
    protected val realForeground = DicUploadSeams.inForeground

    @Before
    fun setUp() {
        DicUploadSeams.api = { api }
        DicUploadSeams.tokens = FakeTokens("tok")
        DicUploadSeams.inForeground = { false }
    }

    @After
    fun tearDown() {
        DicUploadSeams.api = { SemperApi.get(it) }
        DicUploadSeams.tokens = TokenProvider
        DicUploadSeams.inForeground = realForeground
        SessionStore.deleteAll(context)
    }

    // ── harness ──────────────────────────────────────────────────────────────

    /**
     * [FakeCloudApi] plus the upload calls, scripted the same way. Thread-safe
     * lists: the worker uploads and completes several files at once.
     */
    protected class UploadApi(val base: FakeCloudApi = FakeCloudApi()) : CloudApi by base {
        val calls = CopyOnWriteArrayList<String>()
        val deleted = CopyOnWriteArrayList<String>()
        val completed = CopyOnWriteArrayList<FileCompleteRequest>()
        var created: SessionCreateRequest? = null

        var onCreateSession: suspend (SessionCreateRequest) -> SessionCreateResponse = {
            throw AssertionError("unexpected CloudApi.createSession call")
        }
        var onSessionUploads: suspend (String) -> SessionUploadsResponse = {
            throw AssertionError("unexpected CloudApi.listSessionUploads call")
        }
        var onUpload: suspend (File) -> Pair<String, String> = { it.name to Digests.md5Hex(it) }
        var onComplete: suspend (String, FileCompleteRequest) -> Unit = { _, req -> completed += req }

        init {
            base.onDeleteSession = { _, sid -> deleted += sid }
        }

        override suspend fun createSession(idToken: String, request: SessionCreateRequest): SessionCreateResponse {
            calls += "createSession"
            created = request
            return onCreateSession(request)
        }

        override suspend fun listSessionUploads(idToken: String, sessionId: String): SessionUploadsResponse {
            calls += "listSessionUploads"
            return onSessionUploads(sessionId)
        }

        override suspend fun uploadResumable(
            uploadUrl: String,
            file: File,
            chunkSize: Int,
            onBytes: (Long) -> Unit,
        ): Pair<String, String> {
            calls += "uploadResumable"
            return onUpload(file)
        }

        override suspend fun completeFile(idToken: String, fileId: String, request: FileCompleteRequest) {
            calls += "completeFile"
            onComplete(fileId, request)
        }
    }

    protected fun zip(file: File, entry: String) {
        ZipOutputStream(file.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(entry))
            zos.write(byteArrayOf(1, 2, 3, 4))
            zos.closeEntry()
        }
        File(file.parentFile, "${file.name}.sha256").writeText(Digests.sha256Hex(file))
    }

    /**
     * One saved single-frame analysis whose staging is complete and verified,
     * so the run goes straight to the cloud. [cloudSessionId] is the pointer a
     * previous run left (blank = never created).
     */
    protected fun seed(cloudSessionId: String = ""): SessionRecord {
        sessionDir = SessionStore.dirFor(context, ID)
        val ref = File(sessionDir, "reference.png").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        File(sessionDir, SessionPaths.RAW_DEFORMED_SUBDIR).mkdirs()
        File(sessionDir, "${SessionPaths.RAW_DEFORMED_SUBDIR}/def.png").writeBytes(byteArrayOf(8, 8))
        SessionPaths.frameDat(sessionDir, 0).writeBytes(ByteArray(32))

        staging = File(sessionDir, "upload_staging").apply { mkdirs() }
        File(staging, "metadata.json").writeText("""{"schema":"test"}""")
        File(staging, "analysis_data.csv").writeText("image\n")
        File(staging, "reports").mkdirs()
        File(staging, "reports/Master_Report_Frame_1.pdf").writeText("%PDF")
        File(staging, "processed/Frame_1").mkdirs()
        File(staging, "processed/Frame_1/exx.png").writeText("png")
        zip(File(staging, "Session.zip"), "raw/Reference.png")
        zip(File(staging, "Extras.zip"), "csv/analysis_data.csv")
        File(staging, ".bundles_done").createNewFile()

        val record = SessionRecord(
            id = ID, name = "n", createdAt = 1L, updatedAt = 1L, frameCount = 1,
            subset = 41, step = 5, strainWindow = 15,
            imgW = 64, imgH = 64, roiX = 0, roiY = 0, roiW = 64, roiH = 64,
            refPath = ref.path, refName = "ref.png", sessionDir = sessionDir.path,
            defNames = listOf("def.png"),
            cloudSessionId = cloudSessionId,
            syncState = SessionRecord.SyncState.PENDING,
        )
        assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
        return record
    }

    /** The three staged files as the backend would list them pending. */
    protected fun pendingAll(): List<PendingUploadDto> = listOf(
        PendingUploadDto(
            "f_meta",
            "u_meta",
            name = "metadata.json",
            role = "metadata",
            sizeBytes = File(staging, "metadata.json").length(),
        ),
        PendingUploadDto(
            "f_bundle",
            "u_bundle",
            name = "Session.zip",
            role = "bundle",
            sizeBytes = File(staging, "Session.zip").length(),
        ),
        PendingUploadDto(
            "f_extras",
            "u_extras",
            name = "Extras.zip",
            role = "extras",
            sizeBytes = File(staging, "Extras.zip").length(),
        ),
    )

    protected fun uploading(sid: String, uploads: List<PendingUploadDto> = pendingAll()) =
        SessionUploadsResponse(sessionId = sid, status = "UPLOADING", uploads = uploads)

    protected fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<DicUploadWorker>(context)
            .setInputData(workDataOf(IntentKeys.SESSION_LOCAL_ID to ID))
            .build()
            .doWork()
    }

    protected fun row(): SessionRecord = SessionStore.get(context, ID)!!

    protected fun apiError(code: Int, detail: String) =
        ApiException(code, """{"detail":"$detail"}""", "req-1")

    protected fun failReason(result: ListenableWorker.Result): String? =
        (result as? ListenableWorker.Result.Failure)?.outputData?.getString(IntentKeys.UPLOAD_FAIL_REASON)

    protected fun string(id: Int) = context.getString(id)

    /** Empties the staging, so the run has to prepare it again. */
    protected fun unstage() {
        staging.deleteRecursively()
    }

    /** Drops the staged archive [name] and its sidecar, so the run has to build it. */
    protected fun dropArchive(name: String) {
        File(staging, name).delete()
        File(staging, "$name.sha256").delete()
    }

    protected fun entries(zip: File): List<String> = ZipFile(zip).use { z -> z.entries().toList().map { it.name } }

    protected fun staged(name: String) = File(staging, name).readBytes()

    protected companion object {
        const val ID = "s_upload"
    }
}
