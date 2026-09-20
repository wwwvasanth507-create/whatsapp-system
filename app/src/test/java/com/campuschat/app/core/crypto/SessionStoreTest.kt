package com.campuschat.app.core.crypto

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SessionRecord
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(JUnit4::class)
class SessionStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storeFile: File
    private lateinit var cryptoKeyManager: CryptoKeyManager
    private lateinit var sessionStore: SessionStoreImpl

    private val localDeviceId = "local-device-uuid-111"
    private val masterKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun testPreKeyBundleSessionBuilder() {
        val aliceIdentity = IdentityKeyPair.generate()
        val aliceDeviceId = "alice-dev-1"
        val aliceRegId = 1

        val bobIdentity = IdentityKeyPair.generate()
        val bobAddress = SignalProtocolAddress("+10000000002", 2)
        val bobSpkKeyPair = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()
        val bobSpkSig = bobIdentity.privateKey.calculateSignature(bobSpkKeyPair.publicKey.serialize())
        val bobOpkKeyPair = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()

        val kemKeyPair = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(org.signal.libsignal.protocol.kem.KEMKeyType.values()[0])
        val dummyKemKey = kemKeyPair.publicKey
        val dummyKemSig = bobIdentity.privateKey.calculateSignature(dummyKemKey.serialize())

        val preKeyBundle = PreKeyBundle(
            2,
            2,
            1,
            bobOpkKeyPair.publicKey,
            1,
            bobSpkKeyPair.publicKey,
            bobSpkSig,
            bobIdentity.publicKey,
            1,
            dummyKemKey,
            dummyKemSig
        )

        val identityKeyManager = IdentityKeyManagerImpl(null, cryptoKeyManager, File(tempFolder.root, "id.bin"))
        identityKeyManager.getOrGenerateIdentity(aliceDeviceId)
        val preKeyManager = PreKeyManagerImpl(null, cryptoKeyManager, identityKeyManager, File(tempFolder.root, "pk.bin"))
        preKeyManager.initializePreKeys(aliceDeviceId)

        val store = CampusChatSignalProtocolStore(
            identityKeyManager = identityKeyManager,
            preKeyManager = preKeyManager,
            sessionStore = sessionStore,
            localDeviceId = aliceDeviceId,
            localRegistrationId = aliceRegId
        )

        val sessionBuilder = org.signal.libsignal.protocol.SessionBuilder(store, bobAddress)
        try {
            sessionBuilder.process(preKeyBundle)
            println("SESSION BUILDER SUCCESS!")
        } catch (e: Throwable) {
            println("SESSION BUILDER ERROR: ${e.javaClass.name}: ${e.message}")
            e.printStackTrace()
        }
    }

    @Before
    fun setUp() {
        storeFile = File(tempFolder.root, "campuschat_session_store.bin")
        cryptoKeyManager = TestCryptoKeyManager(masterKey)
        sessionStore = SessionStoreImpl(null, cryptoKeyManager, storeFile)
        sessionStore.initStore(localDeviceId)
    }

    @After
    fun tearDown() {
        sessionStore.clearStore()
    }

    @Test
    fun testStoreAndLoadSession() {
        val address = SignalProtocolAddress("user-bob", 1)
        val record = SessionRecord()

        sessionStore.storeSession(address, record)

        assertTrue(sessionStore.containsSession(address))
        val loaded = sessionStore.loadSession(address)
        assertNotNull(loaded)
    }

    @Test
    fun testSessionPersistenceAndReload() {
        val address = SignalProtocolAddress("user-alice", 2)
        val record = SessionRecord()
        val remoteIdentity = IdentityKeyPair.generate().publicKey

        sessionStore.storeSession(address, record)
        sessionStore.bindRemoteDevice(address, "device-b1", remoteIdentity)

        // Instantiate new store instance reading same file (simulating process restart)
        val newStore = SessionStoreImpl(null, cryptoKeyManager, storeFile)
        newStore.initStore(localDeviceId)

        assertTrue(newStore.containsSession(address))
        assertEquals("device-b1", newStore.getRemoteDeviceIdForAddress(address))
        assertEquals(remoteIdentity, newStore.getRemoteIdentityForAddress(address))
    }

    @Test
    fun testDeviceBindingMismatchFailsClosed() {
        val address = SignalProtocolAddress("user-charlie", 3)
        val record = SessionRecord()

        sessionStore.storeSession(address, record)

        // Opening store with DIFFERENT local device ID must clear store and fail closed!
        val wrongDeviceStore = SessionStoreImpl(null, cryptoKeyManager, storeFile)
        wrongDeviceStore.initStore("different-device-id-999")

        assertFalse(wrongDeviceStore.containsSession(address))
    }

    @Test
    fun testMultiDeviceSeparateSessions() {
        val addressB1 = SignalProtocolAddress("user-bob", 101)
        val addressB2 = SignalProtocolAddress("user-bob", 102)

        val recordB1 = SessionRecord()
        val recordB2 = SessionRecord()

        sessionStore.storeSession(addressB1, recordB1)
        sessionStore.storeSession(addressB2, recordB2)

        assertTrue(sessionStore.containsSession(addressB1))
        assertTrue(sessionStore.containsSession(addressB2))

        val existing = sessionStore.loadExistingSessions(listOf(addressB1, addressB2))
        assertEquals(2, existing.size)
    }

    @Test
    fun testCorruptedStoreFailsSafely() {
        val address = SignalProtocolAddress("user-david", 4)
        sessionStore.storeSession(address, SessionRecord())

        // Corrupt file
        storeFile.writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))

        val corruptedStore = SessionStoreImpl(null, cryptoKeyManager, storeFile)
        corruptedStore.initStore(localDeviceId)

        assertFalse(corruptedStore.containsSession(address))
    }

    private class TestCryptoKeyManager(private val secretKey: SecretKey) : CryptoKeyManager {
        override fun encryptData(plaintext: ByteArray): EncryptedDataBlob {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            return EncryptedDataBlob(iv, ciphertext)
        }

        override fun decryptData(encryptedBlob: EncryptedDataBlob): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.GCMParameterSpec(128, encryptedBlob.iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, spec)
            return cipher.doFinal(encryptedBlob.ciphertext)
        }

        override fun getSecurityLevel(): SecurityLevel = SecurityLevel.SOFTWARE
        override fun hasMasterKey(): Boolean = true
    }
}
