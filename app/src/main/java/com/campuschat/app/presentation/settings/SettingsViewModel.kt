package com.campuschat.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.usecase.LogoutUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val deviceId: String = DeviceIdProvider.getDeviceId(),
    val deviceName: String = DeviceIdProvider.getDeviceName(),
    val platform: String = DeviceIdProvider.getPlatform(),
    val isLoggingOut: Boolean = false,
    val errorMessage: String? = null
)

class SettingsViewModel(
    private val logoutUseCase: LogoutUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingOut = true)
            when (val res = logoutUseCase()) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(isLoggingOut = false)
                    onLoggedOut()
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(isLoggingOut = false, errorMessage = res.message)
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isLoggingOut = false)
                }
            }
        }
    }
}
