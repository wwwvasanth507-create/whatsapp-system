package com.campuschat.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.campuschat.app.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE localAccountId = :localAccountId ORDER BY lastMessageTimestamp DESC")
    fun getConversationsForAccount(localAccountId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE localAccountId = :localAccountId AND recipientUserId = :recipientUserId AND recipientDeviceId = :recipientDeviceId LIMIT 1")
    suspend fun getConversation(localAccountId: String, recipientUserId: String, recipientDeviceId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversationById(conversationId: String): ConversationEntity?

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)
}
