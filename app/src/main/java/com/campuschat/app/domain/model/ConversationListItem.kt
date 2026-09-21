package com.campuschat.app.domain.model

data class ConversationListItem(
    val recipientUserId: String,
    val recipientDeviceId: String,
    val recipientDisplayName: String,
    val recipientUsername: String,
    val lastMessagePreview: String? = null,
    val timestamp: String? = null,
    val unreadCount: Int = 0
)
