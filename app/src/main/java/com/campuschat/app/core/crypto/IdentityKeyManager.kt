package com.campuschat.app.core.crypto

import android.content.Context
import android.util.Base64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import java.io.File
import java.nio.ByteBuffer

interface IdentityKeyManager {
    fun getOrGenerateIdentity(deviceId: String): IdentityKeyPair
    fun getIdentityKeyPair(): IdentityKeyPair?
    fun getPublicIdentityKey(): IdentityKey?
    fun getPublicIdentityKeyBase64(): String?
    fun hasIdentity(): Boolean
    fun getBoundDeviceId(): String?
}

class IdentityKeyManagerImpl(
    private val context: Context?,
    private val cryptoKeyManager: CryptoKeyManager,
    private val customIdentityFile: File? = null
) : IdentityKeyManager {

    private val identityFile = customIdentityFile ?: File(context!!.filesDir, IDENTITY_FILE_NAME)
    private var cachedIdentityKeyPair: IdentityKeyPair? = null
    private var cachedDeviceId: String? = null

    companion object {
        private const val IDENTITY_FILE_NAME = "campuschat_identity_store.bin"
        private const val MAGIC_HEADER = 0x43434944 // "CCID" (CampusChat Identity)
        private const val CURRENT_VERSION = 1
    }

    @Synchronized
    override fun getOrGenerateIdentity(deviceId: String): IdentityKeyPair {
        require(deviceId.isNotBlank()) { "DeviceId cannot be blank" }

        cachedIdentityKeyPair?.let {
            if (cachedDeviceId == deviceId) return it
        }

        if (identityFile.exists()) {
            try {
                val (identityKeyPair, boundDevice) = loadEncryptedIdentity()
                if (boundDevice == deviceId) {
                    cachedIdentityKeyPair = identityKeyPair
                    cachedDeviceId = boundDevice
                    return identityKeyPair
                }
                cachedIdentityKeyPair = null
                cachedDeviceId = null
                if (identityFile.exists()) {
                    identityFile.delete()
                }
            } catch (e: Exception) {
                if (identityFile.exists()) {
                    identityFile.delete()
                }
            }
        }

        val freshIdentityKeyPair = try {
            IdentityKeyPair.generate()
        } catch (e: Throwable) {
            throw CryptoException.KeyGenerationException(
                "Failed to generate Signal identity keypair", e
            )
        }

        saveEncryptedIdentity(freshIdentityKeyPair, deviceId)

        cachedIdentityKeyPair = freshIdentityKeyPair
        cachedDeviceId = deviceId
        return freshIdentityKeyPair
    }

    @Synchronized
    override fun getIdentityKeyPair(): IdentityKeyPair? {
        if (cachedIdentityKeyPair != null) return cachedIdentityKeyPair
        if (!identityFile.exists()) return null
        return try {
            val (keyPair, boundDevice) = loadEncryptedIdentity()
            cachedIdentityKeyPair = keyPair
            cachedDeviceId = boundDevice
            keyPair
        } catch (e: Exception) {
            null
        }
    }

    override fun getPublicIdentityKey(): IdentityKey? {
        return getIdentityKeyPair()?.publicKey
    }

    override fun getPublicIdentityKeyBase64(): String? {
        val pubKey = getPublicIdentityKey() ?: return null
        val bytes = pubKey.serialize()
        return try {
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            encoded ?: java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    override fun hasIdentity(): Boolean {
        return cachedIdentityKeyPair != null || identityFile.exists()
    }

    override fun getBoundDeviceId(): String? {
        if (cachedDeviceId != null) return cachedDeviceId
        if (!identityFile.exists()) return null
        return try {
            val (_, boundDevice) = loadEncryptedIdentity()
            cachedDeviceId = boundDevice
            boundDevice
        } catch (e: Exception) {
            null
        }
    }

    private fun saveEncryptedIdentity(identityKeyPair: IdentityKeyPair, deviceId: String) {
        val serializedKeyPair = identityKeyPair.serialize()
        val deviceIdBytes = deviceId.toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(4 + 4 + 4 + deviceIdBytes.size + 4 + serializedKeyPair.size)
        buffer.putInt(MAGIC_HEADER)
        buffer.putInt(CURRENT_VERSION)
        buffer.putInt(deviceIdBytes.size)
        buffer.put(deviceIdBytes)
        buffer.putInt(serializedKeyPair.size)
        buffer.put(serializedKeyPair)

        val plaintextBytes = buffer.array()
        val encryptedBlob = cryptoKeyManager.encryptData(plaintextBytes)

        val fileBuffer = ByteBuffer.allocate(4 + encryptedBlob.iv.size + 4 + encryptedBlob.ciphertext.size)
        fileBuffer.putInt(encryptedBlob.iv.size)
        fileBuffer.put(encryptedBlob.iv)
        fileBuffer.putInt(encryptedBlob.ciphertext.size)
        fileBuffer.put(encryptedBlob.ciphertext)

        identityFile.writeBytes(fileBuffer.array())
    }

    private fun loadEncryptedIdentity(): Pair<IdentityKeyPair, String> {
        val fileBytes = identityFile.readBytes()
        if (fileBytes.size < 8) {
            throw CryptoException.CorruptedKeyStateException("Identity file is too small to be valid")
        }

        val fileBuffer = ByteBuffer.wrap(fileBytes)
        val ivLength = fileBuffer.int
        if (ivLength <= 0 || ivLength > 256) {
            throw CryptoException.CorruptedKeyStateException("Invalid IV length in identity file")
        }
        val iv = ByteArray(ivLength)
        fileBuffer.get(iv)

        val cipherLength = fileBuffer.int
        if (cipherLength <= 0 || cipherLength > 65536) {
            throw CryptoException.CorruptedKeyStateException("Invalid ciphertext length in identity file")
        }
        val ciphertext = ByteArray(cipherLength)
        fileBuffer.get(ciphertext)

        val decryptedBytes = cryptoKeyManager.decryptData(EncryptedDataBlob(iv, ciphertext))
        val buffer = ByteBuffer.wrap(decryptedBytes)

        val magic = buffer.int
        if (magic != MAGIC_HEADER) {
            throw CryptoException.CorruptedKeyStateException("Magic header mismatch in identity file")
        }
        val version = buffer.int
        if (version != CURRENT_VERSION) {
            throw CryptoException.CorruptedKeyStateException("Unsupported identity store version: $version")
        }

        val deviceIdLength = buffer.int
        val deviceIdBytes = ByteArray(deviceIdLength)
        buffer.get(deviceIdBytes)
        val boundDevice = String(deviceIdBytes, Charsets.UTF_8)

        val keyPairLength = buffer.int
        val keyPairBytes = ByteArray(keyPairLength)
        buffer.get(keyPairBytes)

        val identityKeyPair = IdentityKeyPair(keyPairBytes)
        return Pair(identityKeyPair, boundDevice)
    }
}
