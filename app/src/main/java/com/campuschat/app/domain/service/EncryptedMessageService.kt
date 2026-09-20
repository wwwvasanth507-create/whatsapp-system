package com.campuschat.app.domain.service

import com.campuschat.app.core.crypto.CampusChatSessionStore
import com.campuschat.app.core.crypto.CampusChatSignalProtocolStore
import com.campuschat.app.domain.model.DecryptionResult
import com.campuschat.app.domain.model.EncryptedMessageEnvelope
import com.campuschat.app.domain.model.EncryptionResult
import com.campuschat.app.domain.repository.AuthRepository
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.LegacyMessageException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore

interface EncryptedMessageService {
    suspend fun encryptMessage(
        senderDeviceId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        recipientRegistrationId: Int,
        plaintext: String
    ): EncryptionResult

    suspend fun decryptMessage(
        envelope: EncryptedMessageEnvelope
    ): DecryptionResult
}

class EncryptedMessageServiceImpl(
    private val authRepository: AuthRepository,
    private val protocolStore: CampusChatSignalProtocolStore,
    private val sessionStore: CampusChatSessionStore
) : EncryptedMessageService {

    companion object {
        const val MAX_TEXT_MESSAGE_CHARS = 10_000
    }

    override suspend fun encryptMessage(
        senderDeviceId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        recipientRegistrationId: Int,
        plaintext: String
    ): EncryptionResult {
        // 1. Authenticated user validation
        val currentUser = authRepository.getCurrentUser()
            ?: return EncryptionResult.AuthenticationRequired

        // 2. Input validation
        if (plaintext.isBlank()) {
            return EncryptionResult.InvalidInput("Message text cannot be empty or blank")
        }
        if (plaintext.length > MAX_TEXT_MESSAGE_CHARS) {
            return EncryptionResult.InvalidInput("Message exceeds maximum allowed length of $MAX_TEXT_MESSAGE_CHARS characters")
        }

        // 3. Resolve target SignalProtocolAddress
        val recipientAddress = try {
            SignalProtocolAddress(recipientUserId, recipientRegistrationId)
        } catch (e: Exception) {
            return EncryptionResult.InvalidInput("Invalid recipient SignalProtocolAddress: ${e.message}")
        }

        // 4. Verify existing established X3DH session
        if (!protocolStore.containsSession(recipientAddress)) {
            return EncryptionResult.SessionNotFound
        }

        // 5. Verify identity trust
        val remoteIdentity = protocolStore.getIdentity(recipientAddress)
        if (remoteIdentity != null) {
            val isTrusted = protocolStore.isTrustedIdentity(
                recipientAddress,
                remoteIdentity,
                IdentityKeyStore.Direction.SENDING
            )
            if (!isTrusted) {
                return EncryptionResult.IdentityChanged(recipientUserId, recipientDeviceId)
            }
        }

        // 6. Perform Double Ratchet encryption via official libsignal SessionCipher
        return try {
            val cipher = SessionCipher(protocolStore, recipientAddress)
            val ciphertextMessage = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))
            val serializedBytes = ciphertextMessage.serialize()
            val ciphertextBase64 = encodeBase64(serializedBytes)

            val envelope = EncryptedMessageEnvelope(
                senderUserId = currentUser.id,
                senderDeviceId = senderDeviceId,
                senderRegistrationId = protocolStore.getLocalRegistrationId(),
                recipientUserId = recipientUserId,
                recipientDeviceId = recipientDeviceId,
                recipientRegistrationId = recipientRegistrationId,
                messageType = ciphertextMessage.type,
                ciphertextBase64 = ciphertextBase64
            )

            EncryptionResult.Success(envelope)
        } catch (e: UntrustedIdentityException) {
            EncryptionResult.IdentityChanged(recipientUserId, recipientDeviceId)
        } catch (e: NoSessionException) {
            EncryptionResult.SessionNotFound
        } catch (e: Exception) {
            EncryptionResult.CryptoFailure("Encryption error: ${sanitizeErrorMessage(e)}")
        }
    }

    override suspend fun decryptMessage(
        envelope: EncryptedMessageEnvelope
    ): DecryptionResult {
        // 1. Validate envelope payload
        if (envelope.ciphertextBase64.isBlank()) {
            return DecryptionResult.InvalidCiphertext("Ciphertext Base64 is blank")
        }

        val senderAddress = try {
            SignalProtocolAddress(envelope.senderUserId, envelope.senderRegistrationId)
        } catch (e: Exception) {
            return DecryptionResult.InvalidCiphertext("Invalid sender SignalProtocolAddress: ${e.message}")
        }

        // 2. Verify existing session for normal WHISPER_TYPE messages.
        // Note: PREKEY_TYPE messages establish the session on the recipient side during decryption.
        if (envelope.messageType != CiphertextMessage.PREKEY_TYPE && !protocolStore.containsSession(senderAddress)) {
            return DecryptionResult.SessionNotFound
        }

        // 3. Decode ciphertext bytes
        val ciphertextBytes = try {
            decodeBase64(envelope.ciphertextBase64)
        } catch (e: Exception) {
            return DecryptionResult.InvalidCiphertext("Base64 decoding failed")
        }

        if (ciphertextBytes.isEmpty()) {
            return DecryptionResult.InvalidCiphertext("Decoded ciphertext is empty")
        }

        // 4. Verify identity trust prior to decryption
        val remoteIdentity = protocolStore.getIdentity(senderAddress)
        if (remoteIdentity != null) {
            val isTrusted = protocolStore.isTrustedIdentity(
                senderAddress,
                remoteIdentity,
                IdentityKeyStore.Direction.RECEIVING
            )
            if (!isTrusted) {
                return DecryptionResult.IdentityChanged(envelope.senderUserId, envelope.senderDeviceId)
            }
        }

        // 5. Perform Double Ratchet decryption via official libsignal SessionCipher
        return try {
            val cipher = SessionCipher(protocolStore, senderAddress)
            val plaintextBytes = when (envelope.messageType) {
                CiphertextMessage.PREKEY_TYPE -> {
                    val preKeyMsg = PreKeySignalMessage(ciphertextBytes)
                    cipher.decrypt(preKeyMsg)
                }
                CiphertextMessage.WHISPER_TYPE -> {
                    val signalMsg = SignalMessage(ciphertextBytes)
                    cipher.decrypt(signalMsg)
                }
                else -> {
                    return DecryptionResult.InvalidCiphertext("Unsupported Signal message type: ${envelope.messageType}")
                }
            }

            val plaintext = String(plaintextBytes, Charsets.UTF_8)
            DecryptionResult.Success(plaintext)
        } catch (e: UntrustedIdentityException) {
            DecryptionResult.IdentityChanged(envelope.senderUserId, envelope.senderDeviceId)
        } catch (e: DuplicateMessageException) {
            DecryptionResult.DuplicateOrAlreadyProcessed("Duplicate message counter: ${e.message}")
        } catch (e: InvalidMessageException) {
            DecryptionResult.InvalidCiphertext("Invalid Signal ciphertext structure: ${sanitizeErrorMessage(e)}")
        } catch (e: org.signal.libsignal.protocol.InvalidKeyException) {
            DecryptionResult.InvalidCiphertext("Invalid Signal key in ciphertext: ${sanitizeErrorMessage(e)}")
        } catch (e: org.signal.libsignal.protocol.InvalidKeyIdException) {
            DecryptionResult.InvalidCiphertext("Invalid Signal key ID in ciphertext: ${sanitizeErrorMessage(e)}")
        } catch (e: org.signal.libsignal.protocol.InvalidVersionException) {
            DecryptionResult.InvalidCiphertext("Invalid Signal version in ciphertext: ${sanitizeErrorMessage(e)}")
        } catch (e: LegacyMessageException) {
            DecryptionResult.InvalidCiphertext("Legacy Signal message unsupported: ${sanitizeErrorMessage(e)}")
        } catch (e: NoSessionException) {
            DecryptionResult.SessionNotFound
        } catch (e: Exception) {
            DecryptionResult.CryptoFailure("${e.javaClass.simpleName}: ${sanitizeErrorMessage(e)}")
        }
    }

    private fun encodeBase64(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (e: Throwable) {
            try {
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) ?: ""
            } catch (e2: Throwable) {
                ""
            }
        }
    }

    private fun decodeBase64(base64Str: String): ByteArray {
        val trimmed = base64Str.trim()
        if (trimmed.isEmpty()) return ByteArray(0)
        return try {
            java.util.Base64.getDecoder().decode(trimmed)
        } catch (e1: Throwable) {
            try {
                java.util.Base64.getUrlDecoder().decode(trimmed)
            } catch (e2: Throwable) {
                try {
                    val res = android.util.Base64.decode(trimmed, android.util.Base64.NO_WRAP)
                    res ?: ByteArray(0)
                } catch (e3: Throwable) {
                    ByteArray(0)
                }
            }
        }
    }

    private fun sanitizeErrorMessage(e: Exception): String {
        val raw = e.message ?: "Cryptographic operation failure"
        return raw.substringBefore("\nURL:").substringBefore("\nHeaders:").trim()
    }
}
