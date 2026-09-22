package com.campuschat.app.domain.service

import com.campuschat.app.core.crypto.CampusChatSessionStore
import com.campuschat.app.core.crypto.CampusChatSignalProtocolStore
import com.campuschat.app.core.crypto.CryptoKeyManager
import com.campuschat.app.core.crypto.EncryptedDataBlob
import com.campuschat.app.core.crypto.IdentityKeyManagerImpl
import com.campuschat.app.core.crypto.PreKeyManagerImpl
import com.campuschat.app.core.crypto.SecurityLevel
import com.campuschat.app.core.crypto.SessionStoreImpl
import com.campuschat.app.domain.model.RemotePreKeyBundle
import com.campuschat.app.domain.model.X3DHSessionResult
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.PreKeySyncRepository
import io.github.jan.supabase.gotrue.user.UserInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(JUnit4::class)
class KyberPreKeyProvisioningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var identityFile: File
    private lateinit var preKeyFile: File
    private lateinit var sessionFile: File

    private lateinit var cryptoKeyManager: CryptoKeyManager
    private lateinit var identityKeyManager: IdentityKeyManagerImpl
    private lateinit var preKeyManager: PreKeyManagerImpl
    private lateinit var sessionStore: SessionStoreImpl
    private lateinit var protocolStore: CampusChatSignalProtocolStore

    private val aliceDeviceId = "alice-dev-001"
    private val aliceUserId = "+10000000001"
    private val aliceRegId = 1001

    private val masterKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        identityFile = File(tempFolder.root, "identity.bin")
        preKeyFile = File(tempFolder.root, "prekey.bin")
        sessionFile = File(tempFolder.root, "session.bin")

        cryptoKeyManager = TestCryptoKeyManager(masterKey)
        identityKeyManager = IdentityKeyManagerImpl(null, cryptoKeyManager, identityFile)
        identityKeyManager.getOrGenerateIdentity(aliceDeviceId)

        preKeyManager = PreKeyManagerImpl(null, cryptoKeyManager, identityKeyManager, preKeyFile)
        preKeyManager.initializePreKeys(aliceDeviceId)

        sessionStore = SessionStoreImpl(null, cryptoKeyManager, sessionFile)
        sessionStore.initStore(aliceDeviceId)

        protocolStore = CampusChatSignalProtocolStore(
            identityKeyManager = identityKeyManager,
            preKeyManager = preKeyManager,
            sessionStore = sessionStore,
            localDeviceId = aliceDeviceId,
            localRegistrationId = aliceRegId
        )
    }

    @Test
    fun testPreKeyManagerGeneratesAndPersistsKyberPreKey() {
        val kyberPreKey = preKeyManager.getCurrentKyberPreKey(aliceDeviceId)
        assertNotNull("PreKeyManager should generate current KyberPreKey", kyberPreKey)
        assertEquals(1, kyberPreKey!!.id)

        val retrievedKyber = preKeyManager.getKyberPreKey(aliceDeviceId, 1)
        assertNotNull("PreKeyManager should retrieve KyberPreKey by ID", retrievedKyber)
        assertArrayEquals(kyberPreKey.keyPair.publicKey.serialize(), retrievedKyber!!.keyPair.publicKey.serialize())

        val identityKeyPair = identityKeyManager.getIdentityKeyPair()!!
        val signatureValid = identityKeyPair.publicKey.publicKey.verifySignature(
            kyberPreKey.keyPair.publicKey.serialize(),
            kyberPreKey.signature
        )
        assertTrue("KyberPreKey signature should be validly signed by IdentityKeyPair", signatureValid)
    }

    @Test
    fun testX3DHSessionServiceEstablishesPQX3DHSession() {
        val bobIdentityKeyPair = IdentityKeyPair.generate()
        val bobSpkKeyPair = ECKeyPair.generate()
        val bobSpkSignature = bobIdentityKeyPair.privateKey.calculateSignature(bobSpkKeyPair.publicKey.serialize())

        val bobKemKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val bobKemSignature = bobIdentityKeyPair.privateKey.calculateSignature(bobKemKeyPair.publicKey.serialize())

        val bobUserId = "+10000000002"
        val bobDeviceId = "bob-dev-002"
        val bobRegId = 2

        val bundle = RemotePreKeyBundle(
            deviceId = bobDeviceId,
            userId = bobUserId,
            registrationId = bobRegId,
            identityPublicKeyBase64 = encodeBase64(bobIdentityKeyPair.publicKey.serialize()),
            signedPreKeyId = 1,
            signedPreKeyBase64 = encodeBase64(bobSpkKeyPair.publicKey.serialize()),
            signedPreKeySignatureBase64 = encodeBase64(bobSpkSignature),
            oneTimePreKeyId = null,
            oneTimePreKeyBase64 = null,
            kyberPreKeyId = 1,
            kyberPreKeyBase64 = encodeBase64(bobKemKeyPair.publicKey.serialize()),
            kyberPreKeySignatureBase64 = encodeBase64(bobKemSignature)
        )

        val fakeAuthRepository = FakeAuthRepo()
        val fakePreKeySyncRepository = FakeSyncRepo(bundle)
        val sessionService = X3DHSessionServiceImpl(
            authRepository = fakeAuthRepository,
            preKeySyncRepository = fakePreKeySyncRepository,
            protocolStore = protocolStore,
            sessionStore = sessionStore
        )

        val result = sessionService.processAndEstablishSession(bundle)
        assertTrue("PQX3DH session establishment should succeed: $result", result is X3DHSessionResult.SessionEstablished)

        val bobAddress = SignalProtocolAddress(bobUserId, bobRegId)
        assertTrue("Session store should contain session for Bob", sessionStore.containsSession(bobAddress))
    }

    @Test
    fun testX3DHSessionServiceRejectsInvalidKyberSignature() {
        val bobIdentityKeyPair = IdentityKeyPair.generate()
        val bobSpkKeyPair = ECKeyPair.generate()
        val bobSpkSignature = bobIdentityKeyPair.privateKey.calculateSignature(bobSpkKeyPair.publicKey.serialize())

        val bobKemKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val invalidSignature = ByteArray(64) { 0xFF.toByte() }

        val bobUserId = "+10000000002"
        val bobDeviceId = "bob-dev-002"
        val bobRegId = 2

        val bundle = RemotePreKeyBundle(
            deviceId = bobDeviceId,
            userId = bobUserId,
            registrationId = bobRegId,
            identityPublicKeyBase64 = encodeBase64(bobIdentityKeyPair.publicKey.serialize()),
            signedPreKeyId = 1,
            signedPreKeyBase64 = encodeBase64(bobSpkKeyPair.publicKey.serialize()),
            signedPreKeySignatureBase64 = encodeBase64(bobSpkSignature),
            oneTimePreKeyId = null,
            oneTimePreKeyBase64 = null,
            kyberPreKeyId = 1,
            kyberPreKeyBase64 = encodeBase64(bobKemKeyPair.publicKey.serialize()),
            kyberPreKeySignatureBase64 = encodeBase64(invalidSignature)
        )

        val sessionService = X3DHSessionServiceImpl(
            authRepository = FakeAuthRepo(),
            preKeySyncRepository = FakeSyncRepo(bundle),
            protocolStore = protocolStore,
            sessionStore = sessionStore
        )

        val result = sessionService.processAndEstablishSession(bundle)
        assertTrue("Should reject invalid Kyber signature", result is X3DHSessionResult.InvalidPreKeyBundle)
        val failure = result as X3DHSessionResult.InvalidPreKeyBundle
        assertTrue("Failure reason should mention Kyber prekey signature verification failed", failure.reason.contains("Kyber prekey signature verification failed"))
    }

    private fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private class TestCryptoKeyManager(private val secretKey: SecretKey) : CryptoKeyManager {
        override fun encryptData(plaintext: ByteArray): EncryptedDataBlob {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey)
            return EncryptedDataBlob(cipher.iv, cipher.doFinal(plaintext))
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

    private class FakeAuthRepo : AuthRepository {
        override suspend fun signUp(email: String, password: String) = com.campuschat.app.core.result.Resource.Error("Not implemented")
        override suspend fun signIn(email: String, password: String) = com.campuschat.app.core.result.Resource.Error("Not implemented")
        override suspend fun signOut() = com.campuschat.app.core.result.Resource.Success(Unit)
        override suspend fun getCurrentUser(): UserInfo? = null
        override suspend fun restoreSession() = com.campuschat.app.core.result.Resource.Success(null)
    }

    private class FakeSyncRepo(private val bundle: RemotePreKeyBundle) : PreKeySyncRepository {
        override suspend fun publishLocalPublicKeys(userId: String, deviceId: String, registrationId: Int) = com.campuschat.app.core.result.Resource.Success(Unit)
        override suspend fun rotateSignedPreKey(userId: String, deviceId: String) = com.campuschat.app.core.result.Resource.Success(Unit)
        override suspend fun replenishOneTimePreKeys(userId: String, deviceId: String) = com.campuschat.app.core.result.Resource.Success(0)
        override suspend fun claimPreKeyBundle(recipientDeviceId: String) = com.campuschat.app.core.result.Resource.Success(bundle)
        override suspend fun getRemoteUnconsumedPreKeyCount(deviceId: String) = com.campuschat.app.core.result.Resource.Success(100)
    }
}
