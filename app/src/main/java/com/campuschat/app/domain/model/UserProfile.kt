package com.campuschat.app.domain.model

data class UserProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)
