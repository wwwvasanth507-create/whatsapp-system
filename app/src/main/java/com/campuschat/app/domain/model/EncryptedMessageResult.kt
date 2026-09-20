package com.campuschat.app.domain.model

/**
 * Sealed domain result outcomes for Double Ratchet message encryption operations.
 */
sealed class EncryptionResult {
    data class Success(val envelope: EncryptedMessageEnvelope) : EncryptionResult()
    object SessionNotFound : EncryptionResult()
    data class IdentityChanged(val remoteUserId: String, val remoteDeviceId: String) : EncryptionResult()
    data class InvalidInput(val reason: String) : EncryptionResult()
    data class CryptoFailure(val reason: String) : EncryptionResult()
    object AuthenticationRequired : EncryptionResult()
}

/**
 * Sealed domain result outcomes for Double Ratchet message decryption operations.
 */
sealed class DecryptionResult {
    data class Success(val plaintext: String) : DecryptionResult()
    object SessionNotFound : DecryptionResult()
    data class IdentityChanged(val remoteUserId: String, val remoteDeviceId: String) : DecryptionResult()
    data class DuplicateOrAlreadyProcessed(val reason: String) : DecryptionResult()
    data class InvalidCiphertext(val reason: String) : DecryptionResult()
    data class CryptoFailure(val reason: String) : DecryptionResult()
}
