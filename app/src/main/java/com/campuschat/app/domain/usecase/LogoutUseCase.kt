package com.campuschat.app.domain.usecase

import com.campuschat.app.core.result.Resource
import com.campuschat.app.core.session.SessionManager
import com.campuschat.app.domain.repository.AuthRepository

class LogoutUseCase(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(): Resource<Unit> {
        val result = authRepository.signOut()
        if (result is Resource.Success) {
            SessionManager.setUnauthenticated()
        }
        return result
    }
}
