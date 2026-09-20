package com.campuschat.app.domain.repository

import com.campuschat.app.core.result.Resource
import io.github.jan.supabase.gotrue.user.UserInfo

interface AuthRepository {
    suspend fun signUp(email: String, password: String): Resource<UserInfo>
    suspend fun signIn(email: String, password: String): Resource<UserInfo>
    suspend fun signOut(): Resource<Unit>
    suspend fun getCurrentUser(): UserInfo?
    suspend fun restoreSession(): Resource<UserInfo?>
}
