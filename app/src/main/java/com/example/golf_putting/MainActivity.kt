package com.example.golf_putting

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.golf_putting.core.vision.CalibrationManager
import com.example.golf_putting.core.vision.VideoAnalyzer as CoreAnalyzer
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

        // [중요] 비디오 분석 모듈을 초기화
        CalibrationManager.init(applicationContext)

        setContent {
            val navController = rememberNavController()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route

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
                    Box(modifier = Modifier.padding(innerPadding)) {
                        AppNavGraph(
                            navController = navController
                        )
                    }
                }
            }
        }
    }
}
