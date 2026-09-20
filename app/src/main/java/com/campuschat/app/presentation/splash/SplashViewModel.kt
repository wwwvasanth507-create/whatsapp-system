package com.campuschat.app.presentation.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.usecase.RestoreSessionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SplashState {
    data object Loading : SplashState()
    data object Authenticated : SplashState()
    data object Unauthenticated : SplashState()
}

class SplashViewModel(
    private val restoreSessionUseCase: RestoreSessionUseCase
) : ViewModel() {

    private val _state = MutableStateFlow<SplashState>(SplashState.Loading)
    val state: StateFlow<SplashState> = _state.asStateFlow()

    init {
        checkSession()
    }

    fun checkSession() {
        viewModelScope.launch {
            _state.value = SplashState.Loading
            when (val res = restoreSessionUseCase()) {
                is Resource.Success -> {
                    if (res.data != null) {
                        _state.value = SplashState.Authenticated
                    } else {
                        _state.value = SplashState.Unauthenticated
                    }
                }
                else -> {
                    _state.value = SplashState.Unauthenticated
                }
            }
        }
    }
}
