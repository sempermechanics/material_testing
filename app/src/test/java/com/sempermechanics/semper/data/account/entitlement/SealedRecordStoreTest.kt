package com.sempermechanics.semper.data.account.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TD-265: the write order of the sealed record, its first-seal mark and its
 * key, with a process death simulated at every step. A non-malicious event
 * (a crash, a lost Keystore key) must read as no token, so a licensed user
 * keeps the cached config instead of dropping to demo; only an edited record
 * or a key whose record was deleted reads as tampered.
 */
class SealedRecordStoreTest {

    /** The process dies here: every write before it is on disk, nothing after. */
    private class Crash : RuntimeException("process died")

    /** A preference file and a Keystore in fields; [crashAt] names the write the process dies at. */
    private class FakeDisk(var crashAt: String? = null) : EntitlementDisk {
        var stored: String? = null
        var mark = false
        var key: ByteArray? = null
        var keyReadable = true
        private var keys = 0

        override val tagger = Tagger { data ->
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(checkNotNull(key) { "no key" }, "HmacSHA256"))
                doFinal(data)
            }
        }

        private fun step(name: String) {
            if (crashAt == name) throw Crash()
        }

        override fun record(): String? = stored

        override fun sealing(): Boolean = mark

        override fun key(): KeyPresence = when {
            !keyReadable -> KeyPresence.UNREADABLE
            key != null -> KeyPresence.PRESENT
            else -> KeyPresence.ABSENT
        }

        override fun markSealing() {
            step("mark")
            stored = null
            mark = true
        }

        override fun createKey() {
            step("createKey")
            key = ByteArray(32) { (it + ++keys).toByte() }
        }

        override fun deleteKey() {
            step("deleteKey")
            key = null
        }

        override fun commit(record: String?): Boolean {
            step("commit")
            stored = record
            mark = false
            return true
        }
    }

    private val record = SealedEntitlement.Record(
        token = "header.payload.signature",
        clock = EntitlementClock.anchored(1_800_000_000_000L, DeviceTime(1_800_000_000_000L, 5_000L, 1)),
    )

    private fun saved(): FakeDisk = FakeDisk().also { assertTrue(SealedRecordStore.save(it, record)) }

    /** What [disk] means for a licensed account: the token's status, and the bound it puts on the cache. */
    private fun statusOf(disk: FakeDisk): TokenStatus {
        val stored = SealedRecordStore.load(disk)
        return EntitlementPolicy.evaluate(stored, null, DeviceTime(0L, 0L, 1)).first
    }

    @Test
    fun `a saved record loads back`() {
        val disk = saved()
        assertEquals(StoredEntitlement.Sealed(record), SealedRecordStore.load(disk))
        assertFalse("the mark goes with the record's write", disk.mark)
    }

    @Test
    fun `a crash between making the key and writing the record does not drop a licensed user to demo`() {
        val disk = FakeDisk(crashAt = "commit")
        assertThrows(Crash::class.java) { SealedRecordStore.save(disk, record) }
        assertTrue("the key exists, the record does not", disk.key != null && disk.stored == null)
        disk.crashAt = null

        assertEquals(StoredEntitlement.Nothing, SealedRecordStore.load(disk))
        assertEquals(TokenStatus.NotUsed, statusOf(disk))
        assertEquals(EntitlementPolicy.UNBOUNDED, EntitlementPolicy.bound(statusOf(disk), DEMO))
        assertNull("the orphan key is dropped", disk.key)
        assertFalse(disk.mark)
        // The next /v1/config seals a fresh record, which reads back.
        assertTrue(SealedRecordStore.save(disk, record))
        assertEquals(StoredEntitlement.Sealed(record), SealedRecordStore.load(disk))
    }

    @Test
    fun `a crash at any step of a first seal reads as no token`() {
        listOf("mark", "createKey", "commit").forEach { step ->
            val disk = FakeDisk(crashAt = step)
            runCatching { SealedRecordStore.save(disk, record) }
            disk.crashAt = null
            assertEquals(step, StoredEntitlement.Nothing, SealedRecordStore.load(disk))
        }
    }

    @Test
    fun `a write that does not reach the disk leaves no token, not tampering`() {
        val fake = FakeDisk()
        val refusing = object : EntitlementDisk by fake {
            override fun commit(record: String?): Boolean = false
        }
        assertFalse(SealedRecordStore.save(refusing, record))

        assertEquals(StoredEntitlement.Nothing, SealedRecordStore.load(fake))
    }

    @Test
    fun `a record a newer build sealed reads as no token, not tampering, and is resealed`() {
        // readiness TE1-004: it read as tampered, i.e. demo while offline.
        val disk = saved()
        val newer = "{\"v\":2,\"token\":\"t\"}".toByteArray()
        disk.stored = Base64Url.encode(newer) + "." + Base64Url.encode(disk.tagger.tag(newer))

        assertEquals(StoredEntitlement.Nothing, SealedRecordStore.load(disk))
        assertEquals(TokenStatus.NotUsed, statusOf(disk))
        // The next /v1/config seals it in this build's version, under the same key.
        assertTrue(SealedRecordStore.save(disk, record))
        assertEquals(StoredEntitlement.Sealed(record), SealedRecordStore.load(disk))
    }

    @Test
    fun `a lost Keystore key reads as no token and drops the record`() {
        val disk = saved()
        disk.key = null

        assertEquals(StoredEntitlement.Nothing, SealedRecordStore.load(disk))
        assertNull(disk.stored)
        assertEquals(TokenStatus.NotUsed, statusOf(disk))
    }

    @Test
    fun `a record left by a lost key and a crash in the next first seal reads as no token`() {
        // The key went (Keystore loss, or a restore that brought the
        // preferences back without it) and the record was not tidied away;
        // the next seal makes a new key, and the process dies before writing.
        val disk = saved()
        disk.key = null
        disk.crashAt = "commit"
        assertThrows(Crash::class.java) { SealedRecordStore.save(disk, record) }
        disk.crashAt = null

        assertNull("the old record went with the mark, not after the new key", disk.stored)
        assertEquals(StoredEntitlement.Nothing, SealedRecordStore.load(disk))
        assertEquals(TokenStatus.NotUsed, statusOf(disk))
    }

    @Test
    fun `a clear cut short between the key and the record reads as no token`() {
        val disk = saved()
        disk.crashAt = "commit"
        assertThrows(Crash::class.java) { SealedRecordStore.clear(disk) }
        disk.crashAt = null

        assertEquals(StoredEntitlement.Nothing, SealedRecordStore.load(disk))
    }

    @Test
    fun `a key that cannot be deleted keeps its record rather than orphaning the key`() {
        val disk = saved()
        disk.crashAt = "deleteKey"
        assertThrows(Crash::class.java) { SealedRecordStore.clear(disk) }
        disk.crashAt = null

        assertEquals(StoredEntitlement.Sealed(record), SealedRecordStore.load(disk))
    }

    @Test
    fun `a deleted record with its key still there is tampering`() {
        val disk = saved()
        disk.stored = null

        assertEquals(StoredEntitlement.Tampered, SealedRecordStore.load(disk))
        assertEquals(TokenStatus.Lapsed("TAMPERED"), statusOf(disk))
    }

    @Test
    fun `an edited record is tampering, with the key present`() {
        val disk = saved()
        val other = SealedEntitlement.seal(record.copy(clock = record.clock.copy(rolledBack = true))) { ByteArray(32) }
        disk.stored = other

        assertEquals(StoredEntitlement.Tampered, SealedRecordStore.load(disk))
    }

    @Test
    fun `a Keystore that cannot be read is no token, and nothing is deleted`() {
        val disk = saved()
        disk.keyReadable = false

        assertEquals(StoredEntitlement.Nothing, SealedRecordStore.load(disk))
        assertTrue(disk.stored != null && disk.key != null)
    }

    @Test
    fun `tidying up that fails still reads as no token`() {
        val disk = saved()
        disk.key = null
        disk.crashAt = "commit"

        assertEquals(StoredEntitlement.Nothing, SealedRecordStore.load(disk))
    }

    @Test
    fun `a second save reuses the key and sets no mark`() {
        val disk = saved()
        val key = disk.key
        disk.crashAt = "mark"

        assertTrue(SealedRecordStore.save(disk, record.copy(token = "a.b.c")))
        assertTrue(key.contentEquals(disk.key))
    }

    private companion object {
        const val DEMO = 25
    }
}
