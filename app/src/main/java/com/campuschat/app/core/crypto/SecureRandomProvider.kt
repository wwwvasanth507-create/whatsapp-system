package com.campuschat.app.core.crypto

import java.security.NoSuchAlgorithmException
import java.security.SecureRandom

/**
 * Abstraction for cryptographically strong random generation.
 * Guarantees high-entropy random bytes for salt, IVs, and key generation.
 */
class SecureRandomProvider {

    private val secureRandom: SecureRandom by lazy {
        try {
            SecureRandom.getInstanceStrong()
        } catch (e: NoSuchAlgorithmException) {
            // Fallback to standard SecureRandom if Strong instance is unavailable
            SecureRandom()
        }
    }

    /**
     * Generates an array of cryptographically strong random bytes.
     * @param size Number of bytes to generate.
     */
    fun nextBytes(size: Int): ByteArray {
        require(size > 0) { "Byte array size must be greater than 0" }
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * Generates a random positive integer ID within [0, Int.MAX_VALUE).
     */
    fun nextIntId(): Int {
        val raw = secureRandom.nextInt()
        return if (raw == Int.MIN_VALUE) 0 else if (raw < 0) -raw else raw
    }
}
