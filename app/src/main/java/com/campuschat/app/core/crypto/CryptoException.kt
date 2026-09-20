package com.campuschat.app.core.crypto

/**
 * Sealed exception hierarchy for CampusChat cryptographic operations.
 * High-security rule: Exception messages must NEVER expose raw key bytes, passwords, or secrets.
 */
sealed class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class KeystoreUnavailableException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    class KeyGenerationException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    class CorruptedKeyStateException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    class HardwareSecurityException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    class EncryptionException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
    class DecryptionException(message: String, cause: Throwable? = null) : CryptoException(message, cause)
}
