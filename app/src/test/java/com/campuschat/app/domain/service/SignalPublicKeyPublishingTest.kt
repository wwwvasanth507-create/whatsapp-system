package com.campuschat.app.domain.service

import com.campuschat.app.core.crypto.CryptoKeyManager
import com.campuschat.app.core.crypto.EncryptedDataBlob
import com.campuschat.app.core.crypto.IdentityKeyManagerImpl
import com.campuschat.app.core.crypto.PreKeyManagerImpl
import com.campuschat.app.core.crypto.SecurityLevel
import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.dto.DeviceIdentityKeyDto
import com.campuschat.app.data.dto.DeviceOneTimePreKeyDto
import com.campuschat.app.data.dto.DeviceSignedPreKeyDto
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.DeviceRepository
import com.campuschat.app.domain.repository.PreKeySyncRepository
import com.campuschat.app.domain.repository.ProfileRepository
import com.campuschat.app.domain.usecase.LoginUseCase
import com.campuschat.app.domain.usecase.RegisterUseCase
import com.campuschat.app.domain.usecase.RestoreSessionUseCase
import io.github.jan.supabase.gotrue.user.UserInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class SignalPublicKeyPublishingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockFilesDir: File
    private lateinit var fakeCryptoKeyManager: FakeCryptoKeyManager
    private lateinit var identityKeyManager: IdentityKeyManagerImpl
    private lateinit var preKeyManager: PreKeyManagerImpl

    private class FakeCryptoKeyManager : CryptoKeyManager {
        private val key = ByteArray(32) { 0x3C }

        override fun encryptData(plaintext: ByteArray): EncryptedDataBlob {
            val iv = ByteArray(12) { 0x01 }
            val ciphertext = plaintext.mapIndexed { index, byte ->
                (byte.toInt() xor key[index % 32].toInt()).toByte()
            }.toByteArray()
            return EncryptedDataBlob(iv, ciphertext)
        }

        override fun decryptData(blob: EncryptedDataBlob): ByteArray {
            return blob.ciphertext.mapIndexed { index, byte ->
                (byte.toInt() xor key[index % 32].toInt()).toByte()
            }.toByteArray()
        }

        override fun getSecurityLevel(): SecurityLevel = SecurityLevel.TEE
        override fun hasMasterKey(): Boolean = true
    }

    private class TestContext(private val filesDirFile: File) : android.content.ContextWrapper(null) {
        override fun getFilesDir(): File = filesDirFile
    }

    private class MockAuthRepository(var currentUser: UserInfo? = null) : AuthRepository {
        private val sampleUserInfo: UserInfo by lazy {
            val jsonStr = """
            {
                "id": "user-uuid-123",
                "aud": "authenticated",
                "role": "authenticated",
                "email": "user@campus.edu",
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

        override suspend fun signIn(email: String, password: String): Resource<UserInfo> {
            currentUser = sampleUserInfo
            return Resource.Success(sampleUserInfo)
        }

        override suspend fun signUp(email: String, password: String): Resource<UserInfo> {
            currentUser = sampleUserInfo
            return Resource.Success(sampleUserInfo)
        }

        override suspend fun signOut(): Resource<Unit> {
            currentUser = null
            return Resource.Success(Unit)
        }

        override suspend fun getCurrentUser(): UserInfo? = currentUser
        override suspend fun restoreSession(): Resource<UserInfo?> = Resource.Success(currentUser)
    }

    private class MockProfileRepository : ProfileRepository {
        override suspend fun getProfile(userId: String): Resource<UserProfile> {
            return Resource.Success(UserProfile(id = userId, username = "testuser", displayName = "Test User"))
        }

        override suspend fun createProfile(userId: String, username: String, displayName: String): Resource<UserProfile> {
            return Resource.Success(UserProfile(id = userId, username = username, displayName = displayName))
        }

        override suspend fun updateProfile(profile: UserProfile): Resource<UserProfile> {
            return Resource.Success(profile)
        }
    }

    private class MockDeviceRepository : DeviceRepository {
        var lastRegisteredDevice: UserDevice? = null

        override suspend fun registerOrUpdateDevice(device: UserDevice): Resource<UserDevice> {
            val registered = device.copy(registrationId = 1234)
            lastRegisteredDevice = registered
            return Resource.Success(registered)
        }

        override suspend fun updateLastSeen(userId: String, deviceId: String): Resource<Unit> {
            return Resource.Success(Unit)
        }
    }

    private class CapturingPreKeySyncRepository(
        private val identityKeyManager: IdentityKeyManagerImpl,
        private val preKeyManager: PreKeyManagerImpl,
        var shouldFail: Boolean = false
    ) : PreKeySyncRepository {

        var publishedUserId: String? = null
        var publishedDeviceId: String? = null
        var publishedRegistrationId: Int? = null
        var publishedIdentityDto: DeviceIdentityKeyDto? = null
        var publishedSignedPreKeyDto: DeviceSignedPreKeyDto? = null
        var publishedOneTimePreKeyDtos: List<DeviceOneTimePreKeyDto> = emptyList()
        var publishCount = 0

        override suspend fun publishLocalPublicKeys(userId: String, deviceId: String, registrationId: Int): Resource<Unit> {
            publishCount++
            if (shouldFail) {
                return Resource.Error("Simulated prekey publication network error")
            }

            preKeyManager.initializePreKeys(deviceId)
            val pubIdentityKeyBase64 = identityKeyManager.getPublicIdentityKeyBase64()
                ?: return Resource.Error("Identity public key not available locally")

            publishedUserId = userId
            publishedDeviceId = deviceId
            publishedRegistrationId = registrationId

            publishedIdentityDto = DeviceIdentityKeyDto(
                deviceId = deviceId,
                userId = userId,
                identityPublicKey = pubIdentityKeyBase64
            )

            val localSpk = preKeyManager.getCurrentSignedPreKey(deviceId)
                ?: return Resource.Error("Signed prekey not available locally")

            val spkPublicKeyBase64 = Base64.getEncoder().encodeToString(localSpk.keyPair.publicKey.serialize())
            val spkSignatureBase64 = Base64.getEncoder().encodeToString(localSpk.signature)

            publishedSignedPreKeyDto = DeviceSignedPreKeyDto(
                deviceId = deviceId,
                userId = userId,
                keyId = localSpk.id,
                publicKey = spkPublicKeyBase64,
                signature = spkSignatureBase64,
                isActive = true
            )

            val availableOpks = preKeyManager.getAvailableOneTimePreKeys(deviceId)
            publishedOneTimePreKeyDtos = availableOpks.map { opk ->
                DeviceOneTimePreKeyDto(
                    deviceId = deviceId,
                    userId = userId,
                    keyId = opk.id,
                    publicKey = Base64.getEncoder().encodeToString(opk.keyPair.publicKey.serialize()),
                    isConsumed = false
                )
            }

            return Resource.Success(Unit)
        }

        override suspend fun rotateSignedPreKey(userId: String, deviceId: String): Resource<Unit> = Resource.Success(Unit)
        override suspend fun replenishOneTimePreKeys(userId: String, deviceId: String): Resource<Int> = Resource.Success(0)
        override suspend fun claimPreKeyBundle(recipientDeviceId: String): Resource<com.campuschat.app.domain.model.RemotePreKeyBundle> = Resource.Error("Not implemented")
        override suspend fun getRemoteUnconsumedPreKeyCount(deviceId: String): Resource<Int> = Resource.Success(100)
    }

    @Before
    fun setUp() {
        mockFilesDir = tempFolder.newFolder("files")
        fakeCryptoKeyManager = FakeCryptoKeyManager()
        val testContext = TestContext(mockFilesDir)
        identityKeyManager = IdentityKeyManagerImpl(testContext, fakeCryptoKeyManager)
        preKeyManager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)
    }

    @Test
    fun testIdentityPublicKeyIsPublished() = runBlocking {
        val deviceId = "test-device-uuid-1"
        val userId = "test-user-uuid-1"
        val syncRepo = CapturingPreKeySyncRepository(identityKeyManager, preKeyManager)

        val result = syncRepo.publishLocalPublicKeys(userId, deviceId, 1234)

        assertTrue(result is Resource.Success)
        assertNotNull(syncRepo.publishedIdentityDto)
        val dto = syncRepo.publishedIdentityDto!!
        assertEquals(deviceId, dto.deviceId)
        assertEquals(userId, dto.userId)
        assertTrue("Identity public key must not be blank", dto.identityPublicKey.isNotBlank())
    }

    @Test
    fun testSignedPreKeyIsPublishedAndActive() = runBlocking {
        val deviceId = "test-device-uuid-2"
        val userId = "test-user-uuid-2"
        val syncRepo = CapturingPreKeySyncRepository(identityKeyManager, preKeyManager)

        val result = syncRepo.publishLocalPublicKeys(userId, deviceId, 1234)

        assertTrue(result is Resource.Success)
        assertNotNull(syncRepo.publishedSignedPreKeyDto)
        val spkDto = syncRepo.publishedSignedPreKeyDto!!
        assertEquals(deviceId, spkDto.deviceId)
        assertEquals(userId, spkDto.userId)
        assertEquals(1, spkDto.keyId)
        assertTrue("Signed prekey public key must not be blank", spkDto.publicKey.isNotBlank())
        assertTrue("Signed prekey signature must not be blank", spkDto.signature.isNotBlank())
        assertTrue("Signed prekey must be active", spkDto.isActive)
    }

    @Test
    fun testOneTimePreKeysArePublished() = runBlocking {
        val deviceId = "test-device-uuid-3"
        val userId = "test-user-uuid-3"
        val syncRepo = CapturingPreKeySyncRepository(identityKeyManager, preKeyManager)

        val result = syncRepo.publishLocalPublicKeys(userId, deviceId, 1234)

        assertTrue(result is Resource.Success)
        val opkDtos = syncRepo.publishedOneTimePreKeyDtos
        assertEquals("Initial pool must publish 100 one-time prekeys", 100, opkDtos.size)
        opkDtos.forEachIndexed { index, opkDto ->
            assertEquals(deviceId, opkDto.deviceId)
            assertEquals(userId, opkDto.userId)
            assertEquals(index + 1, opkDto.keyId)
            assertTrue("OPK public key must not be blank", opkDto.publicKey.isNotBlank())
            assertFalse("OPK must not be consumed when published", opkDto.isConsumed)
        }
    }

    @Test
    fun testCorrectDeviceIdIsUsedForEveryUpload() = runBlocking {
        val deviceId = "device-uuid-strict-check"
        val userId = "user-uuid-strict-check"
        val syncRepo = CapturingPreKeySyncRepository(identityKeyManager, preKeyManager)

        syncRepo.publishLocalPublicKeys(userId, deviceId, 5678)

        assertEquals(deviceId, syncRepo.publishedDeviceId)
        assertEquals(userId, syncRepo.publishedUserId)
        assertEquals(deviceId, syncRepo.publishedIdentityDto?.deviceId)
        assertEquals(deviceId, syncRepo.publishedSignedPreKeyDto?.deviceId)
        syncRepo.publishedOneTimePreKeyDtos.forEach {
            assertEquals(deviceId, it.deviceId)
            assertEquals(userId, it.userId)
        }
    }

    @Test
    fun testPrivateKeysNeverLeaveLocalStorage() = runBlocking {
        val deviceId = "test-device-private-key-audit"
        val userId = "test-user-private-key-audit"

        val localIdentityKeyPair = identityKeyManager.getOrGenerateIdentity(deviceId)
        val initResult = preKeyManager.initializePreKeys(deviceId)

        val syncRepo = CapturingPreKeySyncRepository(identityKeyManager, preKeyManager)
        syncRepo.publishLocalPublicKeys(userId, deviceId, 1234)

        val privateIkBytes = localIdentityKeyPair.privateKey.serialize()
        val privateSpkBytes = initResult.signedPreKey.keyPair.privateKey.serialize()

        val rawPubIkBytes = Base64.getDecoder().decode(syncRepo.publishedIdentityDto!!.identityPublicKey)
        val rawPubSpkBytes = Base64.getDecoder().decode(syncRepo.publishedSignedPreKeyDto!!.publicKey)

        assertFalse("Public IK payload must not equal private IK bytes", privateIkBytes.contentEquals(rawPubIkBytes))
        assertFalse("Public SPK payload must not equal private SPK bytes", privateSpkBytes.contentEquals(rawPubSpkBytes))

        syncRepo.publishedOneTimePreKeyDtos.forEach { opkDto ->
            val rawPubOpkBytes = Base64.getDecoder().decode(opkDto.publicKey)
            assertFalse("Public OPK payload must not equal private IK bytes", privateIkBytes.contentEquals(rawPubOpkBytes))
        }

        // Verify local encrypted files exist
        assertTrue("campuschat_identity_store.bin must exist on device", File(mockFilesDir, "campuschat_identity_store.bin").exists())
        assertTrue("campuschat_prekey_store.bin must exist on device", File(mockFilesDir, "campuschat_prekey_store.bin").exists())
    }

    @Test
    fun testRepeatedRegistrationAndStartupIsSafe() = runBlocking {
        val deviceId = "test-device-startup-loop"
        val userId = "test-user-startup-loop"
        val syncRepo = CapturingPreKeySyncRepository(identityKeyManager, preKeyManager)

        val res1 = syncRepo.publishLocalPublicKeys(userId, deviceId, 100)
        assertTrue(res1 is Resource.Success)
        val firstIk = syncRepo.publishedIdentityDto?.identityPublicKey

        val res2 = syncRepo.publishLocalPublicKeys(userId, deviceId, 100)
        assertTrue(res2 is Resource.Success)
        val secondIk = syncRepo.publishedIdentityDto?.identityPublicKey

        val res3 = syncRepo.publishLocalPublicKeys(userId, deviceId, 100)
        assertTrue(res3 is Resource.Success)

        assertEquals("Repeated publication must retain consistent identity public key", firstIk, secondIk)
        assertEquals(3, syncRepo.publishCount)
    }

    @Test
    fun testPublicationFailureIsReportedSafely() = runBlocking {
        val deviceId = "test-device-fail"
        val userId = "test-user-fail"
        val syncRepo = CapturingPreKeySyncRepository(identityKeyManager, preKeyManager, shouldFail = true)

        val result = syncRepo.publishLocalPublicKeys(userId, deviceId, 100)

        assertTrue("Failure must be captured as Resource.Error", result is Resource.Error)
        val errorMsg = (result as Resource.Error).message
        assertTrue(errorMsg.contains("Simulated prekey publication network error"))
        assertFalse("Error message must not contain private key material", errorMsg.contains("private", ignoreCase = true))
    }

    @Test
    fun testProvisioningInvokedOnLoginUseCase() = runBlocking {
        val authRepo = MockAuthRepository()
        val profileRepo = MockProfileRepository()
        val deviceRepo = MockDeviceRepository()
        val syncRepo = CapturingPreKeySyncRepository(identityKeyManager, preKeyManager)

        val loginUseCase = LoginUseCase(authRepo, profileRepo, deviceRepo, syncRepo)
        val result = loginUseCase("user@campus.edu", "secret123")

        assertTrue(result is Resource.Success)
        assertEquals(1, syncRepo.publishCount)
        assertEquals("user-uuid-123", syncRepo.publishedUserId)
        assertNotNull(syncRepo.publishedIdentityDto)
        assertNotNull(syncRepo.publishedSignedPreKeyDto)
        assertEquals(100, syncRepo.publishedOneTimePreKeyDtos.size)
    }

    @Test
    fun testProvisioningInvokedOnRegisterUseCase() = runBlocking {
        val authRepo = MockAuthRepository()
        val profileRepo = MockProfileRepository()
        val deviceRepo = MockDeviceRepository()
        val syncRepo = CapturingPreKeySyncRepository(identityKeyManager, preKeyManager)

        val registerUseCase = RegisterUseCase(authRepo, profileRepo, deviceRepo, syncRepo)
        val result = registerUseCase("newuser@campus.edu", "secret123", "newuser", "New User")

        assertTrue(result is Resource.Success)
        assertEquals(1, syncRepo.publishCount)
        assertEquals("user-uuid-123", syncRepo.publishedUserId)
        assertNotNull(syncRepo.publishedIdentityDto)
        assertNotNull(syncRepo.publishedSignedPreKeyDto)
        assertEquals(100, syncRepo.publishedOneTimePreKeyDtos.size)
    }

    @Test
    fun testProvisioningInvokedOnRestoreSessionUseCase() = runBlocking {
        val authRepo = MockAuthRepository()
        val signInRes = authRepo.signIn("existing@campus.edu", "password")
        com.campuschat.app.core.session.SessionManager.setAuthenticated((signInRes as Resource.Success).data)
        val syncRepo = CapturingPreKeySyncRepository(identityKeyManager, preKeyManager)

        val profileRepo = MockProfileRepository()
        val deviceRepo = MockDeviceRepository()

        val restoreUseCase = RestoreSessionUseCase(authRepo, profileRepo, deviceRepo, syncRepo)
        val result = restoreUseCase()

        assertTrue(result is Resource.Success)
        assertEquals(1, syncRepo.publishCount)
        assertEquals("user-uuid-123", syncRepo.publishedUserId)
        assertNotNull(syncRepo.publishedIdentityDto)
        assertNotNull(syncRepo.publishedSignedPreKeyDto)
        assertEquals(100, syncRepo.publishedOneTimePreKeyDtos.size)
    }

    @Test
    fun probePreKeyBundleConstructors() {
        println("=== PREKEYBUNDLE CONSTRUCTORS ===")
        org.signal.libsignal.protocol.state.PreKeyBundle::class.java.constructors.forEachIndexed { i, c ->
            println("PreKeyBundle Constructor #$i: ${c.parameterTypes.joinToString { it.simpleName }}")
        }
        println("=== KYBERPREKEYRECORD CONSTRUCTORS ===")
        org.signal.libsignal.protocol.state.KyberPreKeyRecord::class.java.constructors.forEachIndexed { i, c ->
            println("KyberPreKeyRecord Constructor #$i: ${c.parameterTypes.joinToString { it.simpleName }}")
        }
        org.signal.libsignal.protocol.state.KyberPreKeyRecord::class.java.declaredMethods.forEach { m ->
            println("KyberPreKeyRecord method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
        }
        println("=== KEMKEYPAIR METHODS ===")
        org.signal.libsignal.protocol.kem.KEMKeyPair::class.java.declaredMethods.forEach { m ->
            println("KEMKeyPair method: ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
        }
        println("=== KEMKEYTYPE VALUES ===")
        org.signal.libsignal.protocol.kem.KEMKeyType.values().forEach { t ->
            println("KEMKeyType value: $t")
        }
    }
}
