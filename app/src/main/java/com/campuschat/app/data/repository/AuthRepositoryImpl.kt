package com.campuschat.app.data.repository

import com.campuschat.app.core.result.Resource
import com.campuschat.app.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.user.UserInfo

class AuthRepositoryImpl(
    private val supabaseClient: SupabaseClient
) : AuthRepository {

    override suspend fun signUp(email: String, password: String): Resource<UserInfo> {
        return try {
            supabaseClient.auth.signUpWith(Email) {
                this.email = email
                this.password = password
            }
            val user = supabaseClient.auth.currentUserOrNull()
            if (user != null) {
                Resource.Success(user)
            } else {
                Resource.Error("Registration submitted. Please check email or proceed to sign in.")
            }
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun signIn(email: String, password: String): Resource<UserInfo> {
        return try {
            supabaseClient.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            val user = supabaseClient.auth.currentUserOrNull()
            if (user != null) {
                Resource.Success(user)
            } else {
                Resource.Error("Authentication failed: invalid credentials or session lost.")
            }
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun signOut(): Resource<Unit> {
        return try {
            supabaseClient.auth.signOut()
            Resource.Success(Unit)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    override suspend fun getCurrentUser(): UserInfo? {
        return supabaseClient.auth.currentUserOrNull()
    }

    override suspend fun restoreSession(): Resource<UserInfo?> {
        return try {
            supabaseClient.auth.awaitInitialization()
            val user = supabaseClient.auth.currentUserOrNull()
            Resource.Success(user)
        } catch (e: Exception) {
            Resource.Error(sanitizeErrorMessage(e), e)
        }
    }

    private fun sanitizeErrorMessage(e: Exception): String {
        val rawMessage = e.message ?: return "An unexpected authentication error occurred."
        return when {
            rawMessage.contains("Email not confirmed", ignoreCase = true) ->
                "Email not confirmed. Please check your inbox or disable 'Confirm email' in Supabase Dashboard -> Authentication -> Providers -> Email."
            rawMessage.contains("Invalid login credentials", ignoreCase = true) || rawMessage.contains("invalid_credentials", ignoreCase = true) ->
                "Invalid email or password. Please check your credentials and try again."
            rawMessage.contains("User already registered", ignoreCase = true) ->
                "This email is already registered. Please sign in instead."
            else -> {
                rawMessage.substringBefore("\nURL:").substringBefore("\nHeaders:").trim()
            }
        }
    }
}
