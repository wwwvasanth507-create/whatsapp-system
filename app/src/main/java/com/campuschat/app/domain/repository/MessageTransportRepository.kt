package com.campuschat.app.domain.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.EncryptedMessageEnvelope
import com.campuschat.app.domain.model.PendingTransportMessage

interface MessageTransportRepository {
    suspend fun enqueueEncryptedMessage(
        envelope: EncryptedMessageEnvelope,
        ttlSeconds: Int = 2592000
    ): Resource<String>

    suspend fun fetchPendingMessages(
        deviceId: String
    ): Resource<List<PendingTransportMessage>>

    suspend fun acknowledgeMessage(
        messageId: String
    ): Resource<Boolean>

    suspend fun deleteDeliveredMessage(
        messageId: String
    ): Resource<Boolean>
}
