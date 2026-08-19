package com.example.golf_putting

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.golf_putting.core.vision.CalibrationManager
import com.example.golf_putting.core.vision.VideoAnalyzer
import com.example.golf_putting.ui.components.MainBottomBar
import com.example.golf_putting.ui.components.PermissionRequestScreen
import com.example.golf_putting.ui.navigation.AppNavGraph
import com.example.golf_putting.ui.navigation.Screen
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OpenCV 초기화
        if (OpenCVLoader.initDebug()) {
            Log.d("GolfPutt", "[OPENCV] OpenCV 초기화 성공!")
        } else {
            Log.e("GolfPutt", "[OPENCV] OpenCV 초기화 실패!")
        }

        // 비디오 분석 및 캘리브레이션 모듈 초기화
        VideoAnalyzer.init(applicationContext)
        CalibrationManager.init(applicationContext)

        setContent {
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

            // 로그인 화면 등 특정 화면에서는 바텀바를 숨깁니다.
            val showBottomBar = currentRoute in listOf(
                Screen.TabScreen.Practice.route,
                Screen.TabScreen.Analytics.route,
                Screen.TabScreen.Leaderboard.route,
                Screen.TabScreen.Settings.route
            )

            PermissionRequestScreen {
                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            MainBottomBar(navController = navController)
                        }
                    },
                    containerColor = Color.Black
                ) { innerPadding ->
                    // Navigation Host 연결
                    AppNavGraph(
                        navController = navController
                    )
                }
            }
        }
    }
}
