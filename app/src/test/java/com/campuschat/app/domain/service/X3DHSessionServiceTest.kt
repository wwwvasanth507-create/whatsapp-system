package com.campuschat.app.domain.service

import com.campuschat.app.core.crypto.CampusChatSessionStore
import com.campuschat.app.core.crypto.CampusChatSignalProtocolStore
import com.campuschat.app.core.crypto.CryptoKeyManager
import com.campuschat.app.core.crypto.EncryptedDataBlob
import com.campuschat.app.core.crypto.IdentityKeyManagerImpl
import com.campuschat.app.core.crypto.PreKeyManagerImpl
import com.campuschat.app.core.crypto.SecurityLevel
import com.campuschat.app.core.crypto.SessionStoreImpl
import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.RemotePreKeyBundle
import com.campuschat.app.domain.model.X3DHSessionResult
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.PreKeySyncRepository
import io.github.jan.supabase.gotrue.user.UserInfo
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
import org.signal.libsignal.protocol.ecc.ECKeyPair
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(JUnit4::class)
class X3DHSessionServiceTest {

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

    private lateinit var fakeAuthRepository: FakeAuthRepository
    private lateinit var fakePreKeySyncRepository: FakePreKeySyncRepository
    private lateinit var sessionService: X3DHSessionServiceImpl

    private val aliceDeviceId = "alice-device-uuid-001"
    private val aliceUserId = "10000000001"
    private val aliceRegistrationId = 1111

    private val bobUserId = "10000000002"
    private val bobDeviceId = "bob-device-uuid-002"
    private val bobRegistrationId = 2222

    private lateinit var bobIdentityKeyPair: IdentityKeyPair
    private var bobSpkId = 1
    private lateinit var bobSpkKeyPair: ECKeyPair
    private lateinit var bobSpkSignature: ByteArray

