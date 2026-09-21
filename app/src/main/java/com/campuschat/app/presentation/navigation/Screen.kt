package com.campuschat.app.presentation.navigation

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Home : Screen("home")
    data object NewChat : Screen("new_chat")
    data object Profile : Screen("profile")
    data object Settings : Screen("settings")
    data object Conversation : Screen("conversation/{recipientUserId}/{recipientDeviceId}") {
        fun createRoute(recipientUserId: String, recipientDeviceId: String): String {
            return "conversation/$recipientUserId/$recipientDeviceId"
        }
    }
}
