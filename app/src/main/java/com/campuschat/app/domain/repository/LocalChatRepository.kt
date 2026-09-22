package com.campuschat.app.domain.repository

import com.campuschat.app.data.local.entity.ConversationEntity
import com.campuschat.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

interface LocalChatRepository {
    fun getAllConversations(localAccountId: String): Flow<List<ConversationEntity>>
    suspend fun getConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String): ConversationEntity?
    suspend fun saveConversation(conversation: ConversationEntity)
    
    fun getMessagesForConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String): Flow<List<MessageEntity>>
    suspend fun saveMessage(message: MessageEntity)
    suspend fun updateMessageDeliveryState(messageId: String, deliveryState: String)
    suspend fun getMessageById(messageId: String): MessageEntity?
    suspend fun getPendingOutboundMessages(localAccountId: String): List<MessageEntity>
}
