package com.campuschat.app.domain.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.UserDevice

interface DeviceRepository {
    suspend fun registerOrUpdateDevice(device: UserDevice): Resource<UserDevice>
    suspend fun updateLastSeen(userId: String, deviceId: String): Resource<Unit>
}
