package com.campuschat.app.presentation.auth.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.usecase.LoginUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isSuccess: Boolean = false
)

class LoginViewModel(
    private val loginUseCase: LoginUseCase
) : ViewModel() {

    var email by mutableStateOf("")
        private set

    var password by mutableStateOf("")
        private set

    var emailError by mutableStateOf<String?>(null)
        private set

    var passwordError by mutableStateOf<String?>(null)
        private set

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

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

    fun login(onSuccess: () -> Unit) {
        // Local Validation
        var isValid = true
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            emailError = "Please enter a valid email address."
            isValid = false
        }
        if (password.isBlank()) {
            passwordError = "Password cannot be empty."
            isValid = false
        }

        if (!isValid) return

        viewModelScope.launch {
            _uiState.value = LoginUiState(isLoading = true)

            when (val result = loginUseCase(email, password)) {
                is Resource.Success -> {
                    _uiState.value = LoginUiState(isSuccess = true)
                    onSuccess()
                }
                is Resource.Error -> {
                    _uiState.value = LoginUiState(errorMessage = result.message)
                }
                else -> {
                    _uiState.value = LoginUiState()
                }
            }
        }
    }
}
