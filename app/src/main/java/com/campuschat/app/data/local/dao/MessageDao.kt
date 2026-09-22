package com.campuschat.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.campuschat.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE localAccountId = :localAccountId AND conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForAccountAndConversation(localAccountId: String, conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("UPDATE messages SET deliveryState = :deliveryState WHERE id = :messageId")
    suspend fun updateDeliveryState(messageId: String, deliveryState: String)

    @Query("SELECT * FROM messages WHERE localAccountId = :localAccountId AND deliveryState IN ('QUEUED', 'PENDING', 'FAILED') ORDER BY timestamp ASC")
    suspend fun getPendingOutboundMessages(localAccountId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)
}
