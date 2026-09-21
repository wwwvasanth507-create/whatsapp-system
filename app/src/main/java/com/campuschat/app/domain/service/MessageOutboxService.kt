package com.campuschat.app.domain.service

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.EncryptionResult
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.MessageTransportRepository

interface MessageOutboxService {
    suspend fun sendEncryptedTextMessage(
        senderDeviceId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        recipientRegistrationId: Int,
        plaintext: String
    ): Resource<String>
}

class MessageOutboxServiceImpl(
    private val authRepository: AuthRepository,
    private val encryptedMessageService: EncryptedMessageService,
    private val transportRepository: MessageTransportRepository
) : MessageOutboxService {

    override suspend fun sendEncryptedTextMessage(
        senderDeviceId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        recipientRegistrationId: Int,
        plaintext: String
    ): Resource<String> {
        val currentUser = authRepository.getCurrentUser()
            ?: return Resource.Error("Authentication required")

        val encryptResult = encryptedMessageService.encryptMessage(
            senderDeviceId = senderDeviceId,
            recipientUserId = recipientUserId,
            recipientDeviceId = recipientDeviceId,
            recipientRegistrationId = recipientRegistrationId,
            plaintext = plaintext
        )

        return when (encryptResult) {
            is EncryptionResult.Success -> {
                when (val transportRes = transportRepository.enqueueEncryptedMessage(encryptResult.envelope)) {
                    is Resource.Success -> Resource.Success(transportRes.data)
                    is Resource.Error -> Resource.Error("Transport upload failed: ${transportRes.message}")
                    else -> Resource.Error("Transport upload failed")
                }
            }
            is EncryptionResult.SessionNotFound -> Resource.Error("Session not established with recipient device")
            is EncryptionResult.IdentityChanged -> Resource.Error("Recipient identity has changed. Verification required.")
            is EncryptionResult.InvalidInput -> Resource.Error("Invalid input: ${encryptResult.reason}")
            is EncryptionResult.AuthenticationRequired -> Resource.Error("Authentication required")
            is EncryptionResult.CryptoFailure -> Resource.Error("Cryptographic failure: ${encryptResult.reason}")
        }
    }
}
