package com.campuschat.app.domain.usecase

import android.util.Log
import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.result.Resource
import com.campuschat.app.core.session.AuthState
import com.campuschat.app.core.session.SessionManager
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.AuthRepository
import com.campuschat.app.domain.repository.DeviceRepository
import com.campuschat.app.domain.repository.PreKeySyncRepository
import com.campuschat.app.domain.repository.ProfileRepository

class RestoreSessionUseCase(
    private val authRepository: AuthRepository,
    private val profileRepository: ProfileRepository,
    private val deviceRepository: DeviceRepository,
    private val preKeySyncRepository: PreKeySyncRepository? = null
) {
    suspend operator fun invoke(): Resource<UserProfile?> {
        val currentUser = authRepository.getCurrentUser()
            ?: (SessionManager.checkSession() as? AuthState.Authenticated)?.user

        return if (currentUser != null) {
            val userId = currentUser.id
            val deviceId = DeviceIdProvider.getDeviceId()
            val device = UserDevice(
                id = deviceId,
                userId = userId,
                deviceName = DeviceIdProvider.getDeviceName(),
                platform = DeviceIdProvider.getPlatform()
            )
            val deviceResult = deviceRepository.registerOrUpdateDevice(device)
            val regId = (deviceResult as? Resource.Success)?.data?.registrationId ?: 1
            deviceRepository.updateLastSeen(userId, deviceId)

            if (preKeySyncRepository != null) {
                val syncResult = preKeySyncRepository.publishLocalPublicKeys(userId, deviceId, regId)
                if (syncResult is Resource.Error) {
                    Log.e("RestoreSessionUseCase", "Signal public key publishing non-fatal error: ${syncResult.message}")
                }
            }

            try {
                com.campuschat.app.presentation.navigation.AppViewModelFactory.realtimeMessageObserver?.startObserving()
                com.campuschat.app.presentation.navigation.AppViewModelFactory.pendingMessageService.fetchAndDecryptPendingMessages(deviceId)
                com.campuschat.app.presentation.navigation.AppViewModelFactory.outboxService.processPendingOutboxMessages(userId, deviceId)
            } catch (e: Exception) {
                // Non-fatal sync startup exception
            }

            when (val profileRes = profileRepository.getProfile(userId)) {
                is Resource.Success -> Resource.Success(profileRes.data)
                else -> Resource.Success(null)
            }
        } else {
            Resource.Success(null)
        }
    }
}
