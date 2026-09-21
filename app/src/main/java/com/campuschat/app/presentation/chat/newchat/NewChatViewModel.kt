package com.campuschat.app.presentation.chat.newchat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.model.UserDevice
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.UserDiscoveryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UserDeviceSelection(
    val profile: UserProfile,
    val devices: List<UserDevice>,
    val isLoadingDevices: Boolean = false
)

data class NewChatUiState(
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val searchResults: List<UserDeviceSelection> = emptyList(),
    val errorMessage: String? = null
)

class NewChatViewModel(
    private val userDiscoveryRepository: UserDiscoveryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewChatUiState())
    val uiState: StateFlow<NewChatUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query, errorMessage = null)
        searchJob?.cancel()

        if (query.trim().isBlank()) {
            _uiState.value = _uiState.value.copy(isLoading = false, searchResults = emptyList())
            return
        }

        searchJob = viewModelScope.launch {
            delay(300) // Debounce search
            _uiState.value = _uiState.value.copy(isLoading = true)

            when (val result = userDiscoveryRepository.searchUsers(query)) {
                is Resource.Success -> {
                    val selections = result.data.map { profile ->
                        UserDeviceSelection(profile = profile, devices = emptyList(), isLoadingDevices = true)
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, searchResults = selections)

                    // Fetch recipient devices for discovered profiles
                    selections.forEach { selection ->
                        fetchDevicesForUser(selection.profile)
                    }
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
                else -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    private fun fetchDevicesForUser(profile: UserProfile) {
        viewModelScope.launch {
            when (val devRes = userDiscoveryRepository.getRecipientDevices(profile.id)) {
                is Resource.Success -> {
                    _uiState.value = _uiState.value.copy(
                        searchResults = _uiState.value.searchResults.map { item ->
                            if (item.profile.id == profile.id) {
                                item.copy(devices = devRes.data, isLoadingDevices = false)
                            } else {
                                item
                            }
                        }
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        searchResults = _uiState.value.searchResults.map { item ->
                            if (item.profile.id == profile.id) {
                                item.copy(isLoadingDevices = false)
                            } else {
                                item
                            }
                        }
                    )
                }
                else -> {}
            }
        }
    }
}
