package com.campuschat.app.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.campuschat.app.core.config.AppConfig
import com.campuschat.app.presentation.common.CampusChatCard
import com.campuschat.app.presentation.common.CampusChatErrorBanner
import com.campuschat.app.presentation.theme.DarkBackground
import com.campuschat.app.presentation.theme.ErrorRed
import com.campuschat.app.presentation.theme.PrimaryEmerald
import com.campuschat.app.presentation.theme.TextMuted
import com.campuschat.app.presentation.theme.TextPrimary
import com.campuschat.app.presentation.theme.TextSecondary

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Settings",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            uiState.errorMessage?.let { error ->
                CampusChatErrorBanner(
                    message = error,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // Device Info Section
            Text(
                text = "Device Registration Metadata",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryEmerald,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            CampusChatCard {
                Column {
                    SettingsItem(
                        icon = Icons.Default.Devices,
                        title = "Device Name",
                        subtitle = uiState.deviceName
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsItem(
                        icon = Icons.Default.DeveloperMode,
                        title = "Installation UUID",
                        subtitle = uiState.deviceId
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = "Platform",
                        subtitle = uiState.platform
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Limits Section
            Text(
                text = "App Policy Limits",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = PrimaryEmerald,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )

            CampusChatCard {
                Column {
                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = "Max Single Attachment Limit",
                        subtitle = "${AppConfig.MAX_ATTACHMENT_SIZE_MB} MB"
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    SettingsItem(
                        icon = Icons.Default.Lock,
                        title = "End-to-End Encryption Strategy",
                        subtitle = "Signal Protocol (Prepared for Step 3)"
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Logout Button
            Button(
                onClick = { viewModel.logout(onLoggedOut) },
                enabled = !uiState.isLoggingOut,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorRed.copy(alpha = 0.15f),
                    contentColor = ErrorRed
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (uiState.isLoggingOut) {
                    CircularProgressIndicator(
                        color = ErrorRed,
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "Logout",
                            tint = ErrorRed
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sign Out",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PrimaryEmerald,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = TextSecondary
            )
        }
    }
}
