package com.campuschat.app.core.crypto

import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

/**
 * Metadata & domain representation for initialization result.
 */
data class PreKeyInitializationResult(
    val signedPreKey: SignedPreKeyRecord,
    val initialOneTimePreKeyCount: Int
)

/**
 * Internal container for persisted SignedPreKeys.
 */
internal data class StoredSignedPreKey(
    val id: Int,
    val timestamp: Long,
    val isCurrent: Boolean,
    val recordBytes: ByteArray
) {
    fun toRecord(): SignedPreKeyRecord = SignedPreKeyRecord(recordBytes)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StoredSignedPreKey
        if (id != other.id) return false
        if (timestamp != other.timestamp) return false
        if (isCurrent != other.isCurrent) return false
        if (!recordBytes.contentEquals(other.recordBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isCurrent.hashCode()
        result = 31 * result + recordBytes.contentHashCode()
        return result
    }
}

/**
 * Internal container for persisted OneTimePreKeys.
 */
internal data class StoredOneTimePreKey(
    val id: Int,
    val timestamp: Long,
    val isConsumed: Boolean,
    val consumedTimestamp: Long,
    val recordBytes: ByteArray
) {
    fun toRecord(): PreKeyRecord = PreKeyRecord(recordBytes)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StoredOneTimePreKey
        if (id != other.id) return false
        if (timestamp != other.timestamp) return false
        if (isConsumed != other.isConsumed) return false
        if (consumedTimestamp != other.consumedTimestamp) return false
        if (!recordBytes.contentEquals(other.recordBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + isConsumed.hashCode()
        result = 31 * result + consumedTimestamp.hashCode()
        result = 31 * result + recordBytes.contentHashCode()
        return result
    }
}
