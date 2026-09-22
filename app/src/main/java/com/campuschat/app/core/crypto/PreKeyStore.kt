package com.campuschat.app.core.crypto

import android.content.Context
import java.io.File
import java.nio.ByteBuffer

internal data class PreKeyStorePayload(
    val deviceId: String,
    val lastSignedPreKeyId: Int,
    val lastOneTimePreKeyId: Int,
    val lastKyberPreKeyId: Int = 1,
    val signedPreKeys: List<StoredSignedPreKey>,
    val kyberPreKeys: List<StoredKyberPreKey> = emptyList(),
    val oneTimePreKeys: List<StoredOneTimePreKey>
)

internal class PreKeyStore(
    private val context: Context?,
    private val cryptoKeyManager: CryptoKeyManager,
    private val customStoreFile: File? = null
) {

    private val storeFile = customStoreFile ?: File(context!!.filesDir, PREKEY_STORE_FILE_NAME)

    companion object {
        private const val PREKEY_STORE_FILE_NAME = "campuschat_prekey_store.bin"
        private const val MAGIC_HEADER = 0x4343504B // "CCPK"
        private const val CURRENT_VERSION = 1
    }

    @Synchronized
    fun exists(): Boolean = storeFile.exists()

    @Synchronized
    fun clearStore() {
        if (storeFile.exists()) {
            storeFile.delete()
        }
    }

    @Synchronized
    fun savePayload(payload: PreKeyStorePayload) {
        val deviceIdBytes = payload.deviceId.toByteArray(Charsets.UTF_8)

        // Calculate size for buffer allocation
        var totalBytesNeeded = 4 + 4 + 4 + deviceIdBytes.size + 4 + 4 + 4 + 4
        payload.signedPreKeys.forEach { spk ->
            totalBytesNeeded += 4 + 8 + 1 + 4 + spk.recordBytes.size
        }
        totalBytesNeeded += 4
        payload.kyberPreKeys.forEach { kpk ->
            totalBytesNeeded += 4 + 8 + 1 + 4 + kpk.recordBytes.size
        }
        totalBytesNeeded += 4
        payload.oneTimePreKeys.forEach { opk ->
            totalBytesNeeded += 4 + 8 + 1 + 8 + 4 + opk.recordBytes.size
        }

        val buffer = ByteBuffer.allocate(totalBytesNeeded)
        buffer.putInt(MAGIC_HEADER)
        buffer.putInt(CURRENT_VERSION)
        buffer.putInt(deviceIdBytes.size)
        buffer.put(deviceIdBytes)
        buffer.putInt(payload.lastSignedPreKeyId)
        buffer.putInt(payload.lastOneTimePreKeyId)
        buffer.putInt(payload.lastKyberPreKeyId)

        // Write SignedPreKeys
        buffer.putInt(payload.signedPreKeys.size)
        payload.signedPreKeys.forEach { spk ->
            buffer.putInt(spk.id)
            buffer.putLong(spk.timestamp)
            buffer.put(if (spk.isCurrent) 1.toByte() else 0.toByte())
            buffer.putInt(spk.recordBytes.size)
            buffer.put(spk.recordBytes)
        }

        // Write KyberPreKeys
        buffer.putInt(payload.kyberPreKeys.size)
        payload.kyberPreKeys.forEach { kpk ->
            buffer.putInt(kpk.id)
            buffer.putLong(kpk.timestamp)
            buffer.put(if (kpk.isCurrent) 1.toByte() else 0.toByte())
            buffer.putInt(kpk.recordBytes.size)
            buffer.put(kpk.recordBytes)
        }

        // Write OneTimePreKeys
        buffer.putInt(payload.oneTimePreKeys.size)
        payload.oneTimePreKeys.forEach { opk ->
            buffer.putInt(opk.id)
            buffer.putLong(opk.timestamp)
            buffer.put(if (opk.isConsumed) 1.toByte() else 0.toByte())
            buffer.putLong(opk.consumedTimestamp)
            buffer.putInt(opk.recordBytes.size)
            buffer.put(opk.recordBytes)
        }

        val plaintextBytes = buffer.array()
        val encryptedBlob = cryptoKeyManager.encryptData(plaintextBytes)

        val fileBuffer = ByteBuffer.allocate(4 + encryptedBlob.iv.size + 4 + encryptedBlob.ciphertext.size)
        fileBuffer.putInt(encryptedBlob.iv.size)
        fileBuffer.put(encryptedBlob.iv)
        fileBuffer.putInt(encryptedBlob.ciphertext.size)
        fileBuffer.put(encryptedBlob.ciphertext)

        storeFile.writeBytes(fileBuffer.array())
    }

    @Synchronized
    fun loadPayload(expectedDeviceId: String): PreKeyStorePayload? {
        if (!storeFile.exists()) return null
        val fileBytes = storeFile.readBytes()
        if (fileBytes.size < 8) {
            clearStore()
            return null
        }

        try {
            val fileBuffer = ByteBuffer.wrap(fileBytes)
            val ivLength = fileBuffer.int
            if (ivLength <= 0 || ivLength > 256) {
                clearStore()
                return null
            }
            val iv = ByteArray(ivLength)
            fileBuffer.get(iv)

            val cipherLength = fileBuffer.int
            if (cipherLength <= 0 || cipherLength > 10 * 1024 * 1024) { // 10MB sanity cap
                clearStore()
                return null
            }
            val ciphertext = ByteArray(cipherLength)
            fileBuffer.get(ciphertext)

            val decryptedBytes = cryptoKeyManager.decryptData(EncryptedDataBlob(iv, ciphertext))
            val buffer = ByteBuffer.wrap(decryptedBytes)

            val magic = buffer.int
            if (magic != MAGIC_HEADER) {
                clearStore()
                return null
            }
            val version = buffer.int
            if (version != CURRENT_VERSION) {
                clearStore()
                return null
            }

            val deviceIdLength = buffer.int
            val deviceIdBytes = ByteArray(deviceIdLength)
            buffer.get(deviceIdBytes)
            val boundDevice = String(deviceIdBytes, Charsets.UTF_8)

            // Device Binding Verification: If stored device ID != current device ID, clear store!
            if (boundDevice != expectedDeviceId) {
                clearStore()
                return null
            }

            val lastSignedPreKeyId = buffer.int
            val lastOneTimePreKeyId = buffer.int
            val lastKyberPreKeyId = if (buffer.hasRemaining()) buffer.int else 1

            val spkCount = buffer.int
            val signedPreKeys = ArrayList<StoredSignedPreKey>(spkCount)
            for (i in 0 until spkCount) {
                val id = buffer.int
                val timestamp = buffer.long
                val isCurrent = buffer.get() == 1.toByte()
                val recordLen = buffer.int
                val recordBytes = ByteArray(recordLen)
                buffer.get(recordBytes)
                signedPreKeys.add(StoredSignedPreKey(id, timestamp, isCurrent, recordBytes))
            }

            val kyberCount = if (buffer.hasRemaining()) buffer.int else 0
            val kyberPreKeys = ArrayList<StoredKyberPreKey>(kyberCount)
            for (i in 0 until kyberCount) {
                val id = buffer.int
                val timestamp = buffer.long
                val isCurrent = buffer.get() == 1.toByte()
                val recordLen = buffer.int
                val recordBytes = ByteArray(recordLen)
                buffer.get(recordBytes)
                kyberPreKeys.add(StoredKyberPreKey(id, timestamp, isCurrent, recordBytes))
            }

            val opkCount = buffer.int
            val oneTimePreKeys = ArrayList<StoredOneTimePreKey>(opkCount)
            for (i in 0 until opkCount) {
                val id = buffer.int
                val timestamp = buffer.long
                val isConsumed = buffer.get() == 1.toByte()
                val consumedTimestamp = buffer.long
                val recordLen = buffer.int
                val recordBytes = ByteArray(recordLen)
                buffer.get(recordBytes)
                oneTimePreKeys.add(StoredOneTimePreKey(id, timestamp, isConsumed, consumedTimestamp, recordBytes))
            }

            return PreKeyStorePayload(
                deviceId = boundDevice,
                lastSignedPreKeyId = lastSignedPreKeyId,
                lastOneTimePreKeyId = lastOneTimePreKeyId,
                lastKyberPreKeyId = lastKyberPreKeyId,
                signedPreKeys = signedPreKeys,
                kyberPreKeys = kyberPreKeys,
                oneTimePreKeys = oneTimePreKeys
            )
        } catch (e: Exception) {
            clearStore()
            return null
        }
    }
}
