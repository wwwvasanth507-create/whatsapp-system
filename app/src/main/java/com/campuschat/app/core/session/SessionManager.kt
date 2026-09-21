package com.campuschat.app.core.session

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.user.UserInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AuthState {
    data object Idle : AuthState()
    data object Loading : AuthState()
    data class Authenticated(val user: UserInfo) : AuthState()
    data object Unauthenticated : AuthState()
    data class Error(val message: String) : AuthState()
}

object SessionManager {

    private lateinit var supabaseClient: SupabaseClient

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun init(client: SupabaseClient) {
        supabaseClient = client
    }

    suspend fun checkSession(): AuthState {
        _authState.value = AuthState.Loading
        return try {
            val session = supabaseClient.auth.currentSessionOrNull()
            val currentUser = supabaseClient.auth.currentUserOrNull()

            if (session != null && currentUser != null) {
                val state = AuthState.Authenticated(currentUser)
                _authState.value = state
                state
            } else {
                _authState.value = AuthState.Unauthenticated
                AuthState.Unauthenticated
            }
        } catch (e: Exception) {
            val state = AuthState.Unauthenticated
            _authState.value = state
            state
        }
    }

    fun setAuthenticated(user: UserInfo) {
        _authState.value = AuthState.Authenticated(user)
    }

    fun setUnauthenticated() {
        _authState.value = AuthState.Unauthenticated
    }

    fun getCurrentUserId(): String? {
        if (!::supabaseClient.isInitialized) {
            return null
        }
        return try {
            supabaseClient.auth.currentUserOrNull()?.id
        } catch (e: Exception) {
            null
        }
    }
}
