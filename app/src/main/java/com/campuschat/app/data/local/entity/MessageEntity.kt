package com.campuschat.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val recipientUserId: String,
    val recipientDeviceId: String,
    val direction: String, // "SENT" or "RECEIVED"
    val content: String,
    val timestamp: Long,
    val deliveryState: String, // "PENDING", "ENCRYPTING", "UPLOADING", "SENT", "DELIVERED", "FAILED"
    val readState: Boolean = false
)
