package com.campuschat.app.domain.service

import com.campuschat.app.core.result.Resource
import com.campuschat.app.data.local.entity.MessageEntity
import com.campuschat.app.domain.model.DecryptionResult
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.LocalChatRepository
import com.campuschat.app.domain.repository.MessageTransportRepository

data class ReceivedMessageResult(
    val messageId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val plaintext: String
)

sealed class ProcessPendingResult {
    data class Processed(val messages: List<ReceivedMessageResult>, val skippedCount: Int) : ProcessPendingResult()
    data class Error(val message: String) : ProcessPendingResult()
}

interface PendingMessageService {
    suspend fun fetchAndDecryptPendingMessages(
        localDeviceId: String
    ): ProcessPendingResult
}

class PendingMessageServiceImpl(
    private val authRepository: AuthRepository,
    private val encryptedMessageService: EncryptedMessageService,
    private val transportRepository: MessageTransportRepository,
    private val localChatRepository: LocalChatRepository? = null
) : PendingMessageService {

    override suspend fun fetchAndDecryptPendingMessages(
        localDeviceId: String
    ): ProcessPendingResult {
        val currentUser = authRepository.getCurrentUser()
            ?: return ProcessPendingResult.Error("Authentication required")

        val pendingRes = transportRepository.fetchPendingMessages(localDeviceId)
        if (pendingRes is Resource.Error) {
            return ProcessPendingResult.Error(pendingRes.message)
        }

        val pendingList = (pendingRes as Resource.Success).data
        val decryptedMessages = mutableListOf<ReceivedMessageResult>()
        var skippedCount = 0

        for (pendingItem in pendingList) {
            val decryptResult = encryptedMessageService.decryptMessage(pendingItem.envelope)
            when (decryptResult) {
                is DecryptionResult.Success -> {
                    val received = ReceivedMessageResult(
                        messageId = pendingItem.messageId,
                        senderUserId = pendingItem.envelope.senderUserId,
                        senderDeviceId = pendingItem.envelope.senderDeviceId,
                        plaintext = decryptResult.plaintext
                    )
                    decryptedMessages.add(received)

                    val localAccountId = currentUser.id
                    val conversationId = "${localAccountId}_${pendingItem.envelope.senderUserId}_${pendingItem.envelope.senderDeviceId}"
                    localChatRepository?.saveMessage(
                        MessageEntity(
                            id = pendingItem.messageId,
                            localAccountId = localAccountId,
                            conversationId = conversationId,
                            senderUserId = pendingItem.envelope.senderUserId,
                            senderDeviceId = pendingItem.envelope.senderDeviceId,
                            recipientUserId = currentUser.id,
                            recipientDeviceId = localDeviceId,
                            direction = "RECEIVED",
                            content = decryptResult.plaintext,
                            timestamp = System.currentTimeMillis(),
                            deliveryState = "DELIVERED",
                            readState = false
                        )
                    )

                    // DELETE-AFTER-SUCCESS: Acknowledge server only after successful local decryption & persistence
                    transportRepository.acknowledgeMessage(pendingItem.messageId)
                }
                is DecryptionResult.DuplicateOrAlreadyProcessed -> {
                    // Idempotent acknowledgement for duplicate messages already processed
                    transportRepository.acknowledgeMessage(pendingItem.messageId)
                    skippedCount++
                }
                is DecryptionResult.SessionNotFound,
                is DecryptionResult.IdentityChanged,
                is DecryptionResult.InvalidCiphertext,
                is DecryptionResult.CryptoFailure -> {
                    // CRITICAL SECURITY RULE: DO NOT acknowledge or delete failed ciphertext
                    skippedCount++
                }
            }
        }

        return ProcessPendingResult.Processed(decryptedMessages, skippedCount)
    }
}
