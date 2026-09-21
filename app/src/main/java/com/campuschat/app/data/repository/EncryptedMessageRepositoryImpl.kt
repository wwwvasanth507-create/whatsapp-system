package com.campuschat.app.data.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.EncryptedMessageEnvelope
import com.campuschat.app.domain.model.PendingTransportMessage
import com.campuschat.app.domain.repository.EncryptedMessageRepository
import com.campuschat.app.domain.repository.MessageTransportRepository

/**
 * Implementation of EncryptedMessageRepository delegating transport operations to MessageTransportRepository.
 * Ensures zero plaintext or private key material enters the network transport layer.
 */
class EncryptedMessageRepositoryImpl(
    private val transportRepository: MessageTransportRepository
) : EncryptedMessageRepository {

    override suspend fun sendEncryptedMessage(
        envelope: EncryptedMessageEnvelope,
        ttlSeconds: Int
    ): Resource<String> {
        return transportRepository.enqueueEncryptedMessage(envelope, ttlSeconds)
    }

    override suspend fun fetchPendingMessages(
        deviceId: String
    ): Resource<List<PendingTransportMessage>> {
        return transportRepository.fetchPendingMessages(deviceId)
    }

    override suspend fun markMessageDelivered(
        messageId: String
    ): Resource<Boolean> {
        return transportRepository.acknowledgeMessage(messageId)
    }

    override suspend fun markMessageRead(
        messageId: String
    ): Resource<Boolean> {
        return transportRepository.acknowledgeMessage(messageId)
    }
}
