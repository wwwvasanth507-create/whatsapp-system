package com.campuschat.app.core.crypto

import android.content.Context
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Extended SessionStore interface adding CampusChat-specific device binding methods.
 */
interface CampusChatSessionStore : SessionStore {
    fun getRemoteDeviceIdForAddress(address: SignalProtocolAddress): String?
    fun getRemoteIdentityForAddress(address: SignalProtocolAddress): IdentityKey?
    fun bindRemoteDevice(address: SignalProtocolAddress, remoteDeviceId: String, identityKey: IdentityKey)
    fun clearStore()
}

/**
 * Production-grade encrypted local session persistence backed by CryptoKeyManager (AES-256-GCM master wrapping key).
 */
class SessionStoreImpl(
    private val context: Context?,
    private val cryptoKeyManager: CryptoKeyManager,
    private val customStoreFile: File? = null
) : CampusChatSessionStore {

    private val sessionFile: File = customStoreFile ?: File(context!!.filesDir, SESSION_STORE_FILE_NAME)

    // In-memory cache for fast lookup
    private val sessionMap = ConcurrentHashMap<String, SessionRecord>()
    private val deviceBindingMap = ConcurrentHashMap<String, DeviceBindingInfo>()

    private var boundLocalDeviceId: String? = null

    companion object {
        private const val SESSION_STORE_FILE_NAME = "campuschat_session_store.bin"
        private const val MAGIC_HEADER = 0x43435345 // "CCSE" (CampusChat Session Store)
        private const val CURRENT_VERSION = 1
    }

    private data class DeviceBindingInfo(
        val remoteDeviceId: String,
        val identityKeyBytes: ByteArray
    )

    private fun makeAddressKey(address: SignalProtocolAddress): String {
        return "${address.name}:${address.deviceId}"
    }

    @Synchronized
    fun initStore(localDeviceId: String) {
        require(localDeviceId.isNotBlank()) { "localDeviceId cannot be blank" }
        boundLocalDeviceId = localDeviceId

        if (sessionFile.exists()) {
            loadEncryptedStore(localDeviceId)
        }
    }

    @Synchronized
    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val key = makeAddressKey(address)
        return sessionMap[key] ?: SessionRecord()
    }

    @Synchronized
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> {
        val result = mutableListOf<SessionRecord>()
        for (address in addresses) {
            val key = makeAddressKey(address)
            val record = sessionMap[key]
            if (record != null) {
                result.add(record)
            }
        }
        return result
    }

    @Synchronized
    override fun getSubDeviceSessions(name: String): List<Int> {
        val prefix = "$name:"
        return sessionMap.keys
            .filter { key ->
                val record = sessionMap[key]
                key.startsWith(prefix) && record != null
            }
            .mapNotNull { it.substringAfter(prefix).toIntOrNull() }
    }

    @Synchronized
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        val localDeviceId = boundLocalDeviceId ?: throw CryptoException.CorruptedKeyStateException("SessionStore not initialized with localDeviceId")
        val key = makeAddressKey(address)
        sessionMap[key] = record
        persistEncryptedStore(localDeviceId)
    }

    @Synchronized
    override fun containsSession(address: SignalProtocolAddress): Boolean {
        val key = makeAddressKey(address)
        return sessionMap.containsKey(key)
    }

    @Synchronized
    override fun deleteSession(address: SignalProtocolAddress) {
        val localDeviceId = boundLocalDeviceId ?: return
        val key = makeAddressKey(address)
        sessionMap.remove(key)
        deviceBindingMap.remove(key)
        persistEncryptedStore(localDeviceId)
    }

    @Synchronized
    override fun deleteAllSessions(name: String) {
        val localDeviceId = boundLocalDeviceId ?: return
        val keysToRemove = sessionMap.keys.filter { it.startsWith("$name:") }
        for (key in keysToRemove) {
            sessionMap.remove(key)
            deviceBindingMap.remove(key)
        }
        persistEncryptedStore(localDeviceId)
    }

    @Synchronized
    override fun getRemoteDeviceIdForAddress(address: SignalProtocolAddress): String? {
        val key = makeAddressKey(address)
        return deviceBindingMap[key]?.remoteDeviceId
    }

    @Synchronized
    override fun getRemoteIdentityForAddress(address: SignalProtocolAddress): IdentityKey? {
        val key = makeAddressKey(address)
        val binding = deviceBindingMap[key] ?: return null
        return try {
            IdentityKey(binding.identityKeyBytes, 0)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    override fun bindRemoteDevice(address: SignalProtocolAddress, remoteDeviceId: String, identityKey: IdentityKey) {
        val localDeviceId = boundLocalDeviceId ?: throw CryptoException.CorruptedKeyStateException("SessionStore not initialized with localDeviceId")
        val key = makeAddressKey(address)
        deviceBindingMap[key] = DeviceBindingInfo(
            remoteDeviceId = remoteDeviceId,
            identityKeyBytes = identityKey.serialize()
        )
        persistEncryptedStore(localDeviceId)
    }

    @Synchronized
    override fun clearStore() {
        sessionMap.clear()
        deviceBindingMap.clear()
        boundLocalDeviceId = null
        if (sessionFile.exists()) {
            sessionFile.delete()
        }
    }

    private fun persistEncryptedStore(localDeviceId: String) {
        val localDeviceBytes = localDeviceId.toByteArray(Charsets.UTF_8)
        val keys = sessionMap.keys.toList()

        var sizeNeeded = 4 + 4 + 4 + localDeviceBytes.size + 4
        for (key in keys) {
            val record = sessionMap[key] ?: continue
            val recordBytes = record.serialize()
            val binding = deviceBindingMap[key]
            val remoteDevBytes = binding?.remoteDeviceId?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val identityBytes = binding?.identityKeyBytes ?: ByteArray(0)

            val keyBytes = key.toByteArray(Charsets.UTF_8)
            sizeNeeded += 4 + keyBytes.size + 4 + recordBytes.size + 4 + remoteDevBytes.size + 4 + identityBytes.size
        }

        val buffer = ByteBuffer.allocate(sizeNeeded)
        buffer.putInt(MAGIC_HEADER)
        buffer.putInt(CURRENT_VERSION)
        buffer.putInt(localDeviceBytes.size)
        buffer.put(localDeviceBytes)
        buffer.putInt(keys.size)

        for (key in keys) {
            val record = sessionMap[key] ?: continue
            val recordBytes = record.serialize()
            val binding = deviceBindingMap[key]
            val remoteDevBytes = binding?.remoteDeviceId?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val identityBytes = binding?.identityKeyBytes ?: ByteArray(0)

            val keyBytes = key.toByteArray(Charsets.UTF_8)
            buffer.putInt(keyBytes.size)
            buffer.put(keyBytes)

            buffer.putInt(recordBytes.size)
            buffer.put(recordBytes)

            buffer.putInt(remoteDevBytes.size)
            buffer.put(remoteDevBytes)

            buffer.putInt(identityBytes.size)
            buffer.put(identityBytes)
        }

        val plaintext = buffer.array()
        val blob = cryptoKeyManager.encryptData(plaintext)

        val fileBuffer = ByteBuffer.allocate(4 + blob.iv.size + 4 + blob.ciphertext.size)
        fileBuffer.putInt(blob.iv.size)
        fileBuffer.put(blob.iv)
        fileBuffer.putInt(blob.ciphertext.size)
        fileBuffer.put(blob.ciphertext)

        sessionFile.writeBytes(fileBuffer.array())
    }

    private fun loadEncryptedStore(expectedLocalDeviceId: String) {
        if (!sessionFile.exists()) return
        val fileBytes = sessionFile.readBytes()
        if (fileBytes.size < 8) {
            clearStore()
            return
        }

        try {
            val fileBuffer = ByteBuffer.wrap(fileBytes)
            val ivLen = fileBuffer.int
            if (ivLen <= 0 || ivLen > 256) {
                clearStore()
                return
            }
            val iv = ByteArray(ivLen)
            fileBuffer.get(iv)

            val cipherLen = fileBuffer.int
            if (cipherLen <= 0 || cipherLen > 50 * 1024 * 1024) {
                clearStore()
                return
            }
            val ciphertext = ByteArray(cipherLen)
            fileBuffer.get(ciphertext)

            val decryptedBytes = cryptoKeyManager.decryptData(EncryptedDataBlob(iv, ciphertext))
            val buffer = ByteBuffer.wrap(decryptedBytes)

            val magic = buffer.int
            if (magic != MAGIC_HEADER) {
                clearStore()
                return
            }
            val version = buffer.int
            if (version != CURRENT_VERSION) {
                clearStore()
                return
            }

            val deviceIdLen = buffer.int
            val deviceIdBytes = ByteArray(deviceIdLen)
            buffer.get(deviceIdBytes)
            val storedLocalDevice = String(deviceIdBytes, Charsets.UTF_8)

            if (storedLocalDevice != expectedLocalDeviceId) {
                clearStore()
                return
            }

            val sessionCount = buffer.int
            sessionMap.clear()
            deviceBindingMap.clear()

            for (i in 0 until sessionCount) {
                val keyLen = buffer.int
                val keyBytes = ByteArray(keyLen)
                buffer.get(keyBytes)
                val key = String(keyBytes, Charsets.UTF_8)

                val recLen = buffer.int
                val recBytes = ByteArray(recLen)
                buffer.get(recBytes)
                val record = SessionRecord(recBytes)
                sessionMap[key] = record

                val remoteDevLen = buffer.int
                val remoteDevBytes = ByteArray(remoteDevLen)
                buffer.get(remoteDevBytes)
                val remoteDevId = String(remoteDevBytes, Charsets.UTF_8)

                val idLen = buffer.int
                val idBytes = ByteArray(idLen)
                buffer.get(idBytes)

                if (remoteDevId.isNotBlank() && idBytes.isNotEmpty()) {
                    deviceBindingMap[key] = DeviceBindingInfo(remoteDevId, idBytes)
                }
            }
        } catch (e: Exception) {
            clearStore()
        }
    }
}
