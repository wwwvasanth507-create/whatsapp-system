package com.campuschat.app.domain.service

import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.local.entity.MessageEntity
import com.campuschat.app.domain.model.EncryptionResult
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.LocalChatRepository
import com.campuschat.app.domain.repository.MessageTransportRepository
import java.util.UUID

interface MessageOutboxService {
    suspend fun queueTextMessage(
        senderDeviceId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        recipientRegistrationId: Int,
        plaintext: String
    ): MessageEntity?

    suspend fun processPendingOutboxMessages(
        localAccountId: String,
        senderDeviceId: String
    ): Resource<Int>

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
    private val localChatRepository: LocalChatRepository? = null,
    private val x3dhSessionService: X3DHSessionService? = null
) : MessageOutboxService {

    override suspend fun queueTextMessage(
        senderDeviceId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        recipientRegistrationId: Int,
        plaintext: String
    ): MessageEntity? {
        val currentUser = authRepository.getCurrentUser() ?: return null
        val localAccountId = currentUser.id
        val messageId = UUID.randomUUID().toString()
        val conversationId = "${localAccountId}_${recipientUserId}_${recipientDeviceId}"
        val timestamp = System.currentTimeMillis()

        val entity = MessageEntity(
            id = messageId,
            localAccountId = localAccountId,
            conversationId = conversationId,
            senderUserId = currentUser.id,
            senderDeviceId = senderDeviceId,
            recipientUserId = recipientUserId,
            recipientDeviceId = recipientDeviceId,
            direction = "SENT",
            content = plaintext,
            timestamp = timestamp,
            deliveryState = "QUEUED",
            readState = true
        )

        localChatRepository?.saveMessage(entity)
        return entity
    }

    override suspend fun processPendingOutboxMessages(
        localAccountId: String,
        senderDeviceId: String
    ): Resource<Int> {
        val repo = localChatRepository ?: return Resource.Success(0)
        val pendingOutbound = repo.getPendingOutboundMessages(localAccountId)
        var successCount = 0

        for (msg in pendingOutbound) {
            repo.updateMessageDeliveryState(msg.id, "ENCRYPTING")

            var targetRegistrationId = 1
            if (x3dhSessionService != null) {
                val sessionRes = x3dhSessionService.establishOutboundSession(msg.recipientDeviceId)
                if (sessionRes is com.campuschat.app.domain.model.X3DHSessionResult.SessionEstablished) {
                    targetRegistrationId = sessionRes.address.deviceId
                }
            }

            val encryptResult = encryptedMessageService.encryptMessage(
                senderDeviceId = senderDeviceId,
                recipientUserId = msg.recipientUserId,
                recipientDeviceId = msg.recipientDeviceId,
                recipientRegistrationId = targetRegistrationId,
                plaintext = msg.content
            )

            when (encryptResult) {
                is EncryptionResult.Success -> {
                    repo.updateMessageDeliveryState(msg.id, "UPLOADING")
                    when (transportRepository.enqueueEncryptedMessage(encryptResult.envelope)) {
                        is Resource.Success -> {
                            repo.updateMessageDeliveryState(msg.id, "SENT")
                            successCount++
                        }
                        is Resource.Error -> {
                            repo.updateMessageDeliveryState(msg.id, "FAILED")
                        }
                        else -> {
                            repo.updateMessageDeliveryState(msg.id, "FAILED")
                        }
                    }
                }
                else -> {
                    repo.updateMessageDeliveryState(msg.id, "FAILED")
                }
            }
        }

        return Resource.Success(successCount)
    }

    override suspend fun sendEncryptedTextMessage(
        senderDeviceId: String,
        recipientUserId: String,
        recipientDeviceId: String,
        recipientRegistrationId: Int,
        plaintext: String
    ): Resource<String> {
        val currentUser = authRepository.getCurrentUser()
            ?: return Resource.Error("Authentication required")

        var targetRegistrationId = recipientRegistrationId
        if (x3dhSessionService != null) {
            val sessionRes = x3dhSessionService.establishOutboundSession(recipientDeviceId)
            if (sessionRes is com.campuschat.app.domain.model.X3DHSessionResult.SessionEstablished) {
                targetRegistrationId = sessionRes.address.deviceId
            }
        }

        val encryptResult = encryptedMessageService.encryptMessage(
            senderDeviceId = senderDeviceId,
            recipientUserId = recipientUserId,
            recipientDeviceId = recipientDeviceId,
            recipientRegistrationId = targetRegistrationId,
            plaintext = plaintext
        )

        val envelope = when (encryptResult) {
            is EncryptionResult.Success -> encryptResult.envelope
            is EncryptionResult.SessionNotFound -> return Resource.Error("Session not established with recipient device")
            is EncryptionResult.IdentityChanged -> return Resource.Error("Recipient identity has changed. Verification required.")
            is EncryptionResult.InvalidInput -> return Resource.Error("Invalid input: ${encryptResult.reason}")
            is EncryptionResult.AuthenticationRequired -> return Resource.Error("Authentication required")
            is EncryptionResult.CryptoFailure -> return Resource.Error("Cryptographic failure: ${encryptResult.reason}")
        }

        val messageId = UUID.randomUUID().toString()
        val localAccountId = currentUser.id
        val conversationId = "${localAccountId}_${recipientUserId}_${recipientDeviceId}"
        val timestamp = System.currentTimeMillis()

        localChatRepository?.saveMessage(
            MessageEntity(
                id = messageId,
                localAccountId = localAccountId,
                conversationId = conversationId,
                senderUserId = currentUser.id,
                senderDeviceId = senderDeviceId,
                recipientUserId = recipientUserId,
                recipientDeviceId = recipientDeviceId,
                direction = "SENT",
                content = plaintext,
                timestamp = timestamp,
                deliveryState = "UPLOADING",
                readState = true
            )
        )

        return when (val transportRes = transportRepository.enqueueEncryptedMessage(envelope)) {
            is Resource.Success -> {
                localChatRepository?.updateMessageDeliveryState(messageId, "SENT")
                Resource.Success(transportRes.data)
            }
            is Resource.Error -> {
                localChatRepository?.updateMessageDeliveryState(messageId, "FAILED")
                Resource.Error("Transport upload failed: ${transportRes.message}")
            }
            else -> {
                localChatRepository?.updateMessageDeliveryState(messageId, "FAILED")
                Resource.Error("Transport upload failed")
            }
        }
    }
}
