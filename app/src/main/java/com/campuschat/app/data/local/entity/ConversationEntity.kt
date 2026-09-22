package com.campuschat.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["localAccountId"])]
)
data class ConversationEntity(
    @PrimaryKey val id: String, // format: "${localAccountId}_${recipientUserId}_${recipientDeviceId}"
    val localAccountId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val recipientUsername: String,
    val recipientDisplayName: String,
    val lastMessageSnippet: String,
    val lastMessageTimestamp: Long,
    val unreadCount: Int = 0
)
