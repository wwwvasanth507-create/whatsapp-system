package com.campuschat.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String, // format: "${recipientUserId}_${recipientDeviceId}"
    val recipientUserId: String,
    val recipientDeviceId: String,
    val recipientUsername: String,
    val recipientDisplayName: String,
    val lastMessageSnippet: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int = 0
)
