package com.android.imeisettings.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val titleKey: String, val icon: ImageVector) {
    object Home : Screen("home", "nav_home", Icons.Rounded.Smartphone)
    object Signal : Screen("signal", "nav_signal", Icons.Rounded.SignalCellularAlt)
    object Security : Screen("security", "nav_security", Icons.Rounded.Security)
}
