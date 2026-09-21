package com.campuschat.app.domain.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.UserProfile

/**
 * Repository interface for discovering campus users and resolving active recipient device IDs
 * for Double Ratchet X3DH encrypted messaging session establishment.
 */
interface UserDiscoveryRepository {
    suspend fun searchUsers(query: String): Resource<List<UserProfile>>
    suspend fun getRecipientDevices(userId: String): Resource<List<UserDevice>>
}