    private var bobOpkId = 10
    private lateinit var bobOpkKeyPair: ECKeyPair

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
            localRegistrationId = aliceRegistrationId
        )

        fakeAuthRepository = FakeAuthRepository(isLoggedIn = true)
        fakePreKeySyncRepository = FakePreKeySyncRepository()

        sessionService = X3DHSessionServiceImpl(
            authRepository = fakeAuthRepository,
            preKeySyncRepository = fakePreKeySyncRepository,
            protocolStore = protocolStore,
            sessionStore = sessionStore
        )

        // Generate Bob's test keys
        bobIdentityKeyPair = IdentityKeyPair.generate()
        bobSpkKeyPair = ECKeyPair.generate()
        bobSpkSignature = bobIdentityKeyPair.privateKey.calculateSignature(bobSpkKeyPair.publicKey.serialize())
        bobOpkKeyPair = ECKeyPair.generate()
    }

    @After
    fun tearDown() {
        sessionStore.clearStore()
    }

    private fun createValidBobBundle(withOpk: Boolean = true): RemotePreKeyBundle {
        return RemotePreKeyBundle(
            deviceId = bobDeviceId,
            userId = bobUserId,
            registrationId = bobRegistrationId,
            identityPublicKeyBase64 = encodeBase64(bobIdentityKeyPair.publicKey.serialize()),
            signedPreKeyId = bobSpkId,
            signedPreKeyBase64 = encodeBase64(bobSpkKeyPair.publicKey.serialize()),
            signedPreKeySignatureBase64 = encodeBase64(bobSpkSignature),
            oneTimePreKeyId = if (withOpk) bobOpkId else null,
            oneTimePreKeyBase64 = if (withOpk) encodeBase64(bobOpkKeyPair.publicKey.serialize()) else null
        )
    }

    @Test
    fun testValidPreKeyBundleAcceptedAndSessionEstablished() {
        val bundle = createValidBobBundle(withOpk = true)
        val result = sessionService.processAndEstablishSession(bundle)

        assertTrue("Expected SessionEstablished but got $result", result is X3DHSessionResult.SessionEstablished)
        val established = result as X3DHSessionResult.SessionEstablished
        assertEquals(bobUserId, established.address.name)
        assertEquals(bobRegistrationId, established.address.deviceId)
        assertEquals(bobDeviceId, established.remoteDeviceId)
        assertEquals(bobOpkId, established.claimedOneTimePreKeyId)

        // Verify session stored in local encrypted SessionStore
        val targetAddress = SignalProtocolAddress(bobUserId, bobRegistrationId)
        assertTrue(sessionStore.containsSession(targetAddress))
    }

    @Test
    fun testSessionEstablishedWithoutOpk() {
        val bundle = createValidBobBundle(withOpk = false)
        val result = sessionService.processAndEstablishSession(bundle)

        assertTrue(result is X3DHSessionResult.SessionEstablished)
        val established = result as X3DHSessionResult.SessionEstablished
        assertNull(established.claimedOneTimePreKeyId)

        val targetAddress = SignalProtocolAddress(bobUserId, bobRegistrationId)
        assertTrue(sessionStore.containsSession(targetAddress))
    }

    @Test
    fun testInvalidSignedPreKeySignatureRejected() {
        val validBundle = createValidBobBundle()
        val invalidSignatureBytes = ByteArray(64) { 0x00 }
        val tamperedBundle = validBundle.copy(
            signedPreKeySignatureBase64 = encodeBase64(invalidSignatureBytes)
        )

        val result = sessionService.processAndEstablishSession(tamperedBundle)

        assertTrue(result is X3DHSessionResult.InvalidPreKeyBundle)
        val failure = result as X3DHSessionResult.InvalidPreKeyBundle
        assertTrue(failure.reason.contains("signature verification failed"))

        val targetAddress = SignalProtocolAddress(bobUserId, bobRegistrationId)
        assertFalse(sessionStore.containsSession(targetAddress))
    }

    @Test
    fun testMissingIdentityKeyRejected() {
        val validBundle = createValidBobBundle()
        val invalidBundle = validBundle.copy(identityPublicKeyBase64 = "")

        val result = sessionService.processAndEstablishSession(invalidBundle)
        assertTrue(result is X3DHSessionResult.InvalidPreKeyBundle)
    }

    @Test
    fun testMissingSignedPreKeyRejected() {
        val validBundle = createValidBobBundle()
        val invalidBundle = validBundle.copy(signedPreKeyBase64 = "")

        val result = sessionService.processAndEstablishSession(invalidBundle)
        assertTrue(result is X3DHSessionResult.InvalidPreKeyBundle)
    }

    @Test
    fun testIdentityKeyChangeDetectedAndRejected() {
        // 1. Establish session with Bob Identity A
        val bundleA = createValidBobBundle()
        val resultA = sessionService.processAndEstablishSession(bundleA)
        assertTrue(resultA is X3DHSessionResult.SessionEstablished)

        // 2. Attempt session establishment for SAME address with DIFFERENT Bob Identity B!
        val newBobIdentity = IdentityKeyPair.generate()
        val newSpkKeyPair = ECKeyPair.generate()
        val newSpkSig = newBobIdentity.privateKey.calculateSignature(newSpkKeyPair.publicKey.serialize())

        val bundleB = bundleA.copy(
            identityPublicKeyBase64 = encodeBase64(newBobIdentity.publicKey.serialize()),
            signedPreKeyBase64 = encodeBase64(newSpkKeyPair.publicKey.serialize()),
            signedPreKeySignatureBase64 = encodeBase64(newSpkSig)
        )

        val resultB = sessionService.processAndEstablishSession(bundleB)

        // Must reject identity key change!
        assertTrue(resultB is X3DHSessionResult.IdentityChanged)
        val identityChanged = resultB as X3DHSessionResult.IdentityChanged
        assertEquals(bobUserId, identityChanged.remoteUserId)
        assertEquals(bobDeviceId, identityChanged.remoteDeviceId)
    }

    @Test
    fun testMultiDeviceIndependentSessions() {
        val bundleB1 = createValidBobBundle()
        val resultB1 = sessionService.processAndEstablishSession(bundleB1)
        assertTrue(resultB1 is X3DHSessionResult.SessionEstablished)

        // Bob Device 2
        val bobDev2RegistrationId = 3333
        val bobDev2Id = "bob-device-uuid-003"
        val bobDev2SpkKeyPair = ECKeyPair.generate()
        val bobDev2SpkSig = bobIdentityKeyPair.privateKey.calculateSignature(bobDev2SpkKeyPair.publicKey.serialize())

        val bundleB2 = bundleB1.copy(
            deviceId = bobDev2Id,
            registrationId = bobDev2RegistrationId,
            signedPreKeyBase64 = encodeBase64(bobDev2SpkKeyPair.publicKey.serialize()),
            signedPreKeySignatureBase64 = encodeBase64(bobDev2SpkSig)
        )

        val resultB2 = sessionService.processAndEstablishSession(bundleB2)
        assertTrue(resultB2 is X3DHSessionResult.SessionEstablished)

        // Both device sessions must exist independently
        val addressB1 = SignalProtocolAddress(bobUserId, bobRegistrationId)
        val addressB2 = SignalProtocolAddress(bobUserId, bobDev2RegistrationId)

        assertTrue(sessionStore.containsSession(addressB1))
        assertTrue(sessionStore.containsSession(addressB2))
    }

    @Test
    fun testUnauthenticatedRequestRejected() = kotlinx.coroutines.runBlocking {
        fakeAuthRepository.isLoggedIn = false
        val result = sessionService.establishOutboundSession(bobDeviceId)
        assertTrue(result is X3DHSessionResult.AuthenticationRequired)
    }

    @Test
    fun testInactiveRecipientDeviceRejected() = kotlinx.coroutines.runBlocking {
        fakeAuthRepository.isLoggedIn = true
        fakePreKeySyncRepository.claimResult = Resource.Error("Recipient device is inactive")

        val result = sessionService.establishOutboundSession(bobDeviceId)
        assertTrue(result is X3DHSessionResult.RecipientDeviceUnavailable)
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return Base64.getEncoder().encodeToString(bytes)
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

    private class FakeAuthRepository(var isLoggedIn: Boolean) : AuthRepository {
        private val aliceUserInfo: UserInfo by lazy {
            val jsonStr = """
            {
                "id": "user-alice",
                "aud": "authenticated",
                "role": "authenticated",
                "email": "alice@campus.edu",
                "email_confirmed_at": "2026-09-20T00:00:00Z",
                "phone": "",
                "confirmed_at": "2026-09-20T00:00:00Z",
                "last_sign_in_at": "2026-09-20T00:00:00Z",
                "app_metadata": {},
                "user_metadata": {},
                "identities": [],
                "created_at": "2026-09-20T00:00:00Z",
                "updated_at": "2026-09-20T00:00:00Z"
            }
            """.trimIndent()
            kotlinx.serialization.json.Json.decodeFromString<UserInfo>(jsonStr)
        }

        override suspend fun signUp(email: String, password: String): Resource<UserInfo> = Resource.Error("Not implemented")
        override suspend fun signIn(email: String, password: String): Resource<UserInfo> = Resource.Error("Not implemented")
        override suspend fun signOut(): Resource<Unit> = Resource.Success(Unit)
        override suspend fun getCurrentUser(): UserInfo? = if (isLoggedIn) aliceUserInfo else null
        override suspend fun restoreSession(): Resource<UserInfo?> = Resource.Success(getCurrentUser())
    }

    private class FakePreKeySyncRepository : PreKeySyncRepository {
        var claimResult: Resource<RemotePreKeyBundle>? = null

        override suspend fun publishLocalPublicKeys(userId: String, deviceId: String, registrationId: Int): Resource<Unit> = Resource.Success(Unit)
        override suspend fun rotateSignedPreKey(userId: String, deviceId: String): Resource<Unit> = Resource.Success(Unit)
        override suspend fun replenishOneTimePreKeys(userId: String, deviceId: String): Resource<Int> = Resource.Success(0)
        override suspend fun claimPreKeyBundle(recipientDeviceId: String): Resource<RemotePreKeyBundle> {
            return claimResult ?: Resource.Error("No bundle claimed")
        }

        override suspend fun getRemoteUnconsumedPreKeyCount(deviceId: String): Resource<Int> = Resource.Success(100)
    }
}
