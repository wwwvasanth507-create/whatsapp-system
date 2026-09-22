package com.campuschat.app.domain.model

data class UserDevice(
    val id: String? = null,
    val userId: String,
    val deviceName: String,
    val platform: String = "android",
    val registrationId: Int? = null,
    val fcmToken: String? = null,
    val lastSeenAt: String? = null
)
