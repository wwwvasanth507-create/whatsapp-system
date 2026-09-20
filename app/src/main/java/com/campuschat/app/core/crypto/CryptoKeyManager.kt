package com.campuschat.app.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedDataBlob(
    val iv: ByteArray,
    val ciphertext: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedDataBlob
        if (!iv.contentEquals(other.iv)) return false
        if (!ciphertext.contentEquals(other.ciphertext)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = iv.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }
}

interface CryptoKeyManager {
    fun encryptData(plaintext: ByteArray): EncryptedDataBlob
    fun decryptData(blob: EncryptedDataBlob): ByteArray
    fun getSecurityLevel(): SecurityLevel
    fun hasMasterKey(): Boolean
}

class CryptoKeyManagerImpl(
    private val context: Context,
    private val masterKeyAlias: String = MASTER_KEY_ALIAS
) : CryptoKeyManager {

    private val detector = KeystoreSecurityDetector(context)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "CampusChatMasterWrappingKey"
        private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }

    init {
        ensureMasterKeyExists()
    }

    @Synchronized
    private fun ensureMasterKeyExists() {
        if (!keyStore.containsAlias(masterKeyAlias)) {
            generateMasterKey()
        }
    }

    private fun generateMasterKey() {
        // Step 1: Try generating key with StrongBox if available
        if (detector.hasStrongBoxSupport()) {
            try {
                createMasterKeySpec(useStrongBox = true)
                return
            } catch (e: Exception) {
                // Catch StrongBoxUnavailableException or unsupported algorithm on specific hardware
                // Graceful fallback to standard TEE / AndroidKeyStore
            }
        }

        // Step 2: Standard Android KeyStore TEE / Software fallback
        try {
            createMasterKeySpec(useStrongBox = false)
        } catch (e: Exception) {
            throw CryptoException.KeyGenerationException(
                "Failed to generate Android Keystore master wrapping key", e
            )
        }
    }

    private fun createMasterKeySpec(useStrongBox: Boolean) {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )

        val builder = KeyGenParameterSpec.Builder(
            masterKeyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (useStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true)
        }

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }

    private fun getMasterKey(): SecretKey {
        val entry = keyStore.getEntry(masterKeyAlias, null) as? KeyStore.SecretKeyEntry
            ?: throw CryptoException.KeystoreUnavailableException(
                "Master key entry '$masterKeyAlias' not found in Android KeyStore"
            )
        return entry.secretKey
    }

    override fun encryptData(plaintext: ByteArray): EncryptedDataBlob {
        require(plaintext.isNotEmpty()) { "Plaintext cannot be empty" }
        return try {
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            EncryptedDataBlob(iv = iv, ciphertext = ciphertext)
        } catch (e: Exception) {
            throw CryptoException.EncryptionException("Failed to encrypt data blob", e)
        }
    }

    override fun decryptData(blob: EncryptedDataBlob): ByteArray {
        require(blob.iv.isNotEmpty() && blob.ciphertext.isNotEmpty()) {
            "Invalid encrypted data blob (empty IV or ciphertext)"
        }
        return try {
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, blob.iv)
            cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec)
            cipher.doFinal(blob.ciphertext)
        } catch (e: Exception) {
            throw CryptoException.DecryptionException(
                "Failed to decrypt data blob (corrupted or tampered ciphertext)", e
            )
        }
    }

    override fun getSecurityLevel(): SecurityLevel {
        return try {
            val key = getMasterKey()
            detector.getEffectiveSecurityLevel(key)
        } catch (e: Exception) {
            SecurityLevel.UNKNOWN
        }
    }

    override fun hasMasterKey(): Boolean {
        return keyStore.containsAlias(masterKeyAlias)
    }
}
