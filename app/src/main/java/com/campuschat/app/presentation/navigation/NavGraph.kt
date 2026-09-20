package com.campuschat.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.campuschat.app.presentation.auth.login.LoginScreen
import com.campuschat.app.presentation.auth.login.LoginViewModel
import com.campuschat.app.presentation.auth.register.RegisterScreen
import com.campuschat.app.presentation.auth.register.RegisterViewModel
import com.campuschat.app.presentation.home.HomeScreen
import com.campuschat.app.presentation.home.HomeViewModel
import com.campuschat.app.presentation.profile.ProfileScreen
import com.campuschat.app.presentation.profile.ProfileViewModel
import com.campuschat.app.presentation.settings.SettingsScreen
import com.campuschat.app.presentation.settings.SettingsViewModel
import com.campuschat.app.presentation.splash.SplashScreen
import com.campuschat.app.presentation.splash.SplashViewModel

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Splash.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Splash.route) {
            val splashViewModel: SplashViewModel = viewModel(factory = AppViewModelFactory.provideSplashViewModelFactory())
            SplashScreen(
                viewModel = splashViewModel,
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Login.route) {
            val loginViewModel: LoginViewModel = viewModel(factory = AppViewModelFactory.provideLoginViewModelFactory())
            LoginScreen(
                viewModel = loginViewModel,
                onNavigateToRegister = {
                    navController.navigate(Screen.Register.route)
                },
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Register.route) {
            val registerViewModel: RegisterViewModel = viewModel(factory = AppViewModelFactory.provideRegisterViewModelFactory())
            RegisterScreen(
                viewModel = registerViewModel,
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Register.route) { inclusive = true }
                    }
                },
                onNavigateToHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Register.route) { inclusive = true }
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            val homeViewModel: HomeViewModel = viewModel(factory = AppViewModelFactory.provideHomeViewModelFactory())
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToProfile = {
                    navController.navigate(Screen.Profile.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Profile.route) {
            val profileViewModel: ProfileViewModel = viewModel(factory = AppViewModelFactory.provideProfileViewModelFactory())
            ProfileScreen(
                viewModel = profileViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory.provideSettingsViewModelFactory())
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onLoggedOut = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }
    }
}
