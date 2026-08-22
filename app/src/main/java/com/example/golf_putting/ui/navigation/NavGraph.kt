package com.example.golf_putting.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.golf_putting.ui.screens.analytics.AnalyticsScreen
import com.example.golf_putting.ui.screens.auth.LoginScreen
import com.example.golf_putting.ui.screens.calibration.CalibrationWizardScreen
import com.example.golf_putting.ui.screens.leaderboard.LeaderboardScreen
import com.example.golf_putting.ui.screens.practice.CameraScreen
import com.example.golf_putting.ui.screens.practice.ChallengeScreen
import com.example.golf_putting.ui.screens.settings.SettingsScreen

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Screen.Login.route
    ) {
        // 인증 화면
        composable(Screen.Login.route) {
            LoginScreen(onLoginSuccess = {
                navController.navigate(Screen.TabScreen.Practice.route) {
                    popUpTo(Screen.Login.route) { inclusive = true }
                }
            })
        }

        // 메인 탭: 연습
        composable(Screen.TabScreen.Practice.route) {
            CameraScreen(navController = navController)
        }

                // 메인 탭: 리포트
        composable(Screen.TabScreen.Analytics.route) {
            AnalyticsScreen()
        }

        // 메인 탭: 랭킹
        composable(Screen.TabScreen.Leaderboard.route) {
            LeaderboardScreen()
        }

        // 메인 탭: 설정
        composable(Screen.TabScreen.Settings.route) {
            SettingsScreen(navController = navController)
        }

        // 세부 화면: 챌린지
        composable(Screen.Challenge.route) {
            ChallengeScreen(onBack = { navController.popBackStack() })
        }

        // 세부 화면: 캘리브레이션 위저드
        composable(Screen.CalibrationWizard.route) {
            CalibrationWizardScreen(onFinished = { navController.popBackStack() })
        }
    }
}
