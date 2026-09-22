package com.campuschat.app.core.crypto

import android.content.Context
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.io.File

interface PreKeyManager {
    fun initializePreKeys(deviceId: String): PreKeyInitializationResult
    fun getCurrentSignedPreKey(deviceId: String): SignedPreKeyRecord?
    fun getSignedPreKey(deviceId: String, keyId: Int): SignedPreKeyRecord?
    fun rotateSignedPreKey(deviceId: String): SignedPreKeyRecord
    fun getCurrentKyberPreKey(deviceId: String): KyberPreKeyRecord?
    fun getKyberPreKey(deviceId: String, keyId: Int): KyberPreKeyRecord?
    fun getAvailableOneTimePreKeyCount(deviceId: String): Int
    fun getAvailableOneTimePreKeys(deviceId: String): List<PreKeyRecord>
    fun replenishOneTimePreKeys(deviceId: String): Int
    fun consumeLocalOneTimePreKey(deviceId: String, keyId: Int): PreKeyRecord?
    fun hasSignedPreKey(deviceId: String, keyId: Int): Boolean
    fun hasKyberPreKey(deviceId: String, keyId: Int): Boolean
    fun hasOneTimePreKey(deviceId: String, keyId: Int): Boolean
    fun clearPreKeys()
}

class PreKeyManagerImpl(
    private val context: Context?,
    private val cryptoKeyManager: CryptoKeyManager,
    private val identityKeyManager: IdentityKeyManager,
    customStoreFile: File? = null
) : PreKeyManager {

    private val preKeyStore = PreKeyStore(context, cryptoKeyManager, customStoreFile)

    // Configuration Constants
    companion object {
        const val INITIAL_ONE_TIME_PREKEY_COUNT = 100
        const val LOW_WATERMARK = 20
        const val REPLENISH_COUNT = 50
        const val DEFAULT_SIGNED_PREKEY_ROTATION_INTERVAL_MS = 14 * 24 * 60 * 60 * 1000L // 14 days
        const val DEFAULT_SIGNED_PREKEY_RETENTION_MS = 30 * 24 * 60 * 60 * 1000L // 30 days
    }

    private var cachedPayload: PreKeyStorePayload? = null

    @Synchronized
    private fun getOrLoadPayload(deviceId: String): PreKeyStorePayload {
        require(deviceId.isNotBlank()) { "DeviceId cannot be blank" }

        cachedPayload?.let { payload ->
            if (payload.deviceId == deviceId) {
                return payload
            }
        }

        val loaded = preKeyStore.loadPayload(deviceId)
        if (loaded != null && loaded.deviceId == deviceId) {
            if (loaded.kyberPreKeys.isEmpty()) {
                val identityKeyPair = identityKeyManager.getOrGenerateIdentity(deviceId)
                val timestamp = System.currentTimeMillis()
                val kemKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
                val kyberSig = identityKeyPair.privateKey.calculateSignature(kemKeyPair.publicKey.serialize())
                val initialKyberRecord = KyberPreKeyRecord(1, timestamp, kemKeyPair, kyberSig)
                val storedKyber = StoredKyberPreKey(
                    id = 1,
                    timestamp = timestamp,
                    isCurrent = true,
                    recordBytes = initialKyberRecord.serialize()
                )
                val updatedLoaded = loaded.copy(
                    lastKyberPreKeyId = 1,
                    kyberPreKeys = listOf(storedKyber)
                )
                preKeyStore.savePayload(updatedLoaded)
                cachedPayload = updatedLoaded
                return updatedLoaded
            }
            cachedPayload = loaded
            return loaded
        }

        val freshPayload = generateFreshPreKeyPayload(deviceId)
        preKeyStore.savePayload(freshPayload)
        cachedPayload = freshPayload
        return freshPayload
    }

    private fun generateFreshPreKeyPayload(deviceId: String): PreKeyStorePayload {
        val identityKeyPair = identityKeyManager.getOrGenerateIdentity(deviceId)
        val timestamp = System.currentTimeMillis()

        // 1. Generate Initial SignedPreKey (ID = 1)
        val initialSpkKeyPair = ECKeyPair.generate()
        val signature = identityKeyPair.privateKey.calculateSignature(initialSpkKeyPair.publicKey.serialize())
        val initialSpkRecord = SignedPreKeyRecord(1, timestamp, initialSpkKeyPair, signature)
        val storedSpk = StoredSignedPreKey(
            id = 1,
            timestamp = timestamp,
            isCurrent = true,
            recordBytes = initialSpkRecord.serialize()
        )

        // 2. Generate Initial KyberPreKey (ID = 1)
        val kemKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSig = identityKeyPair.privateKey.calculateSignature(kemKeyPair.publicKey.serialize())
        val initialKyberRecord = KyberPreKeyRecord(1, timestamp, kemKeyPair, kyberSig)
        val storedKyber = StoredKyberPreKey(
            id = 1,
            timestamp = timestamp,
            isCurrent = true,
            recordBytes = initialKyberRecord.serialize()
        )

        // 3. Generate Initial Pool of OneTimePreKeys (IDs = 1..100)
        val storedOpks = ArrayList<StoredOneTimePreKey>(INITIAL_ONE_TIME_PREKEY_COUNT)
        for (i in 1..INITIAL_ONE_TIME_PREKEY_COUNT) {
            val opkKeyPair = ECKeyPair.generate()
            val opkRecord = PreKeyRecord(i, opkKeyPair)
            storedOpks.add(
                StoredOneTimePreKey(
                    id = i,
                    timestamp = timestamp,
                    isConsumed = false,
                    consumedTimestamp = 0L,
                    recordBytes = opkRecord.serialize()
                )
            )
        }

        return PreKeyStorePayload(
            deviceId = deviceId,
            lastSignedPreKeyId = 1,
            lastOneTimePreKeyId = INITIAL_ONE_TIME_PREKEY_COUNT,
            lastKyberPreKeyId = 1,
            signedPreKeys = listOf(storedSpk),
            kyberPreKeys = listOf(storedKyber),
            oneTimePreKeys = storedOpks
        )
    }

    @Synchronized
    override fun initializePreKeys(deviceId: String): PreKeyInitializationResult {
        val payload = getOrLoadPayload(deviceId)
        val currentSpk = payload.signedPreKeys.firstOrNull { it.isCurrent }?.toRecord()
            ?: payload.signedPreKeys.first().toRecord()
        val availableOpkCount = payload.oneTimePreKeys.count { !it.isConsumed }
        return PreKeyInitializationResult(
            signedPreKey = currentSpk,
            initialOneTimePreKeyCount = availableOpkCount
        )
    }

    @Synchronized
    override fun getCurrentSignedPreKey(deviceId: String): SignedPreKeyRecord? {
        val payload = getOrLoadPayload(deviceId)
        return payload.signedPreKeys.firstOrNull { it.isCurrent }?.toRecord()
            ?: payload.signedPreKeys.lastOrNull()?.toRecord()
    }

    @Synchronized
    override fun getSignedPreKey(deviceId: String, keyId: Int): SignedPreKeyRecord? {
        val payload = getOrLoadPayload(deviceId)
        return payload.signedPreKeys.firstOrNull { it.id == keyId }?.toRecord()
    }

    @Synchronized
    override fun rotateSignedPreKey(deviceId: String): SignedPreKeyRecord {
        val payload = getOrLoadPayload(deviceId)
        val identityKeyPair = identityKeyManager.getOrGenerateIdentity(deviceId)
        val timestamp = System.currentTimeMillis()
        val newSpkId = payload.lastSignedPreKeyId + 1

        val newEcKeyPair = ECKeyPair.generate()
        val signature = identityKeyPair.privateKey.calculateSignature(newEcKeyPair.publicKey.serialize())
        val newSpkRecord = SignedPreKeyRecord(newSpkId, timestamp, newEcKeyPair, signature)

        val updatedSpks = payload.signedPreKeys.map { existing ->
            existing.copy(isCurrent = false)
        }.toMutableList()

        val newStoredSpk = StoredSignedPreKey(
            id = newSpkId,
            timestamp = timestamp,
            isCurrent = true,
            recordBytes = newSpkRecord.serialize()
        )
        updatedSpks.add(newStoredSpk)

        val retentionCutoff = timestamp - DEFAULT_SIGNED_PREKEY_RETENTION_MS
        val filteredSpks = updatedSpks.filter { it.isCurrent || it.timestamp >= retentionCutoff }

        val newPayload = payload.copy(
            lastSignedPreKeyId = newSpkId,
            signedPreKeys = filteredSpks
        )

        preKeyStore.savePayload(newPayload)
        cachedPayload = newPayload

        return newSpkRecord
    }

    @Synchronized
    override fun getCurrentKyberPreKey(deviceId: String): KyberPreKeyRecord? {
        val payload = getOrLoadPayload(deviceId)
        return payload.kyberPreKeys.firstOrNull { it.isCurrent }?.toRecord()
            ?: payload.kyberPreKeys.lastOrNull()?.toRecord()
    }

    @Synchronized
    override fun getKyberPreKey(deviceId: String, keyId: Int): KyberPreKeyRecord? {
        val payload = getOrLoadPayload(deviceId)
        return payload.kyberPreKeys.firstOrNull { it.id == keyId }?.toRecord()
    }

    @Synchronized
    override fun getAvailableOneTimePreKeyCount(deviceId: String): Int {
        val payload = getOrLoadPayload(deviceId)
        return payload.oneTimePreKeys.count { !it.isConsumed }
    }

    @Synchronized
    override fun getAvailableOneTimePreKeys(deviceId: String): List<PreKeyRecord> {
        val payload = getOrLoadPayload(deviceId)
        return payload.oneTimePreKeys
            .filter { !it.isConsumed }
            .map { it.toRecord() }
    }

    @Synchronized
    override fun replenishOneTimePreKeys(deviceId: String): Int {
        val payload = getOrLoadPayload(deviceId)
        val currentAvailableCount = payload.oneTimePreKeys.count { !it.isConsumed }

        if (currentAvailableCount > LOW_WATERMARK) {
            return 0
        }

        val timestamp = System.currentTimeMillis()
        var nextId = payload.lastOneTimePreKeyId
        val newOpks = ArrayList<StoredOneTimePreKey>(REPLENISH_COUNT)

        for (i in 1..REPLENISH_COUNT) {
            nextId++
            val ecKeyPair = ECKeyPair.generate()
            val opkRecord = PreKeyRecord(nextId, ecKeyPair)
            newOpks.add(
                StoredOneTimePreKey(
                    id = nextId,
                    timestamp = timestamp,
                    isConsumed = false,
                    consumedTimestamp = 0L,
                    recordBytes = opkRecord.serialize()
                )
            )
        }

        val combinedOpks = payload.oneTimePreKeys + newOpks
        val newPayload = payload.copy(
            lastOneTimePreKeyId = nextId,
            oneTimePreKeys = combinedOpks
        )

        preKeyStore.savePayload(newPayload)
        cachedPayload = newPayload

        return REPLENISH_COUNT
    }

    @Synchronized
    override fun consumeLocalOneTimePreKey(deviceId: String, keyId: Int): PreKeyRecord? {
        val payload = getOrLoadPayload(deviceId)
        val targetOpk = payload.oneTimePreKeys.firstOrNull { it.id == keyId } ?: return null

        if (targetOpk.isConsumed) {
            return null
        }

        val timestamp = System.currentTimeMillis()
        val updatedOpks = payload.oneTimePreKeys.map { opk ->
            if (opk.id == keyId) {
                opk.copy(isConsumed = true, consumedTimestamp = timestamp)
            } else {
                opk
            }
        }

        val newPayload = payload.copy(oneTimePreKeys = updatedOpks)
        preKeyStore.savePayload(newPayload)
        cachedPayload = newPayload

        return targetOpk.toRecord()
    }

    @Synchronized
    override fun hasSignedPreKey(deviceId: String, keyId: Int): Boolean {
        val payload = getOrLoadPayload(deviceId)
        return payload.signedPreKeys.any { it.id == keyId }
    }

    @Synchronized
    override fun hasKyberPreKey(deviceId: String, keyId: Int): Boolean {
        val payload = getOrLoadPayload(deviceId)
        return payload.kyberPreKeys.any { it.id == keyId }
    }

    @Synchronized
    override fun hasOneTimePreKey(deviceId: String, keyId: Int): Boolean {
        val payload = getOrLoadPayload(deviceId)
        return payload.oneTimePreKeys.any { it.id == keyId }
    }

    @Synchronized
    override fun clearPreKeys() {
        cachedPayload = null
        preKeyStore.clearStore()
    }
}

