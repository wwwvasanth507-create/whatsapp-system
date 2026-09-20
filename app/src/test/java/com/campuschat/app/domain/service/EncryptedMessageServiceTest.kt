package com.campuschat.app.domain.service

import com.campuschat.app.core.crypto.CampusChatSignalProtocolStore
import com.campuschat.app.core.crypto.CryptoKeyManager
import com.campuschat.app.core.crypto.EncryptedDataBlob
import com.campuschat.app.core.crypto.IdentityKeyManagerImpl
import com.campuschat.app.core.crypto.PreKeyManagerImpl
import com.campuschat.app.core.crypto.SecurityLevel
import com.campuschat.app.core.crypto.SessionStoreImpl
import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.DecryptionResult
import com.campuschat.app.domain.model.EncryptedMessageEnvelope
import com.campuschat.app.domain.model.EncryptionResult
import com.campuschat.app.domain.model.RemotePreKeyBundle
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
class EncryptedMessageServiceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val masterKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private lateinit var cryptoKeyManager: CryptoKeyManager

    // --- Alice Party (Sender) ---
    private val aliceUserId = "+10000000001"
    private val aliceDeviceId = "alice-device-uuid-001"
    private val aliceRegistrationId = 1

    private lateinit var aliceIdentityFile: File
    private lateinit var alicePreKeyFile: File
    private lateinit var aliceSessionFile: File

    private lateinit var aliceIdentityManager: IdentityKeyManagerImpl
    private lateinit var alicePreKeyManager: PreKeyManagerImpl
    private lateinit var aliceSessionStore: SessionStoreImpl
    private lateinit var aliceProtocolStore: CampusChatSignalProtocolStore
    private lateinit var aliceSessionService: X3DHSessionServiceImpl
    private lateinit var aliceMessageService: EncryptedMessageServiceImpl

    // --- Bob Party (Recipient) ---
    private val bobUserId = "+10000000002"
    private val bobDeviceId = "bob-device-uuid-002"
    private val bobRegistrationId = 2

    private lateinit var bobIdentityFile: File
    private lateinit var bobPreKeyFile: File
    private lateinit var bobSessionFile: File

    private lateinit var bobIdentityManager: IdentityKeyManagerImpl
    private lateinit var bobPreKeyManager: PreKeyManagerImpl
    private lateinit var bobSessionStore: SessionStoreImpl
    private lateinit var bobProtocolStore: CampusChatSignalProtocolStore
    private lateinit var bobSessionService: X3DHSessionServiceImpl
    private lateinit var bobMessageService: EncryptedMessageServiceImpl

    private lateinit var fakeAliceAuthRepository: FakeAuthRepository
    private lateinit var fakeBobAuthRepository: FakeAuthRepository

    @Before
    fun setUp() {
        cryptoKeyManager = TestCryptoKeyManager(masterKey)

        // 1. Setup Alice Environment
        aliceIdentityFile = File(tempFolder.root, "alice_id.bin")
        alicePreKeyFile = File(tempFolder.root, "alice_pk.bin")
        aliceSessionFile = File(tempFolder.root, "alice_session.bin")

        aliceIdentityManager = IdentityKeyManagerImpl(null, cryptoKeyManager, aliceIdentityFile)
        aliceIdentityManager.getOrGenerateIdentity(aliceDeviceId)
        alicePreKeyManager = PreKeyManagerImpl(null, cryptoKeyManager, aliceIdentityManager, alicePreKeyFile)
        alicePreKeyManager.initializePreKeys(aliceDeviceId)
        aliceSessionStore = SessionStoreImpl(null, cryptoKeyManager, aliceSessionFile)
        aliceSessionStore.initStore(aliceDeviceId)

        aliceProtocolStore = CampusChatSignalProtocolStore(
            identityKeyManager = aliceIdentityManager,
            preKeyManager = alicePreKeyManager,
            sessionStore = aliceSessionStore,
            localDeviceId = aliceDeviceId,
            localRegistrationId = aliceRegistrationId
        )

        fakeAliceAuthRepository = FakeAuthRepository(userId = aliceUserId)
        aliceSessionService = X3DHSessionServiceImpl(
            authRepository = fakeAliceAuthRepository,
            preKeySyncRepository = FakePreKeySyncRepository(),
            protocolStore = aliceProtocolStore,
            sessionStore = aliceSessionStore
        )
        aliceMessageService = EncryptedMessageServiceImpl(
            authRepository = fakeAliceAuthRepository,
            protocolStore = aliceProtocolStore,
            sessionStore = aliceSessionStore
        )

        // 2. Setup Bob Environment
        bobIdentityFile = File(tempFolder.root, "bob_id.bin")
        bobPreKeyFile = File(tempFolder.root, "bob_pk.bin")
        bobSessionFile = File(tempFolder.root, "bob_session.bin")

        bobIdentityManager = IdentityKeyManagerImpl(null, cryptoKeyManager, bobIdentityFile)
        bobIdentityManager.getOrGenerateIdentity(bobDeviceId)
        bobPreKeyManager = PreKeyManagerImpl(null, cryptoKeyManager, bobIdentityManager, bobPreKeyFile)
        bobPreKeyManager.initializePreKeys(bobDeviceId)
        bobSessionStore = SessionStoreImpl(null, cryptoKeyManager, bobSessionFile)
        bobSessionStore.initStore(bobDeviceId)

        bobProtocolStore = CampusChatSignalProtocolStore(
            identityKeyManager = bobIdentityManager,
            preKeyManager = bobPreKeyManager,
            sessionStore = bobSessionStore,
            localDeviceId = bobDeviceId,
            localRegistrationId = bobRegistrationId
        )

        fakeBobAuthRepository = FakeAuthRepository(userId = bobUserId)
        bobSessionService = X3DHSessionServiceImpl(
            authRepository = fakeBobAuthRepository,
            preKeySyncRepository = FakePreKeySyncRepository(),
            protocolStore = bobProtocolStore,
            sessionStore = bobSessionStore
        )
        bobMessageService = EncryptedMessageServiceImpl(
            authRepository = fakeBobAuthRepository,
            protocolStore = bobProtocolStore,
            sessionStore = bobSessionStore
        )

        // 3. Establish initial X3DH Session (Alice -> Bob)
        establishX3DHSessionAliceToBob()
    }

    @After
    fun tearDown() {
        aliceSessionStore.clearStore()
        bobSessionStore.clearStore()
    }

    private fun establishX3DHSessionAliceToBob() {
        val bobIdentity = bobProtocolStore.getIdentityKeyPair()
        val bobSpk = bobPreKeyManager.getCurrentSignedPreKey(bobDeviceId)!!
        val bobOpk = bobPreKeyManager.getAvailableOneTimePreKeys(bobDeviceId).first()

        val kemKeyPair = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(org.signal.libsignal.protocol.kem.KEMKeyType.values()[0])
        val kemSig = bobIdentity.privateKey.calculateSignature(kemKeyPair.publicKey.serialize())
        val kyberRecord = org.signal.libsignal.protocol.state.KyberPreKeyRecord(1, System.currentTimeMillis(), kemKeyPair, kemSig)
        bobProtocolStore.storeKyberPreKey(1, kyberRecord)

        val bobBundle = RemotePreKeyBundle(
            deviceId = bobDeviceId,
            userId = bobUserId,
            registrationId = bobRegistrationId,
            identityPublicKeyBase64 = encodeBase64(bobIdentity.publicKey.serialize()),
            signedPreKeyId = bobSpk.id,
            signedPreKeyBase64 = encodeBase64(bobSpk.keyPair.publicKey.serialize()),
            signedPreKeySignatureBase64 = encodeBase64(bobSpk.signature),
            oneTimePreKeyId = bobOpk.id,
            oneTimePreKeyBase64 = encodeBase64(bobOpk.keyPair.publicKey.serialize()),
            kyberPreKeyId = 1,
            kyberPreKeyBase64 = encodeBase64(kemKeyPair.publicKey.serialize()),
            kyberPreKeySignatureBase64 = encodeBase64(kemSig)
        )

        val x3dhResult = aliceSessionService.processAndEstablishSession(bobBundle)
        assertTrue("X3DH establishment failed: $x3dhResult", x3dhResult is com.campuschat.app.domain.model.X3DHSessionResult.SessionEstablished)
    }

    // 1. Encrypt text with an established session
    @Test
    fun test1_EncryptTextWithEstablishedSession() = kotlinx.coroutines.runBlocking {
        val result = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "Meet at campus library at 5 PM"
        )

        assertTrue("Expected EncryptionResult.Success but got $result", result is EncryptionResult.Success)
        val envelope = (result as EncryptionResult.Success).envelope
        assertEquals(aliceUserId, envelope.senderUserId)
        assertEquals(bobUserId, envelope.recipientUserId)
        assertTrue(envelope.ciphertextBase64.isNotBlank())
    }

    // 2. Decrypt encrypted text on recipient side
    // 3. Plaintext round-trip equality
    @Test
    fun test2_3_DecryptTextOnRecipientSideAndRoundTripEquality() = kotlinx.coroutines.runBlocking {
        val originalText = "Encrypted message test payload"
        val encryptResult = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = originalText
        ) as EncryptionResult.Success

        val decryptResult = bobMessageService.decryptMessage(encryptResult.envelope)
        assertTrue("Expected DecryptionResult.Success but got $decryptResult", decryptResult is DecryptionResult.Success)
        val recoveredText = (decryptResult as DecryptionResult.Success).plaintext
        assertEquals(originalText, recoveredText)
    }

    // 4. Ciphertext is different from plaintext
    // 5. Plaintext is not present in serialized ciphertext envelope
    @Test
    fun test4_5_CiphertextIsDifferentFromPlaintextAndEnvelopeDoesNotContainPlaintext() = kotlinx.coroutines.runBlocking {
        val sensitiveText = "SUPER_SECRET_TOKEN_9999"
        val encryptResult = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = sensitiveText
        ) as EncryptionResult.Success

        val envelope = encryptResult.envelope
        assertNotEquals(sensitiveText, envelope.ciphertextBase64)
        assertFalse(envelope.ciphertextBase64.contains(sensitiveText))

        // Decode Base64 and ensure plaintext string bytes are not present in raw ciphertext
        val rawBytes = Base64.getDecoder().decode(envelope.ciphertextBase64)
        val rawString = String(rawBytes, Charsets.ISO_8859_1)
        assertFalse(rawString.contains(sensitiveText))
    }

    // 6. Empty plaintext rejected
    @Test
    fun test6_EmptyPlaintextRejected() = kotlinx.coroutines.runBlocking {
        val result = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "   "
        )

        assertTrue(result is EncryptionResult.InvalidInput)
        val invalidInput = result as EncryptionResult.InvalidInput
        assertTrue(invalidInput.reason.contains("empty or blank"))
    }

    // 7. Oversized plaintext rejected
    @Test
    fun test7_OversizedPlaintextRejected() = kotlinx.coroutines.runBlocking {
        val hugeText = "A".repeat(EncryptedMessageServiceImpl.MAX_TEXT_MESSAGE_CHARS + 10)
        val result = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = hugeText
        )

        assertTrue(result is EncryptionResult.InvalidInput)
        val invalidInput = result as EncryptionResult.InvalidInput
        assertTrue(invalidInput.reason.contains("exceeds maximum allowed length"))
    }

    // 8a. Missing session on encryption handled safely
    @Test
    fun test8a_MissingSessionEncryptionHandledSafely() = kotlinx.coroutines.runBlocking {
        val unknownUserId = "+10000000099"
        val unknownRegistrationId = 3

        val resultEnc = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = unknownUserId,
            recipientDeviceId = "unknown-device",
            recipientRegistrationId = unknownRegistrationId,
            plaintext = "Message to unknown recipient"
        )
        System.err.println("=== TEST8A_RESULT: $resultEnc ===")
        assertTrue("Expected EncryptionResult.SessionNotFound but got $resultEnc", resultEnc is EncryptionResult.SessionNotFound)
    }

    // 8b. Missing session on decryption handled safely
    @Test
    fun test8b_MissingSessionDecryptionHandledSafely() = kotlinx.coroutines.runBlocking {
        val unknownUserId = "+10000000099"
        val unknownRegistrationId = 3

        val missingSessionEnvelope = EncryptedMessageEnvelope(
            senderUserId = unknownUserId,
            senderDeviceId = "unknown-device",
            senderRegistrationId = unknownRegistrationId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            messageType = 2, // WHISPER_TYPE requires existing session
            ciphertextBase64 = "Ym9iX2NpcGhlcnRleHRfZGF0YQ=="
        )
        val resultDec = bobMessageService.decryptMessage(missingSessionEnvelope)
        System.err.println("=== TEST8B_RESULT: $resultDec ===")
        assertTrue("Expected DecryptionResult.SessionNotFound but got $resultDec", resultDec is DecryptionResult.SessionNotFound)
    }

    // 9. Invalid ciphertext rejected safely
    @Test
    fun test9_InvalidCiphertextRejectedSafely() = kotlinx.coroutines.runBlocking {
        val encryptResult = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "Valid message"
        ) as EncryptionResult.Success

        // Tamper with ciphertext bytes
        val tamperedBytes = ByteArray(32) { 0xFF.toByte() }
        val tamperedEnvelope = encryptResult.envelope.copy(
            ciphertextBase64 = encodeBase64(tamperedBytes)
        )

        val decryptResult = bobMessageService.decryptMessage(tamperedEnvelope)
        assertTrue("Expected InvalidCiphertext but got $decryptResult", decryptResult is DecryptionResult.InvalidCiphertext)
    }

    // 10. Session state persists after encryption/decryption
    @Test
    fun test10_SessionStatePersistsAfterEncryptDecrypt() = kotlinx.coroutines.runBlocking {
        val aliceBobAddress = SignalProtocolAddress(bobUserId, bobRegistrationId)
        val bobAliceAddress = SignalProtocolAddress(aliceUserId, aliceRegistrationId)

        val encryptResult = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "State test 1"
        ) as EncryptionResult.Success

        bobMessageService.decryptMessage(encryptResult.envelope)

        assertTrue(aliceSessionStore.containsSession(aliceBobAddress))
        assertTrue(bobSessionStore.containsSession(bobAliceAddress))
    }

    // 11. Session survives repository/process reload
    @Test
    fun test11_SessionSurvivesProcessReload() = kotlinx.coroutines.runBlocking {
        val encryptResult = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "Message before restart"
        ) as EncryptionResult.Success

        bobMessageService.decryptMessage(encryptResult.envelope)

        // Instantiate fresh Bob SessionStore & ProtocolStore from disk (simulating process restart)
        val reloadedBobSessionStore = SessionStoreImpl(null, cryptoKeyManager, bobSessionFile)
        reloadedBobSessionStore.initStore(bobDeviceId)

        val reloadedBobProtocolStore = CampusChatSignalProtocolStore(
            identityKeyManager = bobIdentityManager,
            preKeyManager = bobPreKeyManager,
            sessionStore = reloadedBobSessionStore,
            localDeviceId = bobDeviceId,
            localRegistrationId = bobRegistrationId
        )

        val reloadedBobMessageService = EncryptedMessageServiceImpl(
            authRepository = fakeBobAuthRepository,
            protocolStore = reloadedBobProtocolStore,
            sessionStore = reloadedBobSessionStore
        )

        // Send second message after restart
        val encryptResult2 = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "Message after restart"
        ) as EncryptionResult.Success

        val decryptResult2 = reloadedBobMessageService.decryptMessage(encryptResult2.envelope)
        assertTrue(decryptResult2 is DecryptionResult.Success)
        assertEquals("Message after restart", (decryptResult2 as DecryptionResult.Success).plaintext)
    }

    // 12. Multiple sequential messages succeed (Double Ratchet forward movement)
    @Test
    fun test12_MultipleSequentialMessagesSucceed() = kotlinx.coroutines.runBlocking {
        val messages = listOf(
            "Message 1: Good morning!",
            "Message 2: Meeting at 10 AM.",
            "Message 3: Don't forget the slides.",
            "Message 4: Thanks!"
        )

        for (msg in messages) {
            val enc = aliceMessageService.encryptMessage(
                senderDeviceId = aliceDeviceId,
                recipientUserId = bobUserId,
                recipientDeviceId = bobDeviceId,
                recipientRegistrationId = bobRegistrationId,
                plaintext = msg
            ) as EncryptionResult.Success

            val dec = bobMessageService.decryptMessage(enc.envelope)
            assertTrue(dec is DecryptionResult.Success)
            assertEquals(msg, (dec as DecryptionResult.Success).plaintext)
        }
    }

    // 13. Out-of-order behavior is tested according to actual libsignal behavior
    @Test
    fun test13_OutOfOrderMessageHandling() = kotlinx.coroutines.runBlocking {
        // Send message 1
        val enc1 = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "First message (1)"
        ) as EncryptionResult.Success

        // Bob decrypts message 1 to initialize recipient session
        val dec1 = bobMessageService.decryptMessage(enc1.envelope)
        assertTrue(dec1 is DecryptionResult.Success)

        // Send message 2 and message 3 from Alice
        val enc2 = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "Second message (2)"
        ) as EncryptionResult.Success

        val enc3 = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "Third message (3)"
        ) as EncryptionResult.Success

        // Deliver Message 3 FIRST (Out of Order)
        val dec3 = bobMessageService.decryptMessage(enc3.envelope)
        assertTrue("Out-of-order decryption failed: $dec3", dec3 is DecryptionResult.Success)
        assertEquals("Third message (3)", (dec3 as DecryptionResult.Success).plaintext)

        // Deliver Message 2 SECOND (Delayed Message)
        val dec2 = bobMessageService.decryptMessage(enc2.envelope)
        assertTrue("Delayed message decryption failed: $dec2", dec2 is DecryptionResult.Success)
        assertEquals("Second message (2)", (dec2 as DecryptionResult.Success).plaintext)
    }

    // 14. Duplicate ciphertext behavior is tested according to actual libsignal behavior
    @Test
    fun test14_DuplicateMessageHandling() = kotlinx.coroutines.runBlocking {
        val enc1 = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "Single message"
        ) as EncryptionResult.Success

        // First decryption succeeds
        val dec1 = bobMessageService.decryptMessage(enc1.envelope)
        assertTrue(dec1 is DecryptionResult.Success)

        // Second decryption of SAME envelope triggers DuplicateMessageException
        val dec2 = bobMessageService.decryptMessage(enc1.envelope)
        assertTrue("Expected DuplicateOrAlreadyProcessed but got $dec2", dec2 is DecryptionResult.DuplicateOrAlreadyProcessed)
    }

    // 15. Identity-change protection remains enforced
    @Test
    fun test15_IdentityChangeProtectionEnforced() = kotlinx.coroutines.runBlocking {
        val aliceBobAddress = SignalProtocolAddress(bobUserId, bobRegistrationId)

        // 1. Verify untrusted identity is rejected by isTrustedIdentity
        val untrustedIdentity = IdentityKeyPair.generate().publicKey
        val isTrusted = aliceProtocolStore.isTrustedIdentity(
            aliceBobAddress,
            untrustedIdentity,
            org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING
        )
        assertFalse("Expected untrusted identity to be rejected", isTrusted)

        // 2. Verify saveIdentity flags the untrusted replacement as REPLACED without corrupting trusted store
        val identityChange = aliceProtocolStore.saveIdentity(aliceBobAddress, untrustedIdentity)
        assertTrue("Expected IdentityChange.REPLACED but got $identityChange", identityChange.name.contains("REPLACED"))

        // 3. Original identity in store remains protected and unchanged
        val storedIdentity = aliceProtocolStore.getIdentity(aliceBobAddress)
        assertNotEquals(untrustedIdentity, storedIdentity)
    }

    // 16. No sensitive material appears in exceptions/logging
    @Test
    fun test16_NoSensitiveMaterialInExceptionsOrLogs() = kotlinx.coroutines.runBlocking {
        val secretMessage = "VERY_SENSITIVE_CRYPTO_SECRET_KEY_12345"

        val invalidEnvelope = EncryptedMessageEnvelope(
            senderUserId = aliceUserId,
            senderDeviceId = aliceDeviceId,
            senderRegistrationId = aliceRegistrationId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            messageType = 3, // PREKEY_TYPE
            ciphertextBase64 = "invalid_base64_data!"
        )

        val decResult = bobMessageService.decryptMessage(invalidEnvelope)
        assertTrue("Expected InvalidCiphertext but got $decResult", decResult is DecryptionResult.InvalidCiphertext)
        val reason = (decResult as DecryptionResult.InvalidCiphertext).reason
        assertFalse(reason.contains(secretMessage))
        assertFalse(reason.contains("private"))
        assertFalse(reason.contains("secret"))
    }

    // 17. Multi-device sessions remain isolated
    @Test
    fun test17_MultiDeviceSessionsRemainIsolated() = kotlinx.coroutines.runBlocking {
        val bobDev2RegistrationId = 3
        val bobDev2DeviceId = "bob-device-uuid-003"
        val bobDev2Address = SignalProtocolAddress(bobUserId, bobDev2RegistrationId)

        // Establish second X3DH session for Bob Device 2
        val bobIdentity = bobProtocolStore.getIdentityKeyPair()
        val bobDev2SpkKeyPair = ECKeyPair.generate()
        val bobDev2SpkSig = bobIdentity.privateKey.calculateSignature(bobDev2SpkKeyPair.publicKey.serialize())
        val bobDev2OpkKeyPair = ECKeyPair.generate()

        val kemKeyPair = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(org.signal.libsignal.protocol.kem.KEMKeyType.values()[0])
        val kemSig = bobIdentity.privateKey.calculateSignature(kemKeyPair.publicKey.serialize())

        val bobDev2Bundle = RemotePreKeyBundle(
            deviceId = bobDev2DeviceId,
            userId = bobUserId,
            registrationId = bobDev2RegistrationId,
            identityPublicKeyBase64 = encodeBase64(bobIdentity.publicKey.serialize()),
            signedPreKeyId = 2,
            signedPreKeyBase64 = encodeBase64(bobDev2SpkKeyPair.publicKey.serialize()),
            signedPreKeySignatureBase64 = encodeBase64(bobDev2SpkSig),
            oneTimePreKeyId = 20,
            oneTimePreKeyBase64 = encodeBase64(bobDev2OpkKeyPair.publicKey.serialize()),
            kyberPreKeyId = 2,
            kyberPreKeyBase64 = encodeBase64(kemKeyPair.publicKey.serialize()),
            kyberPreKeySignatureBase64 = encodeBase64(kemSig)
        )

        val x3dhResult2 = aliceSessionService.processAndEstablishSession(bobDev2Bundle)
        assertTrue(x3dhResult2 is com.campuschat.app.domain.model.X3DHSessionResult.SessionEstablished)

        // Encrypt message for Device 1
        val encDev1 = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDeviceId,
            recipientRegistrationId = bobRegistrationId,
            plaintext = "For Device 1"
        ) as EncryptionResult.Success

        // Encrypt message for Device 2
        val encDev2 = aliceMessageService.encryptMessage(
            senderDeviceId = aliceDeviceId,
            recipientUserId = bobUserId,
            recipientDeviceId = bobDev2DeviceId,
            recipientRegistrationId = bobDev2RegistrationId,
            plaintext = "For Device 2"
        ) as EncryptionResult.Success

        assertEquals(bobRegistrationId, encDev1.envelope.recipientRegistrationId)
        assertEquals(bobDev2RegistrationId, encDev2.envelope.recipientRegistrationId)
        assertNotEquals(encDev1.envelope.ciphertextBase64, encDev2.envelope.ciphertextBase64)
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

    private class FakeAuthRepository(private val userId: String) : AuthRepository {
        private val userInfo: UserInfo by lazy {
            val jsonStr = """{"id":"$userId","aud":"authenticated","role":"authenticated","email":"user@campus.edu"}"""
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<UserInfo>(jsonStr)
        }

        override suspend fun signUp(email: String, password: String): Resource<UserInfo> = Resource.Error("Not implemented")
        override suspend fun signIn(email: String, password: String): Resource<UserInfo> = Resource.Error("Not implemented")
        override suspend fun signOut(): Resource<Unit> = Resource.Success(Unit)
        override suspend fun getCurrentUser(): UserInfo? = userInfo
        override suspend fun restoreSession(): Resource<UserInfo?> = Resource.Success(getCurrentUser())
    }

    private class FakePreKeySyncRepository : PreKeySyncRepository {
        override suspend fun publishLocalPublicKeys(userId: String, deviceId: String, registrationId: Int): Resource<Unit> = Resource.Success(Unit)
        override suspend fun rotateSignedPreKey(userId: String, deviceId: String): Resource<Unit> = Resource.Success(Unit)
        override suspend fun replenishOneTimePreKeys(userId: String, deviceId: String): Resource<Int> = Resource.Success(0)
        override suspend fun claimPreKeyBundle(recipientDeviceId: String): Resource<RemotePreKeyBundle> = Resource.Error("Not needed in test")
        override suspend fun getRemoteUnconsumedPreKeyCount(deviceId: String): Resource<Int> = Resource.Success(100)
    }
}
