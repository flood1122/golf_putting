package com.example.golf_putting.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String = "") {
    object Login : Screen("login", "로그인")
    object Challenge : Screen("challenge", "챌린지 모드")
    object CalibrationWizard : Screen("calibration_wizard", "캘리브레이션")

    // Bottom Navigation Screens
    sealed class TabScreen(route: String, title: String, val icon: ImageVector) : Screen(route, title) {
        object Practice : TabScreen("practice", "연습", Icons.Default.PlayArrow)
        object Analytics : TabScreen("analytics", "리포트", Icons.Default.BarChart)
        object Leaderboard : TabScreen("leaderboard", "랭킹", Icons.Default.EmojiEvents)
        object Settings : TabScreen("settings", "설정", Icons.Default.Build)
    }
}

val bottomNavigationItems = listOf(
    Screen.TabScreen.Practice,
    Screen.TabScreen.Analytics,
    Screen.TabScreen.Leaderboard,
    Screen.TabScreen.Settings
)
