package com.campuschat.app.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IdentityKeyManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockFilesDir: File
    private lateinit var fakeCryptoKeyManager: FakeCryptoKeyManager

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

    @Before
    fun setUp() {
        mockFilesDir = tempFolder.newFolder("files")
        fakeCryptoKeyManager = FakeCryptoKeyManager()
    }

    @Test
    fun testFirstInitializationCreatesIdentityKeyPair() {
        val testContext = TestContext(mockFilesDir)
        val manager = IdentityKeyManagerImpl(testContext, fakeCryptoKeyManager)

        val deviceId = "test-device-uuid-12345"
        val identityKeyPair = manager.getOrGenerateIdentity(deviceId)

        assertNotNull("Identity key pair must not be null", identityKeyPair)
        assertNotNull("Public identity key must not be null", identityKeyPair.publicKey)
        assertNotNull("Private identity key must not be null", identityKeyPair.privateKey)
        assertTrue("Has identity must return true", manager.hasIdentity())
        assertEquals(deviceId, manager.getBoundDeviceId())
    }

    @Test
    fun testSecondInitializationPreservesIdentityAndDoesNotRegenerate() {
        val testContext = TestContext(mockFilesDir)
        val manager1 = IdentityKeyManagerImpl(testContext, fakeCryptoKeyManager)

        val deviceId = "test-device-uuid-12345"
        val identity1 = manager1.getOrGenerateIdentity(deviceId)

        // Simulate app restart / second manager initialization
        val manager2 = IdentityKeyManagerImpl(testContext, fakeCryptoKeyManager)
        val identity2 = manager2.getOrGenerateIdentity(deviceId)

        assertArrayEquals(
            "Identity key pair must remain identical across app launches",
            identity1.publicKey.serialize(),
            identity2.publicKey.serialize()
        )
    }

    @Test
    fun testDeviceMismatchGeneratesFreshIdentityAndDoesNotReuse() {
        val testContext = TestContext(mockFilesDir)
        val manager1 = IdentityKeyManagerImpl(testContext, fakeCryptoKeyManager)

        val deviceId1 = "device-uuid-11111"
        val identity1 = manager1.getOrGenerateIdentity(deviceId1)

        // Simulate new app installation with different device ID
        val manager2 = IdentityKeyManagerImpl(testContext, fakeCryptoKeyManager)
        val deviceId2 = "device-uuid-22222"
        val identity2 = manager2.getOrGenerateIdentity(deviceId2)

        val isIdentical = identity1.publicKey.serialize().contentEquals(identity2.publicKey.serialize())
        assertFalse("Identity key must NOT be reused when device ID mismatches", isIdentical)
        assertEquals(deviceId2, manager2.getBoundDeviceId())
    }

    @Test
    fun testPrivateKeyIsEncryptedAndNotStoredInPlaintext() {
        val testContext = TestContext(mockFilesDir)
        val manager = IdentityKeyManagerImpl(testContext, fakeCryptoKeyManager)
        val deviceId = "test-device-uuid-12345"
        val identity = manager.getOrGenerateIdentity(deviceId)

        val storeFile = File(mockFilesDir, "campuschat_identity_store.bin")
        assertTrue("Protected identity file must exist", storeFile.exists())

        val rawFileBytes = storeFile.readBytes()
        val privateKeyBytes = identity.privateKey.serialize()

        // Verify that raw identity private key bytes do not appear unencrypted in the file
        var foundMatch = false
        for (i in 0..(rawFileBytes.size - privateKeyBytes.size)) {
            var match = true
            for (j in privateKeyBytes.indices) {
                if (rawFileBytes[i + j] != privateKeyBytes[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                foundMatch = true
                break
            }
        }

        assertTrue("Private key must NEVER be written to storage as unencrypted plaintext", !foundMatch)
    }

    @Test
    fun testPublicIdentityKeyBase64Export() {
        val testContext = TestContext(mockFilesDir)
        val manager = IdentityKeyManagerImpl(testContext, fakeCryptoKeyManager)
        manager.getOrGenerateIdentity("device-777")

        val pubKeyBase64 = manager.getPublicIdentityKeyBase64()
        assertNotNull(pubKeyBase64)
        assertTrue("Base64 string must not be empty", pubKeyBase64!!.isNotBlank())
    }

    private class TestContext(private val filesDirFile: File) : android.content.ContextWrapper(null) {
        override fun getFilesDir(): File = filesDirFile
    }
}
