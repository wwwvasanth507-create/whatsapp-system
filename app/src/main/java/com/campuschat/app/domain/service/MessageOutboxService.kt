package com.campuschat.app.domain.service

import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.local.entity.MessageEntity
import com.campuschat.app.domain.model.EncryptionResult
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.LocalChatRepository
import com.campuschat.app.domain.repository.MessageTransportRepository
import java.util.UUID

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
    private val transportRepository: MessageTransportRepository,
    private val localChatRepository: LocalChatRepository? = null
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

        val messageId = UUID.randomUUID().toString()
        val conversationId = "${recipientUserId}_${recipientDeviceId}"
        val timestamp = System.currentTimeMillis()

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
                    is Resource.Success -> {
                        localChatRepository?.saveMessage(
                            MessageEntity(
                                id = messageId,
                                conversationId = conversationId,
                                senderUserId = currentUser.id,
                                senderDeviceId = senderDeviceId,
                                recipientUserId = recipientUserId,
                                recipientDeviceId = recipientDeviceId,
                                direction = "SENT",
                                content = plaintext,
                                timestamp = timestamp,
                                deliveryState = "SENT",
                                readState = true
                            )
                        )
                        Resource.Success(transportRes.data)
                    }
                    is Resource.Error -> {
                        localChatRepository?.saveMessage(
                            MessageEntity(
                                id = messageId,
                                conversationId = conversationId,
                                senderUserId = currentUser.id,
                                senderDeviceId = senderDeviceId,
                                recipientUserId = recipientUserId,
                                recipientDeviceId = recipientDeviceId,
                                direction = "SENT",
                                content = plaintext,
                                timestamp = timestamp,
                                deliveryState = "FAILED",
                                readState = true
                            )
                        )
                        Resource.Error("Transport upload failed: ${transportRes.message}")
                    }
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
