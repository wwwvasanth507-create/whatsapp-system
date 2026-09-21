package com.campuschat.app.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuschat.app.core.device.DeviceIdProvider
import com.campuschat.app.core.result.Resource
import com.campuschat.app.core.session.SessionManager
import com.campuschat.app.domain.model.ConversationListItem
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.repository.LocalChatRepository
import com.campuschat.app.domain.usecase.GetProfileUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val deviceName: String = "",
    val deviceId: String = "",
    val conversations: List<ConversationListItem> = emptyList(),
    val errorMessage: String? = null
)

class HomeViewModel(
    private val getProfileUseCase: GetProfileUseCase,
    private val localChatRepository: LocalChatRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadData()
        observeConversations()
    }

    private fun observeConversations() {
        val repo = localChatRepository ?: return
        viewModelScope.launch {
            repo.getAllConversations()
                .catch { /* ignore */ }
                .collect { entityList ->
                    val items = entityList.map { entity ->
                        ConversationListItem(
                            recipientUserId = entity.recipientUserId,
                            recipientDeviceId = entity.recipientDeviceId,
                            recipientDisplayName = entity.recipientDisplayName,
                            recipientUsername = entity.recipientUsername,
                            lastMessagePreview = entity.lastMessageSnippet,
                            timestamp = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(entity.lastMessageTimestamp)),
                            unreadCount = entity.unreadCount
                        )
                    }
                    _uiState.value = _uiState.value.copy(conversations = items)
                }
        }
    }

    fun loadData() {
        val userId = SessionManager.getCurrentUserId()
        val deviceId = DeviceIdProvider.getDeviceId()
        val deviceName = DeviceIdProvider.getDeviceName()

        _uiState.value = _uiState.value.copy(
            deviceId = deviceId,
            deviceName = deviceName
        )

        if (userId != null) {
            viewModelScope.launch {
                when (val result = getProfileUseCase(userId)) {
                    is Resource.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            profile = result.data
                        )
                    }
                    is Resource.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                    }
                    else -> {}
                }
            }
        } else {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }
}
