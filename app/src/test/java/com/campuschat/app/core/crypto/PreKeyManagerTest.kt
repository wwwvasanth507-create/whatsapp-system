package com.campuschat.app.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class PreKeyManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockFilesDir: File
    private lateinit var fakeCryptoKeyManager: FakeCryptoKeyManager
    private lateinit var identityKeyManager: IdentityKeyManager

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
    }

    @Test
    fun testInitialSignedPreKeyGeneration() {
        val testContext = TestContext(mockFilesDir)
        val preKeyManager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        val result = preKeyManager.initializePreKeys(deviceId)

        assertNotNull("Signed prekey must not be null", result.signedPreKey)
        assertEquals("Initial signed prekey ID must be 1", 1, result.signedPreKey.id)
        assertNotNull("Signed prekey signature must not be null", result.signedPreKey.signature)
        assertTrue("Signed prekey signature must be non-empty", result.signedPreKey.signature.isNotEmpty())
    }

    @Test
    fun testSignedPreKeyPersistsAcrossReload() {
        val testContext = TestContext(mockFilesDir)
        val deviceId = "device-12345"

        val manager1 = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)
        val spk1 = manager1.initializePreKeys(deviceId).signedPreKey

        // Reload from disk with fresh manager instance
        val manager2 = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)
        val spk2 = manager2.getCurrentSignedPreKey(deviceId)

        assertNotNull("Loaded signed prekey must not be null", spk2)
        assertEquals("Signed prekey ID must match", spk1.id, spk2!!.id)
        assertArrayEquals(
            "Signed prekey public key must persist identically across restarts",
            spk1.keyPair.publicKey.serialize(),
            spk2.keyPair.publicKey.serialize()
        )
    }

    @Test
    fun testSignedPreKeyDoesNotRegenerateOnRestart() {
        val testContext = TestContext(mockFilesDir)
        val deviceId = "device-12345"

        val manager1 = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)
        val spk1 = manager1.initializePreKeys(deviceId).signedPreKey

        val manager2 = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)
        val spk2 = manager2.initializePreKeys(deviceId).signedPreKey

        assertArrayEquals(
            "Signed prekey must NOT regenerate upon re-initialization",
            spk1.serialize(),
            spk2.serialize()
        )
    }

    @Test
    fun testInitialOneTimePreKeyPoolGeneration() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        val result = manager.initializePreKeys(deviceId)

        assertEquals("Initial OPK pool size must be 100", 100, result.initialOneTimePreKeyCount)
        assertEquals("Available OPK count must be 100", 100, manager.getAvailableOneTimePreKeyCount(deviceId))
    }

    @Test
    fun testOneTimePreKeyIdsAreUnique() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        manager.initializePreKeys(deviceId)

        val opks = manager.getAvailableOneTimePreKeys(deviceId)
        val ids = opks.map { it.id }

        assertEquals("OPK count must match list size", 100, ids.size)
        assertEquals("All OPK IDs in pool must be distinct", 100, ids.toSet().size)
    }

    @Test
    fun testAvailableOneTimePreKeyCount() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        manager.initializePreKeys(deviceId)

        assertEquals(100, manager.getAvailableOneTimePreKeyCount(deviceId))

        manager.consumeLocalOneTimePreKey(deviceId, 1)
        assertEquals(99, manager.getAvailableOneTimePreKeyCount(deviceId))
    }

    @Test
    fun testConsumeOneTimePreKey() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        manager.initializePreKeys(deviceId)

        val consumedRecord = manager.consumeLocalOneTimePreKey(deviceId, 1)
        assertNotNull("Consumed OPK record must not be null", consumedRecord)
        assertEquals(1, consumedRecord!!.id)

        assertFalse("Consumed OPK must not exist in available list", manager.getAvailableOneTimePreKeys(deviceId).any { it.id == 1 })
    }

    @Test
    fun testConsumedOneTimePreKeyCannotBeReturnedAsAvailable() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        manager.initializePreKeys(deviceId)

        manager.consumeLocalOneTimePreKey(deviceId, 5)

        val available = manager.getAvailableOneTimePreKeys(deviceId)
        assertFalse("OPK #5 must not be returned in available list", available.any { it.id == 5 })
    }

    @Test
    fun testDoubleConsumptionFailsSafely() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        manager.initializePreKeys(deviceId)

        val firstConsume = manager.consumeLocalOneTimePreKey(deviceId, 10)
        assertNotNull("First consumption must succeed", firstConsume)

        val secondConsume = manager.consumeLocalOneTimePreKey(deviceId, 10)
        assertNull("Second consumption of same OPK must return null", secondConsume)
    }

    @Test
    fun testReplenishmentAtLowWatermark() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        manager.initializePreKeys(deviceId)

        // Consume 81 OPKs so available count becomes 19 (below LOW_WATERMARK = 20)
        for (id in 1..81) {
            manager.consumeLocalOneTimePreKey(deviceId, id)
        }

        assertEquals(19, manager.getAvailableOneTimePreKeyCount(deviceId))

        val replenishedCount = manager.replenishOneTimePreKeys(deviceId)
        assertEquals(50, replenishedCount)
        assertEquals(69, manager.getAvailableOneTimePreKeyCount(deviceId))
    }

    @Test
    fun testSignedPreKeyRotationCreatesNewKey() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        val originalSpk = manager.initializePreKeys(deviceId).signedPreKey
        assertEquals(1, originalSpk.id)

        val rotatedSpk = manager.rotateSignedPreKey(deviceId)
        assertEquals(2, rotatedSpk.id)
        assertNotEquals(
            "Rotated SPK public key must be different from original",
            originalSpk.keyPair.publicKey.serialize(),
            rotatedSpk.keyPair.publicKey.serialize()
        )

        // Historical SPK #1 must still be retrievable
        val historicalSpk = manager.getSignedPreKey(deviceId, 1)
        assertNotNull("Historical SPK #1 must remain accessible after rotation", historicalSpk)
    }

    @Test
    fun testPrivatePreKeyDataIsEncryptedAtRest() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        val spk = manager.initializePreKeys(deviceId).signedPreKey

        val storeFile = File(mockFilesDir, "campuschat_prekey_store.bin")
        assertTrue("Encrypted prekey store file must exist", storeFile.exists())

        val rawFileBytes = storeFile.readBytes()
        val privateKeyBytes = spk.keyPair.privateKey.serialize()

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

        assertFalse("Private prekey bytes must NEVER be written to disk in unencrypted plaintext", foundMatch)
    }

    @Test
    fun testWrongDeviceBindingDoesNotReuseKeys() {
        val testContext = TestContext(mockFilesDir)
        val manager1 = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId1 = "device-11111"
        val spk1 = manager1.initializePreKeys(deviceId1).signedPreKey

        // Reload with different device ID
        val manager2 = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)
        val deviceId2 = "device-22222"
        val spk2 = manager2.initializePreKeys(deviceId2).signedPreKey

        assertNotEquals(
            "PreKeys must NOT be reused when device ID mismatches",
            spk1.keyPair.publicKey.serialize(),
            spk2.keyPair.publicKey.serialize()
        )
    }

    @Test
    fun testCorruptedStoreRecovery() {
        val testContext = TestContext(mockFilesDir)
        val manager1 = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        manager1.initializePreKeys(deviceId)

        val storeFile = File(mockFilesDir, "campuschat_prekey_store.bin")
        assertTrue(storeFile.exists())

        // Corrupt the store file
        storeFile.writeBytes(ByteArray(128) { 0xFF.toByte() })

        val manager2 = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)
        val recoveredResult = manager2.initializePreKeys(deviceId)

        assertNotNull("Manager must safely recover from corrupted store file and generate fresh keys", recoveredResult.signedPreKey)
        assertEquals(100, recoveredResult.initialOneTimePreKeyCount)
    }

    @Test
    fun testConcurrentLocalPreKeyOperations() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        val deviceId = "device-12345"
        manager.initializePreKeys(deviceId)

        val threadCount = 10
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        val consumedIds = java.util.Collections.synchronizedList(ArrayList<Int>())

        for (i in 1..threadCount) {
            val keyToConsume = i
            executor.execute {
                try {
                    val record = manager.consumeLocalOneTimePreKey(deviceId, keyToConsume)
                    if (record != null) {
                        consumedIds.add(record.id)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertEquals("All thread consumption requests must succeed cleanly without race conditions", threadCount, consumedIds.size)
        assertEquals("Consumed OPK count must be distinct", threadCount, consumedIds.toSet().size)
    }

    @Test
    fun testNoPrivateKeyMaterialInExceptions() {
        val testContext = TestContext(mockFilesDir)
        val manager = PreKeyManagerImpl(testContext, fakeCryptoKeyManager, identityKeyManager)

        try {
            manager.initializePreKeys("")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            assertFalse("Exception message must not contain private key material", msg.contains("private", ignoreCase = true))
        }
    }
}
