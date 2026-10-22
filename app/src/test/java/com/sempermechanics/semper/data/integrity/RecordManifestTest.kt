package com.sempermechanics.semper.data.integrity

import com.sempermechanics.semper.data.account.entitlement.DeviceKeyThumbprint
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecordSettings
import com.sempermechanics.semper.fixtures.SoftwareRecordSigner
import com.sempermechanics.semper.report.recordFingerprintText
import com.sempermechanics.semper.util.Digests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The record manifest (ADR-028): what [RecordManifestWriter] writes beside a
 * run's results and what [RecordVerifier] says of them afterwards. The `.dat`
 * bytes are never touched; any change to them, or to the manifest, reads
 * Changed, naming what changed.
 */
class RecordManifestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: File by lazy { tmp.newFolder("session") }
    private val layout by lazy { SessionLayout(dir) }
    private val signer = SoftwareRecordSigner()
    private val settings = SessionRecordSettings(
        subset = 41, step = 5, strainWin = 21,
        roiX = 1, roiY = 2, roiW = 300, roiH = 200,
        use6x6 = true, refUpdateInterval = 3,
    )
    private val build = RecordManifestWriter.Build(versionCode = 1036, versionName = "1.2-beta.4", engine = "59ac995")

    /** Three frames and the summary's sidecar, as a run stages them. */
    private fun stageResults(frames: Int = 3) {
        repeat(frames) { i -> SessionPaths.frameDat(dir, i).writeBytes(ByteArray(64) { (it + i).toByte() }) }
        layout.fieldRanges.writeBytes(byteArrayOf(9, 8, 7))
    }

    private fun write(signer: RecordSigner? = this.signer): RecordManifest? =
        RecordManifestWriter.write(dir, settings, signer, build, nowMs = 1_760_000_000_000L)

    private fun flipByte(file: File, at: Int = 10) {
        val bytes = file.readBytes()
        bytes[at] = (bytes[at].toInt() xor 1).toByte()
        file.writeBytes(bytes)
    }

    /** [files] with [frame]'s entry carrying its hash as it is now: what a forger would write. */
    private fun rehashed(files: List<RecordManifest.FileEntry>, frame: File) =
        files.map { if (it.name == frame.name) it.copy(sha256 = Digests.sha256Hex(frame)) else it }

    private fun fingerprintOf(file: File) = Digests.sha256Hex(file).take(RecordVerifier.FINGERPRINT_HEX)

    /** This phone's key, as the verifier knows it: [signer]'s. */
    private val localThumbprint: String get() = DeviceKeyThumbprint.of(signer.publicKeyDer())

    private fun verify(local: String? = localThumbprint) = RecordVerifier.verify(dir, local)

    @Test
    fun `a valid signature by another phone's key reads SignedElsewhere, naming that key`() {
        stageResults()
        val other = SoftwareRecordSigner()
        write(other)

        val result = verify()

        val otherKey = DeviceKeyThumbprint.of(other.publicKeyDer())
        assertEquals(RecordIntegrity.SignedElsewhere(otherKey, fingerprintOf(layout.recordManifest)), result)
        assertEquals(otherKey.take(8) + "…", (result as RecordIntegrity.SignedElsewhere).shortThumbprint)
    }

    @Test
    fun `a forger who edits a frame and re-signs with a fresh key of their own never reads Verified`() {
        stageResults()
        write()
        val frame = SessionPaths.frameDat(dir, 1)
        flipByte(frame)
        // Everything re-hashed and re-signed: the signature holds, by the forger's key.
        val forger = SoftwareRecordSigner()
        write(forger)

        val result = verify()

        assertTrue("$result", result is RecordIntegrity.SignedElsewhere)
        val named = (result as RecordIntegrity.SignedElsewhere).keyThumbprint
        assertEquals(DeviceKeyThumbprint.of(forger.publicKeyDer()), named)
    }

    @Test
    fun `with no key of its own a phone reads every signed manifest as SignedElsewhere`() {
        stageResults()
        write()

        assertTrue(verify(local = null) is RecordIntegrity.SignedElsewhere)
        assertTrue("its own key reads Verified", verify() is RecordIntegrity.Verified)
    }

    @Test
    fun `a signed manifest over untouched results verifies`() {
        stageResults()
        val datBefore = (0 until 3).map { SessionPaths.frameDat(dir, it).readBytes() }

        val manifest = write()

        assertEquals(RecordIntegrity.Verified(fingerprintOf(layout.recordManifest)), verify())
        assertEquals(1, signer.signed)
        (0 until 3).forEach {
            assertArrayEquals("frame $it untouched", datBefore[it], SessionPaths.frameDat(dir, it).readBytes())
        }
        assertNotNull(manifest)
        assertEquals(
            listOf("field_ranges.bin", "frame_0000.dat", "frame_0001.dat", "frame_0002.dat"),
            manifest!!.files.map { it.name },
        )
        manifest.files.forEach { entry ->
            val file = File(dir, entry.name)
            assertEquals(file.length(), entry.size)
            assertEquals(Digests.sha256Hex(file), entry.sha256)
        }
    }

    @Test
    fun `the manifest names the build, the engine, the settings and an untrusted clock`() {
        stageResults()

        val manifest = checkNotNull(write())

        assertEquals(1, manifest.v)
        assertEquals(RecordManifest.DEVICE_CLOCK, manifest.createdAt.source)
        assertEquals("2025-10-09T08:53:20Z", manifest.createdAt.utc)
        assertEquals(RecordManifest.AppStamp(1036, "1.2-beta.4"), manifest.app)
        assertEquals("59ac995", manifest.engine)
        assertEquals(RecordManifest.RunSpecRecord(41, 5, 21, 1, 2, 300, 200, true, 3), manifest.runSpec)
        assertEquals(DeviceKeyThumbprint.of(signer.publicKeyDer()), manifest.keyThumbprint)
        assertEquals(manifest, ManifestJson.parse(layout.recordManifest.readText())?.manifest)
    }

    @Test
    fun `one flipped byte in a frame reads Changed, naming that frame`() {
        stageResults()
        write()
        flipByte(SessionPaths.frameDat(dir, 1))

        assertEquals(
            RecordIntegrity.Changed(listOf("frame_0001.dat"), fingerprintOf(layout.recordManifest)),
            verify(),
        )
    }

    @Test
    fun `a missing frame and an extra frame both read Changed`() {
        stageResults()
        write()
        SessionPaths.frameDat(dir, 0).delete()
        SessionPaths.frameDat(dir, 5).writeBytes(ByteArray(32))

        val result = verify() as RecordIntegrity.Changed

        assertEquals(listOf("frame_0000.dat", "frame_0005.dat"), result.files)
    }

    @Test
    fun `the field-range cache is checked when there and never missed`() {
        stageResults()
        write()

        layout.fieldRanges.delete()
        assertTrue("a cache a restore does not bring back", verify() is RecordIntegrity.Verified)

        layout.fieldRanges.writeBytes(byteArrayOf(1))
        assertEquals(listOf("field_ranges.bin"), (verify() as RecordIntegrity.Changed).files)
    }

    @Test
    fun `no manifest reads NotRecorded`() {
        stageResults()

        assertEquals(RecordIntegrity.NotRecorded, verify())
        assertNull(RecordIntegrity.NotRecorded.fingerprint)
    }

    @Test
    fun `no key writes an unsigned manifest, which reads Unsigned`() {
        stageResults()

        val manifest = checkNotNull(write(signer = null))

        assertNull(manifest.signature)
        assertNull(manifest.publicKey)
        assertNull(manifest.keyThumbprint)
        val json = Json.parseToJsonElement(layout.recordManifest.readText()).jsonObject
        assertEquals("the file says it is unsigned", JsonNull, json[ManifestJson.SIGNATURE])
        assertEquals(RecordIntegrity.Unsigned(fingerprintOf(layout.recordManifest)), verify())
    }

    @Test
    fun `a key that will not sign leaves the manifest unsigned rather than none`() {
        stageResults()
        val refusing = object : RecordSigner {
            override fun publicKeyDer(): ByteArray = signer.publicKeyDer()
            override fun sign(message: ByteArray): String = error("Keystore busy")
        }

        val manifest = checkNotNull(write(refusing))

        assertNull(manifest.signature)
        assertTrue(verify() is RecordIntegrity.Unsigned)
    }

    @Test
    fun `a run with no frames writes nothing`() {
        layout.fieldRanges.writeBytes(byteArrayOf(1))

        assertNull(write())
        assertFalse(layout.recordManifest.exists())
    }

    @Test
    fun `a manifest rewritten to match edited frames fails its signature`() {
        stageResults()
        write()
        val frame = SessionPaths.frameDat(dir, 2)
        flipByte(frame)
        // The edited frame's hash put in the manifest, the run's signature kept.
        val parsed = checkNotNull(ManifestJson.parse(layout.recordManifest.readText())).manifest
        val forged = parsed.copy(files = rehashed(parsed.files, frame))
        layout.recordManifest.writeText(ManifestJson.encode(forged))

        assertEquals(listOf(SessionLayout.MANIFEST), (verify() as RecordIntegrity.Changed).files)
    }

    @Test
    fun `a manifest signed by one key and claiming another's thumbprint fails`() {
        stageResults()
        val manifest = checkNotNull(write())
        val other = SoftwareRecordSigner()
        layout.recordManifest.writeText(
            ManifestJson.encode(manifest.copy(keyThumbprint = DeviceKeyThumbprint.of(other.publicKeyDer()))),
        )

        assertTrue(verify() is RecordIntegrity.Changed)
    }

    @Test
    fun `stripping the signature and rewriting the hashes reads Unsigned, never Verified`() {
        stageResults()
        val manifest = checkNotNull(write())
        val frame = SessionPaths.frameDat(dir, 0)
        flipByte(frame)
        val forged = manifest.copy(signature = null, files = rehashed(manifest.files, frame))
        layout.recordManifest.writeText(ManifestJson.encode(forged))

        assertTrue(verify() is RecordIntegrity.Unsigned)
    }

    @Test
    fun `a manifest that does not parse reads Changed, naming itself`() {
        stageResults()
        layout.recordManifest.writeText("[not a manifest")

        val result = verify() as RecordIntegrity.Changed

        assertEquals(listOf(SessionLayout.MANIFEST), result.files)
        assertEquals(fingerprintOf(layout.recordManifest), result.fingerprint)
        layout.recordManifest.writeText("[1, 2]")
        assertTrue(verify() is RecordIntegrity.Changed)
    }

    @Test
    fun `a listed name that reaches outside the session reads Changed`() {
        stageResults()
        val manifest = checkNotNull(write(signer = null))
        layout.recordManifest.writeText(
            ManifestJson.encode(manifest.copy(files = manifest.files + RecordManifest.FileEntry("../x.dat", 1, "00"))),
        )

        assertEquals(listOf("../x.dat"), (verify() as RecordIntegrity.Changed).files)
    }

    @Test
    fun `canonical bytes sort every key, carry no whitespace and leave the signature out`() {
        stageResults()
        val manifest = checkNotNull(write())

        val canonical = ManifestJson.canonicalBytes(manifest).toString(Charsets.UTF_8)

        assertFalse(canonical.contains(' ') || canonical.contains('\n'))
        assertFalse(canonical.contains("\"signature\""))
        val head = "{\"app\":{\"versionCode\":1036,\"versionName\":\"1.2-beta.4\"},\"createdAt\":"
        assertTrue(canonical, canonical.startsWith(head))
        assertTrue(canonical.contains("{\"name\":\"frame_0000.dat\",\"sha256\":\""))
        assertSortedEverywhere(Json.parseToJsonElement(canonical).jsonObject)
        assertEquals(
            listOf("app", "createdAt", "engine", "files", "keyThumbprint", "publicKey", "runSpec", "v"),
            Json.parseToJsonElement(canonical).jsonObject.keys.toList(),
        )
    }

    @Test
    fun `canonical bytes are the same from the model, from the file, and on every call`() {
        stageResults()
        val manifest = checkNotNull(write())
        val parsed = checkNotNull(ManifestJson.parse(layout.recordManifest.readText()))

        val fromModel = ManifestJson.canonicalBytes(manifest)

        assertArrayEquals(fromModel, ManifestJson.canonicalBytes(manifest))
        assertArrayEquals(fromModel, ManifestJson.canonicalBytes(parsed.obj))
        assertEquals(
            "the file is the canonical JSON with the signature added",
            ManifestJson.encode(manifest),
            layout.recordManifest.readText(),
        )
    }

    @Test
    fun `a field a later version adds is signed and verified with the rest`() {
        stageResults()
        val unsigned = checkNotNull(write(signer = null))
        val der = signer.publicKeyDer()
        val keyed = unsigned.copy(
            keyThumbprint = DeviceKeyThumbprint.of(der),
            publicKey = ManifestSignature.encodeKey(der),
        )
        val obj = JsonObject(
            Json.parseToJsonElement(ManifestJson.encode(keyed)).jsonObject + ("zNext" to JsonPrimitive("later")),
        )
        val signature = JsonPrimitive(signer.sign(ManifestJson.canonicalBytes(obj)))
        layout.recordManifest.writeText(JsonObject(obj + (ManifestJson.SIGNATURE to signature)).toString())

        assertTrue(verify() is RecordIntegrity.Verified)
    }

    @Test
    fun `the cover prints the fingerprint and a state that cannot read as a pass unless verified`() {
        val fp = "0123456789abcdef"
        assertEquals("$fp (verified)", recordFingerprintText(RecordIntegrity.Verified(fp)))
        assertEquals("$fp (not signed)", recordFingerprintText(RecordIntegrity.Unsigned(fp)))
        assertEquals(
            "$fp (signed on another device, key abcdefgh…; not verified here)",
            recordFingerprintText(RecordIntegrity.SignedElsewhere("abcdefghijklmnop", fp)),
        )
        assertEquals(
            "$fp (CHANGED since analysis: 2 file(s))",
            recordFingerprintText(RecordIntegrity.Changed(listOf("a.dat", "b.dat"), fp)),
        )
        assertEquals(
            "unreadable (CHANGED since analysis: 1 file(s))",
            recordFingerprintText(RecordIntegrity.Changed(listOf("m"), null)),
        )
        assertEquals("Not recorded", recordFingerprintText(RecordIntegrity.NotRecorded))
    }

    private fun assertSortedEverywhere(obj: JsonObject) {
        assertEquals(obj.keys.sorted(), obj.keys.toList())
        obj.values.forEach { value ->
            when (value) {
                is JsonObject -> assertSortedEverywhere(value)
                is JsonArray -> value.filterIsInstance<JsonObject>().forEach(::assertSortedEverywhere)
                else -> Unit
            }
        }
    }
}
