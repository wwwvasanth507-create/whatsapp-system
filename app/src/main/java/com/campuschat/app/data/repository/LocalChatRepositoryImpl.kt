package com.campuschat.app.data.repository

import com.campuschat.app.data.local.dao.ConversationDao
import com.campuschat.app.data.local.dao.MessageDao
import com.campuschat.app.data.local.entity.ConversationEntity
import com.campuschat.app.data.local.entity.MessageEntity
import com.campuschat.app.domain.repository.LocalChatRepository
import kotlinx.coroutines.flow.Flow

class LocalChatRepositoryImpl(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : LocalChatRepository {

    override fun getAllConversations(localAccountId: String): Flow<List<ConversationEntity>> {
        return if (localAccountId.isNotBlank()) {
            conversationDao.getConversationsForAccount(localAccountId)
        } else {
            conversationDao.getAllConversations()
        }
    }

    override suspend fun getConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String): ConversationEntity? {
        if (localAccountId.isNotBlank()) {
            val conv = conversationDao.getConversation(localAccountId, recipientUserId, recipientDeviceId)
            if (conv != null) return conv
        }
        val fallbackId = if (localAccountId.isNotBlank()) "${localAccountId}_${recipientUserId}_${recipientDeviceId}" else "${recipientUserId}_${recipientDeviceId}"
        return conversationDao.getConversationById(fallbackId)
    }

    override suspend fun saveConversation(conversation: ConversationEntity) {
        conversationDao.insertOrUpdateConversation(conversation)
    }

    override fun getMessagesForConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String): Flow<List<MessageEntity>> {
        val conversationId = if (localAccountId.isNotBlank()) "${localAccountId}_${recipientUserId}_${recipientDeviceId}" else "${recipientUserId}_${recipientDeviceId}"
        return if (localAccountId.isNotBlank()) {
            messageDao.getMessagesForAccountAndConversation(localAccountId, conversationId)
        } else {
            messageDao.getMessagesForConversation(conversationId)
        }
    }

    override suspend fun saveMessage(message: MessageEntity) {
        messageDao.insertOrUpdateMessage(message)
        // Also update conversation last message summary
        val conversationId = message.conversationId
        val existing = conversationDao.getConversationById(conversationId)
        val targetRecipientUserId = if (message.direction == "SENT") message.recipientUserId else message.senderUserId
        val targetRecipientDeviceId = if (message.direction == "SENT") message.recipientDeviceId else message.senderDeviceId
        val updatedConv = ConversationEntity(
            id = conversationId,
            localAccountId = message.localAccountId,
            recipientUserId = targetRecipientUserId,
            recipientDeviceId = targetRecipientDeviceId,
            recipientUsername = existing?.recipientUsername ?: targetRecipientUserId.take(8),
            recipientDisplayName = existing?.recipientDisplayName ?: "Campus User",
            lastMessageSnippet = message.content,
            lastMessageTimestamp = message.timestamp,
            unreadCount = existing?.unreadCount ?: 0
        )
        conversationDao.insertOrUpdateConversation(updatedConv)
    }

    override suspend fun updateMessageDeliveryState(messageId: String, deliveryState: String) {
        messageDao.updateDeliveryState(messageId, deliveryState)
    }

    override suspend fun getMessageById(messageId: String): MessageEntity? {
        return messageDao.getMessageById(messageId)
    }

    override suspend fun getPendingOutboundMessages(localAccountId: String): List<MessageEntity> {
        return messageDao.getPendingOutboundMessages(localAccountId)
    }

    override suspend fun clearChatHistory(localAccountId: String, recipientUserId: String, recipientDeviceId: String) {
        val conversationId = if (localAccountId.isNotBlank()) "${localAccountId}_${recipientUserId}_${recipientDeviceId}" else "${recipientUserId}_${recipientDeviceId}"
        if (localAccountId.isNotBlank()) {
            messageDao.deleteMessagesForAccountAndConversation(localAccountId, conversationId)
            conversationDao.clearConversationSummary(localAccountId, conversationId)
        } else {
            messageDao.deleteMessagesForConversation(conversationId)
        }
    }

    override suspend fun deleteConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String) {
        val conversationId = if (localAccountId.isNotBlank()) "${localAccountId}_${recipientUserId}_${recipientDeviceId}" else "${recipientUserId}_${recipientDeviceId}"
        if (localAccountId.isNotBlank()) {
            messageDao.deleteMessagesForAccountAndConversation(localAccountId, conversationId)
            conversationDao.deleteConversationForAccount(localAccountId, conversationId)
        } else {
            messageDao.deleteMessagesForConversation(conversationId)
            conversationDao.deleteConversation(conversationId)
        }
    }
}
