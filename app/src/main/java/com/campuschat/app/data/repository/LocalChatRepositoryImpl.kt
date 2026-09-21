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

    override fun getAllConversations(): Flow<List<ConversationEntity>> {
        return conversationDao.getAllConversations()
    }

    override suspend fun getConversation(recipientUserId: String, recipientDeviceId: String): ConversationEntity? {
        val id = "${recipientUserId}_${recipientDeviceId}"
        return conversationDao.getConversationById(id)
    }

    override suspend fun saveConversation(conversation: ConversationEntity) {
        conversationDao.insertOrUpdateConversation(conversation)
    }

    override fun getMessagesForConversation(recipientUserId: String, recipientDeviceId: String): Flow<List<MessageEntity>> {
        val conversationId = "${recipientUserId}_${recipientDeviceId}"
        return messageDao.getMessagesForConversation(conversationId)
    }

    override suspend fun saveMessage(message: MessageEntity) {
        messageDao.insertOrUpdateMessage(message)
        // Also update conversation last message summary
        val conversationId = message.conversationId
        val existing = conversationDao.getConversationById(conversationId)
        val updatedConv = ConversationEntity(
            id = conversationId,
            recipientUserId = message.recipientUserId,
            recipientDeviceId = message.recipientDeviceId,
            recipientUsername = existing?.recipientUsername ?: message.recipientUserId.take(8),
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
}
