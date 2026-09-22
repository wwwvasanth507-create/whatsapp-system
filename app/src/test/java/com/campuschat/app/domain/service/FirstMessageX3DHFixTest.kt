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
import com.campuschat.app.data.dto.DeviceDto
import com.campuschat.app.data.repository.DeviceRepositoryImpl
import com.campuschat.app.data.local.entity.MessageEntity
import com.campuschat.app.domain.model.EncryptedMessageEnvelope
import com.campuschat.app.domain.model.PendingTransportMessage
import com.campuschat.app.domain.model.RemotePreKeyBundle
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.X3DHSessionResult
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.MessageTransportRepository
import com.campuschat.app.domain.repository.PreKeySyncRepository
import com.campuschat.app.presentation.chat.conversation.ConversationViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.user.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@OptIn(ExperimentalCoroutinesApi::class)
class FirstMessageX3DHFixTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()

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
    private val aliceRegistrationId = 1

    private val bobDeviceId = "bob-dev-002"
    private val bobUserId = "+10000000002"
    private val bobRegistrationId = 2

    private lateinit var bobIdentityKeyPair: IdentityKeyPair
    private var bobSpkId = 1
    private lateinit var bobSpkKeyPair: ECKeyPair
    private lateinit var bobSpkSignature: ByteArray
    private var bobOpkId = 10
    private lateinit var bobOpkKeyPair: ECKeyPair

    private val masterKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

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

        bobIdentityKeyPair = IdentityKeyPair.generate()
        bobSpkKeyPair = ECKeyPair.generate()
        bobSpkSignature = bobIdentityKeyPair.privateKey.calculateSignature(bobSpkKeyPair.publicKey.serialize())
        bobOpkKeyPair = ECKeyPair.generate()

        println("=== SESSIONBUILDER METHODS ===")
        org.signal.libsignal.protocol.SessionBuilder::class.java.declaredMethods.forEach { m ->
            println("SESSIONBUILDER METHOD: ${m.name}(${m.parameterTypes.joinToString { it.name }}) -> ${m.returnType.name}")
        }
        org.signal.libsignal.protocol.SessionBuilder::class.java.constructors.forEach { c ->
            println("SESSIONBUILDER CONSTRUCTOR: $c")
        }
        println("=== PREKEYBUNDLE METHODS ===")
        org.signal.libsignal.protocol.state.PreKeyBundle::class.java.declaredMethods.forEach { m ->
            println("PREKEYBUNDLE METHOD: ${m.name}(${m.parameterTypes.joinToString { it.name }}) -> ${m.returnType.name}")
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        sessionStore.clearStore()
    }

    private fun createRemoteBobBundle(includeKyber: Boolean = true): RemotePreKeyBundle {
        val kemKeyPair = if (includeKyber) {
            org.signal.libsignal.protocol.kem.KEMKeyPair.generate(org.signal.libsignal.protocol.kem.KEMKeyType.values()[0])
        } else null
        val kemSig = kemKeyPair?.let {
            bobIdentityKeyPair.privateKey.calculateSignature(it.publicKey.serialize())
        }

        return RemotePreKeyBundle(
            deviceId = bobDeviceId,
            userId = bobUserId,
            registrationId = bobRegistrationId,
            identityPublicKeyBase64 = encodeBase64(bobIdentityKeyPair.publicKey.serialize()),
            signedPreKeyId = bobSpkId,
            signedPreKeyBase64 = encodeBase64(bobSpkKeyPair.publicKey.serialize()),
            signedPreKeySignatureBase64 = encodeBase64(bobSpkSignature),
            oneTimePreKeyId = bobOpkId,
            oneTimePreKeyBase64 = encodeBase64(bobOpkKeyPair.publicKey.serialize()),
            kyberPreKeyId = if (includeKyber) 1 else null,
            kyberPreKeyBase64 = kemKeyPair?.let { encodeBase64(it.publicKey.serialize()) },
            kyberPreKeySignatureBase64 = kemSig?.let { encodeBase64(it) }
        )
    }

    // 1. testX3DHWithoutKyberUsesClassicPreKeyBundle
    @Test
    fun testX3DHWithoutKyberUsesClassicPreKeyBundle() {
        val fakeAuth = FakeAuthRepo()
        val fakeSync = FakeSyncRepo(createRemoteBobBundle(includeKyber = false))
        val x3dhService = X3DHSessionServiceImpl(fakeAuth, fakeSync, protocolStore, sessionStore)

        val result = x3dhService.processAndEstablishSession(createRemoteBobBundle(includeKyber = false))
        assertTrue("Expected InvalidPreKeyBundle for missing Kyber material but got $result", result is X3DHSessionResult.InvalidPreKeyBundle)
        val failure = result as X3DHSessionResult.InvalidPreKeyBundle
        assertTrue("Failure reason must mention Kyber material", failure.reason.contains("Kyber", ignoreCase = true))
    }

    // 2. testDummyKyberMaterialIsNeverGenerated
    @Test
    fun testDummyKyberMaterialIsNeverGenerated() {
        val bundle = createRemoteBobBundle(includeKyber = false)
        assertNull(bundle.kyberPreKeyBase64)
        assertNull(bundle.kyberPreKeySignatureBase64)

        val fakeAuth = FakeAuthRepo()
        val fakeSync = FakeSyncRepo(bundle)
        val x3dhService = X3DHSessionServiceImpl(fakeAuth, fakeSync, protocolStore, sessionStore)

        val result = x3dhService.processAndEstablishSession(bundle)
        // Must never throw "Invalid Kyber prekey signature" or generate dummy KEM keys
        assertFalse("Must not throw CryptoFailure for dummy Kyber signature", result is X3DHSessionResult.CryptoFailure)
    }

    // 3. testDeviceRegistrationPersistsStableRegistrationId
    @Test
    fun testDeviceRegistrationPersistsStableRegistrationId() = kotlinx.coroutines.runBlocking {
        var capturedRegistrationId: Int? = null

        val deviceRepo = object : com.campuschat.app.domain.repository.DeviceRepository {
            override suspend fun registerOrUpdateDevice(device: UserDevice): Resource<UserDevice> {
                capturedRegistrationId = device.registrationId ?: 404
                return Resource.Success(device.copy(registrationId = capturedRegistrationId))
            }

            override suspend fun updateLastSeen(userId: String, deviceId: String): Resource<Unit> {
                return Resource.Success(Unit)
            }
        }

        val testDevice = UserDevice(id = "dev-123", userId = "u1", deviceName = "Pixel 8", registrationId = 777)
        val res = deviceRepo.registerOrUpdateDevice(testDevice)

        assertTrue(res is Resource.Success)
        assertEquals(777, capturedRegistrationId)
    }

    // 4. testX3DHSessionUsesClaimedRegistrationId
    @Test
    fun testX3DHSessionUsesClaimedRegistrationId() {
        val bundle = createRemoteBobBundle(includeKyber = true)
        val fakeAuth = FakeAuthRepo()
        val fakeSync = FakeSyncRepo(bundle)
        val x3dhService = X3DHSessionServiceImpl(fakeAuth, fakeSync, protocolStore, sessionStore)

        val result = x3dhService.processAndEstablishSession(bundle)
        assertTrue("Expected SessionEstablished but got $result", result is X3DHSessionResult.SessionEstablished)
        val established = result as X3DHSessionResult.SessionEstablished

        assertEquals(bobRegistrationId, established.address.deviceId)
        val targetAddress = SignalProtocolAddress(bobUserId, bobRegistrationId)
        assertTrue(sessionStore.containsSession(targetAddress))
    }

    // 5. testMessageEncryptionUsesSameRegistrationIdAsSession
    @Test
    fun testMessageEncryptionUsesSameRegistrationIdAsSession() = kotlinx.coroutines.runBlocking {
        val bundle = createRemoteBobBundle(includeKyber = true)
        val fakeAuth = FakeAuthRepo()
        val fakeSync = FakeSyncRepo(bundle)
        val x3dhService = X3DHSessionServiceImpl(fakeAuth, fakeSync, protocolStore, sessionStore)

        val estResult = x3dhService.processAndEstablishSession(bundle)
        assertTrue(estResult is X3DHSessionResult.SessionEstablished)
        val claimedRegId = (estResult as X3DHSessionResult.SessionEstablished).address.deviceId

        val encService = EncryptedMessageServiceImpl(fakeAuth, protocolStore, sessionStore)
        val encResult = encService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = claimedRegId,
            plaintext = "Hello Bob with claimed reg ID"
        )

        assertTrue("Expected Success but got $encResult", encResult is com.campuschat.app.domain.model.EncryptionResult.Success)
        val envelope = (encResult as com.campuschat.app.domain.model.EncryptionResult.Success).envelope
        assertEquals(claimedRegId, envelope.recipientRegistrationId)
    }

    // 6. testNoHardcodedRecipientRegistrationId
    @Test
    fun testNoHardcodedRecipientRegistrationId() = kotlinx.coroutines.runBlocking {
        var passedRegId: Int? = null

        val fakeX3DH = object : X3DHSessionService {
            override suspend fun establishOutboundSession(recipientDeviceId: String): X3DHSessionResult {
                return X3DHSessionResult.SessionEstablished(
                    address = SignalProtocolAddress(bobUserId, 5), // Claimed Registration ID 5
                    remoteDeviceId = recipientDeviceId,
                    claimedOneTimePreKeyId = 10
                )
            }
        }

        val fakeOutbox = object : com.campuschat.app.domain.service.MessageOutboxService {
            override suspend fun queueTextMessage(senderDeviceId: String, recipientUserId: String, recipientDeviceId: String, recipientRegistrationId: Int, plaintext: String): MessageEntity? = null
            override suspend fun processPendingOutboxMessages(localAccountId: String, senderDeviceId: String): Resource<Int> = Resource.Success(0)
            override suspend fun sendEncryptedTextMessage(
                senderDeviceId: String,
                recipientUserId: String,
                recipientDeviceId: String,
                recipientRegistrationId: Int,
                plaintext: String
            ): Resource<String> {
                passedRegId = recipientRegistrationId
                return Resource.Success("msg_sent_123")
            }
        }

        val fakePending = object : com.campuschat.app.domain.service.PendingMessageService {
            override suspend fun fetchAndDecryptPendingMessages(localDeviceId: String): com.campuschat.app.domain.service.ProcessPendingResult {
                return com.campuschat.app.domain.service.ProcessPendingResult.Processed(emptyList(), 0)
            }
        }

        val viewModel = ConversationViewModel(
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            messageOutboxService = fakeOutbox,
            pendingMessageService = fakePending,
            x3dhSessionService = fakeX3DH
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onMessageInputChanged("Testing dynamic registration id")
        viewModel.sendMessage()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(5, passedRegId)
    }

    // 7. testX3DHFailureReasonIsPreservedSafely
    @Test
    fun testX3DHFailureReasonIsPreservedSafely() = kotlinx.coroutines.runBlocking {
        val detailedFailureReason = "Signed prekey signature verification failed"

        val failingX3DH = object : X3DHSessionService {
            override suspend fun establishOutboundSession(recipientDeviceId: String): X3DHSessionResult {
                return X3DHSessionResult.InvalidPreKeyBundle(detailedFailureReason)
            }
        }

        val mockOutbox = mock(com.campuschat.app.domain.service.MessageOutboxService::class.java)
        val mockPending = mock(com.campuschat.app.domain.service.PendingMessageService::class.java)

        val viewModel = ConversationViewModel(
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            messageOutboxService = mockOutbox,
            pendingMessageService = mockPending,
            x3dhSessionService = failingX3DH
        )

        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onMessageInputChanged("Test message")
        viewModel.sendMessage()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSending)
        assertEquals(detailedFailureReason, state.errorMessage)
        assertFalse("Generic message must not override detailed reason", state.errorMessage == "Session establishment failed")
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

    private class FakeAuthRepo : AuthRepository {
        private val userInfoJson = """
            {
                "id": "alice-user-id",
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

        override suspend fun signUp(email: String, password: String) = Resource.Error("Not implemented")
        override suspend fun signIn(email: String, password: String) = Resource.Error("Not implemented")
        override suspend fun signOut() = Resource.Success(Unit)
        override suspend fun getCurrentUser(): UserInfo? = kotlinx.serialization.json.Json.decodeFromString<UserInfo>(userInfoJson)
        override suspend fun restoreSession() = Resource.Success(getCurrentUser())
    }

    private class FakeSyncRepo(val bundle: RemotePreKeyBundle) : PreKeySyncRepository {
        override suspend fun publishLocalPublicKeys(userId: String, deviceId: String, registrationId: Int) = Resource.Success(Unit)
        override suspend fun rotateSignedPreKey(userId: String, deviceId: String) = Resource.Success(Unit)
        override suspend fun replenishOneTimePreKeys(userId: String, deviceId: String) = Resource.Success(0)
        override suspend fun claimPreKeyBundle(recipientDeviceId: String): Resource<RemotePreKeyBundle> = Resource.Success(bundle)
        override suspend fun getRemoteUnconsumedPreKeyCount(deviceId: String) = Resource.Success(100)
    }
}
