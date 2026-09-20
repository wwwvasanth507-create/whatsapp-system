package com.campuschat.app.domain.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.RemotePreKeyBundle

interface PreKeySyncRepository {
    suspend fun publishLocalPublicKeys(userId: String, deviceId: String, registrationId: Int): Resource<Unit>
    suspend fun rotateSignedPreKey(userId: String, deviceId: String): Resource<Unit>
    suspend fun replenishOneTimePreKeys(userId: String, deviceId: String): Resource<Int>
    suspend fun claimPreKeyBundle(recipientDeviceId: String): Resource<RemotePreKeyBundle>
    suspend fun getRemoteUnconsumedPreKeyCount(deviceId: String): Resource<Int>
}
