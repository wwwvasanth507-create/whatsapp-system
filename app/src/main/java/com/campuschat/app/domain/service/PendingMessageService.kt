package com.campuschat.app.domain.service

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.DecryptionResult
import com.campuschat.app.domain.repository.AuthRepository
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
    private val transportRepository: MessageTransportRepository
) : PendingMessageService {

    override suspend fun fetchAndDecryptPendingMessages(
        localDeviceId: String
    ): ProcessPendingResult {
        authRepository.getCurrentUser()
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
                    decryptedMessages.add(
                        ReceivedMessageResult(
                            messageId = pendingItem.messageId,
                            senderUserId = pendingItem.envelope.senderUserId,
                            senderDeviceId = pendingItem.envelope.senderDeviceId,
                            plaintext = decryptResult.plaintext
                        )
                    )
                    // DELETE-AFTER-SUCCESS: Acknowledge server only after successful local decryption
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
