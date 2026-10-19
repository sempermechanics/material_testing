package com.sempermechanics.semper.data.account.entitlement

/**
 * Where the sealed record ([SealedEntitlement]) and its HMAC key live: one
 * preference file and the Keystore on the phone ([EntitlementStore]), plain
 * fields in tests. Every write is durable when it returns, so the order
 * [SealedRecordStore] writes in is the order a crash can stop at.
 */
interface EntitlementDisk {

    /** MACs under the stored key; throws when there is none. */
    val tagger: Tagger

    /** The sealed record, or null. */
    fun record(): String?

    /** True between [markSealing] and the next [commit]: a first seal is in flight. */
    fun sealing(): Boolean

    fun key(): KeyPresence

    /**
     * Note, durably, that a first seal is starting, before its key exists,
     * and remove any record left from a key that is gone, in one write: that
     * record's MAC cannot match the new key, so a crash before the new record
     * is written must not leave the two side by side.
     */
    fun markSealing()

    fun createKey()

    fun deleteKey()

    /**
     * Write [record] (null removes it) and clear the [sealing] mark, together,
     * durably. False when the write did not reach the disk.
     */
    fun commit(record: String?): Boolean
}

/**
 * The order the record, the mark and the key are written and read in, so a
 * process that dies at any step leaves something [EntitlementPolicy.reading]
 * reads as "no token" rather than as tampering:
 *
 * - a first seal marks itself (dropping any record a lost key left behind),
 *   then makes the key, then writes the record and clears the mark in one
 *   commit: a crash after the key leaves key + mark, which reads as no token
 *   and drops the key, never an old record beside a new key;
 * - a clear deletes the key before the record: a crash between leaves a
 *   record with no key, which reads as no token and drops the record;
 * - a lost Keystore key leaves the same, and reads the same.
 *
 * Only a key with neither record nor mark, a record whose MAC does not match
 * its key, or a record this build cannot parse reads as tampered.
 */
object SealedRecordStore {

    fun load(disk: EntitlementDisk): StoredEntitlement {
        val text = disk.record()
        return when (EntitlementPolicy.reading(text != null, disk.key(), disk.sealing())) {
            StoreReading.NOTHING -> StoredEntitlement.Nothing
            StoreReading.TAMPERED -> StoredEntitlement.Tampered
            // Tidying up is best effort: left in place, the same pieces read
            // the same way next time.
            StoreReading.DROP_RECORD -> StoredEntitlement.Nothing.also { runCatching { disk.commit(null) } }
            StoreReading.DROP_KEY -> StoredEntitlement.Nothing.also {
                runCatching {
                    disk.deleteKey()
                    disk.commit(null)
                }
            }
            StoreReading.UNSEAL -> when (val unsealed = SealedEntitlement.unseal(checkNotNull(text), disk.tagger)) {
                is SealedEntitlement.Unsealed.Ok -> StoredEntitlement.Sealed(unsealed.record)
                SealedEntitlement.Unsealed.Tampered -> StoredEntitlement.Tampered
            }
        }
    }

    /** Seal and write [record], making the key first if there is none. False when it did not reach the disk. */
    fun save(disk: EntitlementDisk, record: SealedEntitlement.Record): Boolean {
        if (disk.key() == KeyPresence.ABSENT) {
            disk.markSealing()
            disk.createKey()
        }
        return disk.commit(SealedEntitlement.seal(record, disk.tagger))
    }

    /**
     * Forget the record and its key: the key first, so a crash between reads
     * as no token. A key that cannot be deleted throws and leaves the record,
     * which then still matches it, rather than a key with no record.
     */
    fun clear(disk: EntitlementDisk) {
        disk.deleteKey()
        disk.commit(null)
    }
}
