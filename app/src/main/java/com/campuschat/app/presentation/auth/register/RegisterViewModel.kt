package com.campuschat.app.presentation.auth.register

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.usecase.RegisterUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RegisterUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
)

class RegisterViewModel(
    private val registerUseCase: RegisterUseCase
) : ViewModel() {

    var email by mutableStateOf("")
        private set

    var password by mutableStateOf("")
        private set

    var username by mutableStateOf("")
        private set

    var displayName by mutableStateOf("")
        private set

    var emailError by mutableStateOf<String?>(null)
        private set

    var passwordError by mutableStateOf<String?>(null)
        private set

    var usernameError by mutableStateOf<String?>(null)
        private set

    var displayNameError by mutableStateOf<String?>(null)
        private set

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    fun onEmailChanged(value: String) {
        email = value
        emailError = null
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun onPasswordChanged(value: String) {
        password = value
        passwordError = null
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun onUsernameChanged(value: String) {
        username = value
        usernameError = null
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun onDisplayNameChanged(value: String) {
        displayName = value
        displayNameError = null
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun register(onSuccess: () -> Unit) {
        var isValid = true

        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            emailError = "Valid email is required."
            isValid = false
        }
        if (password.length < 6) {
            passwordError = "Password must be at least 6 characters."
            isValid = false
        }
        if (username.trim().length < 3) {
            usernameError = "Username must be at least 3 characters."
            isValid = false
        }
        if (displayName.trim().isBlank()) {
            displayNameError = "Display name is required."
            isValid = false
        }

        if (!isValid) return

        viewModelScope.launch {
            _uiState.value = RegisterUiState(isLoading = true)

            when (val result = registerUseCase(email, password, username, displayName)) {
                is Resource.Success -> {
                    _uiState.value = RegisterUiState(isSuccess = true)
                    onSuccess()
                }
                is Resource.Error -> {
                    _uiState.value = RegisterUiState(errorMessage = result.message)
                }
                else -> {
                    _uiState.value = RegisterUiState()
                }
            }
        }
    }
}
