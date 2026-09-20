package com.campuschat.app.core.crypto

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Integrated SignalProtocolStore implementing identity, prekey, signed prekey, session store, sender key store, and kyber prekey store
 * for org.signal:libsignal-android:0.86.5.
 */
class CampusChatSignalProtocolStore(
    private val identityKeyManager: IdentityKeyManager,
    private val preKeyManager: PreKeyManager,
    private val sessionStore: CampusChatSessionStore,
    private val localDeviceId: String,
    private val localRegistrationId: Int
) : SignalProtocolStore {

    // Remote identity store mapping address -> IdentityKey
    private val trustedIdentities = ConcurrentHashMap<String, IdentityKey>()

    private fun makeAddressKey(address: SignalProtocolAddress): String {
        return "${address.name}:${address.deviceId}"
    }

    // --- IdentityKeyStore Implementation ---

    override fun getIdentityKeyPair(): IdentityKeyPair {
        return identityKeyManager.getIdentityKeyPair()
            ?: identityKeyManager.getOrGenerateIdentity(localDeviceId)
    }

    override fun getLocalRegistrationId(): Int {
        return localRegistrationId
    }

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val key = makeAddressKey(address)
        val existing = trustedIdentities[key] ?: sessionStore.getRemoteIdentityForAddress(address)

        val enumValues = IdentityKeyStore.IdentityChange.values()
        val replacedEnum = enumValues.firstOrNull { it.name.contains("REPLACED") } ?: enumValues.last()
        val newEnum = enumValues.firstOrNull { it.name == "NEW" || it.name.contains("NEW") } ?: enumValues.first()
        val unchangedEnum = enumValues.firstOrNull { it.name.contains("UNCHANGED") } ?: enumValues.first()

        if (existing != null && existing != identityKey) {
            return replacedEnum
        }

        val isNew = existing == null
        trustedIdentities[key] = identityKey
        val remoteDeviceId = sessionStore.getRemoteDeviceIdForAddress(address) ?: address.deviceId.toString()
        sessionStore.bindRemoteDevice(address, remoteDeviceId, identityKey)
        return if (isNew) newEnum else unchangedEnum
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val key = makeAddressKey(address)
        val existing = trustedIdentities[key] ?: sessionStore.getRemoteIdentityForAddress(address)

        if (existing == null) {
            return true
        }

        return existing == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val key = makeAddressKey(address)
        return trustedIdentities[key] ?: sessionStore.getRemoteIdentityForAddress(address)
    }

    // --- PreKeyStore Implementation ---

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val availableOpks = preKeyManager.getAvailableOneTimePreKeys(localDeviceId)
        val record = availableOpks.firstOrNull { it.id == preKeyId }
            ?: preKeyManager.consumeLocalOneTimePreKey(localDeviceId, preKeyId)
            ?: throw CryptoException.CorruptedKeyStateException("One-time prekey $preKeyId not found locally")
        return record
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        // Managed locally by PreKeyManager
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return preKeyManager.hasOneTimePreKey(localDeviceId, preKeyId)
    }

    override fun removePreKey(preKeyId: Int) {
        preKeyManager.consumeLocalOneTimePreKey(localDeviceId, preKeyId)
    }

    // --- SignedPreKeyStore Implementation ---

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val record = preKeyManager.getSignedPreKey(localDeviceId, signedPreKeyId)
            ?: throw CryptoException.CorruptedKeyStateException("Signed prekey $signedPreKeyId not found locally")
        return record
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        val currentSpk = preKeyManager.getCurrentSignedPreKey(localDeviceId)
        return if (currentSpk != null) listOf(currentSpk) else emptyList()
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        // Managed locally by PreKeyManager
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return preKeyManager.hasSignedPreKey(localDeviceId, signedPreKeyId)
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        // Signed prekeys are not removed on load
    }

    // --- SessionStore Implementation ---

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        return sessionStore.loadSession(address)
    }

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> {
        return sessionStore.loadExistingSessions(addresses)
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        return sessionStore.getSubDeviceSessions(name)
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        sessionStore.storeSession(address, record)
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return sessionStore.containsSession(address)
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        val key = makeAddressKey(address)
        trustedIdentities.remove(key)
        sessionStore.deleteSession(address)
    }

    override fun deleteAllSessions(name: String) {
        trustedIdentities.keys.filter { it.startsWith("$name:") }.forEach { trustedIdentities.remove(it) }
        sessionStore.deleteAllSessions(name)
    }

    // --- SenderKeyStore Implementation ---

    private val senderKeys = ConcurrentHashMap<String, SenderKeyRecord>()

    override fun storeSenderKey(address: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        val key = "${address.name}:${address.deviceId}:$distributionId"
        senderKeys[key] = record
    }

    override fun loadSenderKey(address: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? {
        val key = "${address.name}:${address.deviceId}:$distributionId"
        return senderKeys[key]
    }

    // --- KyberPreKeyStore Implementation ---

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        throw CryptoException.CorruptedKeyStateException("KyberPreKey $kyberPreKeyId not supported in classic X3DH")
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        return emptyList()
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
        return false
    }

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
    }
}
