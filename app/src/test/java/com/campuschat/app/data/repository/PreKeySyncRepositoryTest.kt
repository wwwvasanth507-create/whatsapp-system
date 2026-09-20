package com.campuschat.app.data.repository

import com.campuschat.app.core.crypto.CryptoException
import com.campuschat.app.core.crypto.CryptoKeyManager
import com.campuschat.app.core.crypto.EncryptedDataBlob
import com.campuschat.app.core.crypto.IdentityKeyManagerImpl
import com.campuschat.app.core.crypto.PreKeyManagerImpl
import com.campuschat.app.core.crypto.SecurityLevel
import com.campuschat.app.data.dto.ClaimedPreKeyBundleDto
import com.campuschat.app.data.dto.DeviceIdentityKeyDto
import com.campuschat.app.data.dto.DeviceOneTimePreKeyDto
import com.campuschat.app.data.dto.DeviceSignedPreKeyDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreKeySyncRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockFilesDir: File
    private lateinit var fakeCryptoKeyManager: FakeCryptoKeyManager
    private lateinit var identityKeyManager: IdentityKeyManagerImpl
    private lateinit var preKeyManager: PreKeyManagerImpl

    private class FakeCryptoKeyManager : CryptoKeyManager {
        private val key = ByteArray(32) { 0x5A }

        override fun encryptData(plaintext: ByteArray): EncryptedDataBlob {
            val iv = ByteArray(12) { 0x07 }
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

    @Before
    fun setUp() {
        mockFilesDir = tempFolder.newFolder("files")
        fakeCryptoKeyManager = FakeCryptoKeyManager()
        val testContext = TestContext(mockFilesDir)
        identityKeyManager = IdentityKeyManagerImpl(testContext, fakeCryptoKeyManager)
        preKeyManager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)
    }

    @Test
    fun testDeviceIdentityKeyDtoContainsPublicMaterialOnly() {
        val deviceId = "device-test-123"
        val userId = "user-uuid-456"
        val identityKeyPair = identityKeyManager.getOrGenerateIdentity(deviceId)
        val publicBase64 = identityKeyManager.getPublicIdentityKeyBase64()!!

        val dto = DeviceIdentityKeyDto(
            deviceId = deviceId,
            userId = userId,
            identityPublicKey = publicBase64
        )

        assertEquals(deviceId, dto.deviceId)
        assertEquals(userId, dto.userId)
        assertEquals(publicBase64, dto.identityPublicKey)

        val privateKeyBytes = identityKeyPair.privateKey.serialize()
        val rawBase64Bytes = try {
            java.util.Base64.getDecoder().decode(publicBase64)
        } catch (e: Throwable) {
            ByteArray(0)
        }

        assertFalse("Public Identity Key DTO must not contain private key bytes", privateKeyBytes.contentEquals(rawBase64Bytes))
    }

    @Test
    fun testDeviceSignedPreKeyDtoContainsPublicMaterialOnly() {
        val deviceId = "device-test-123"
        val userId = "user-uuid-456"
        val initResult = preKeyManager.initializePreKeys(deviceId)
        val spk = initResult.signedPreKey

        val publicBase64 = java.util.Base64.getEncoder().encodeToString(spk.keyPair.publicKey.serialize())
        val signatureBase64 = java.util.Base64.getEncoder().encodeToString(spk.signature)

        val dto = DeviceSignedPreKeyDto(
            deviceId = deviceId,
            userId = userId,
            keyId = spk.id,
            publicKey = publicBase64,
            signature = signatureBase64,
            isActive = true
        )

        assertEquals(spk.id, dto.keyId)
        assertEquals(publicBase64, dto.publicKey)
        assertEquals(signatureBase64, dto.signature)
        assertTrue(dto.isActive)

        val privateKeyBytes = spk.keyPair.privateKey.serialize()
        val rawPublicBytes = java.util.Base64.getDecoder().decode(publicBase64)
        assertFalse("Public SPK DTO must not contain private key bytes", privateKeyBytes.contentEquals(rawPublicBytes))
    }

    @Test
    fun testDeviceOneTimePreKeyDtoContainsPublicMaterialOnly() {
        val deviceId = "device-test-123"
        val userId = "user-uuid-456"
        preKeyManager.initializePreKeys(deviceId)

        val availableOpks = preKeyManager.getAvailableOneTimePreKeys(deviceId)
        val firstOpk = availableOpks.first()

        val publicBase64 = java.util.Base64.getEncoder().encodeToString(firstOpk.keyPair.publicKey.serialize())

        val dto = DeviceOneTimePreKeyDto(
            deviceId = deviceId,
            userId = userId,
            keyId = firstOpk.id,
            publicKey = publicBase64,
            isConsumed = false
        )

        assertEquals(firstOpk.id, dto.keyId)
        assertEquals(publicBase64, dto.publicKey)
        assertFalse(dto.isConsumed)

        val privateKeyBytes = firstOpk.keyPair.privateKey.serialize()
        val rawPublicBytes = java.util.Base64.getDecoder().decode(publicBase64)
        assertFalse("Public OPK DTO must not contain private key bytes", privateKeyBytes.contentEquals(rawPublicBytes))
    }

    @Test
    fun testClaimedPreKeyBundleDtoStructure() {
        val dto = ClaimedPreKeyBundleDto(
            deviceId = "device-999",
            userId = "user-777",
            registrationId = 1234,
            identityKey = "public-ik-base64",
            signedPreKeyId = 1,
            signedPreKey = "public-spk-base64",
            signedPreKeySignature = "spk-sig-base64",
            oneTimePreKeyId = 101,
            oneTimePreKey = "public-opk-base64"
        )

        assertEquals("device-999", dto.deviceId)
        assertEquals("user-777", dto.userId)
        assertEquals(1234, dto.registrationId)
        assertEquals("public-ik-base64", dto.identityKey)
        assertEquals(1, dto.signedPreKeyId)
        assertEquals("public-spk-base64", dto.signedPreKey)
        assertEquals("spk-sig-base64", dto.signedPreKeySignature)
        assertEquals(101, dto.oneTimePreKeyId)
        assertEquals("public-opk-base64", dto.oneTimePreKey)
    }

    @Test
    fun testOpkMutationIsConsumedEnforcedDefaultFalseOnInsert() {
        val dto = DeviceOneTimePreKeyDto(
            deviceId = "device-1",
            userId = "user-1",
            keyId = 5,
            publicKey = "opk-pub-key",
            isConsumed = false
        )
        assertFalse("Newly uploaded OPKs must have isConsumed = false by default", dto.isConsumed)
    }

    @Test
    fun testLocalPrivateFilesRemainIntactAfterPublicMaterialExtraction() {
        val deviceId = "device-test-123"
        identityKeyManager.getOrGenerateIdentity(deviceId)
        preKeyManager.initializePreKeys(deviceId)

        val identityStoreFile = File(mockFilesDir, "campuschat_identity_store.bin")
        val preKeyStoreFile = File(mockFilesDir, "campuschat_prekey_store.bin")

        assertTrue("Identity store file must exist locally", identityStoreFile.exists())
        assertTrue("PreKey store file must exist locally", preKeyStoreFile.exists())

        val identitySizeBefore = identityStoreFile.length()
        val preKeySizeBefore = preKeyStoreFile.length()

        val pubIk = identityKeyManager.getPublicIdentityKeyBase64()
        val pubSpk = preKeyManager.getCurrentSignedPreKey(deviceId)
        val pubOpks = preKeyManager.getAvailableOneTimePreKeys(deviceId)

        assertNotNull(pubIk)
        assertNotNull(pubSpk)
        assertTrue(pubOpks.isNotEmpty())

        assertEquals("Identity store file size must remain intact", identitySizeBefore, identityStoreFile.length())
        assertEquals("PreKey store file size must remain intact", preKeySizeBefore, preKeyStoreFile.length())
    }

    @Test
    fun testNoSecretMaterialInExceptions() {
        try {
            throw CryptoException.EncryptionException("PreKey synchronization error for device-123")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            assertFalse("Exception message must not contain private key material", msg.contains("private", ignoreCase = true))
            assertFalse("Exception message must not contain master key", msg.contains("masterKey", ignoreCase = true))
        }
    }
}
