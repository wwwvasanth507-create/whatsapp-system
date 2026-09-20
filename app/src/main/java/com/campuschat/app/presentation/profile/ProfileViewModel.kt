package com.campuschat.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuschat.app.core.result.Resource
import com.campuschat.app.core.session.SessionManager
import com.campuschat.app.domain.model.UserProfile
import com.campuschat.app.domain.usecase.GetProfileUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val errorMessage: String? = null
)

class ProfileViewModel(
    private val getProfileUseCase: GetProfileUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    fun loadProfile() {
        val userId = SessionManager.getCurrentUserId() ?: return
        viewModelScope.launch {
            _uiState.value = ProfileUiState(isLoading = true)
            when (val res = getProfileUseCase(userId)) {
                is Resource.Success -> _uiState.value = ProfileUiState(isLoading = false, profile = res.data)
                is Resource.Error -> _uiState.value = ProfileUiState(isLoading = false, errorMessage = res.message)
                else -> {}
            }
        }
    }
}
