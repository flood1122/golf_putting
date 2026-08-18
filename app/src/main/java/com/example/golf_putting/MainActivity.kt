package com.example.golf_putting

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // [확인 방법 1] OpenCV 초기화 성공 로그 확인
        if (OpenCVLoader.initDebug()) {
            Log.i("OpenCV/StaticHelper", "OpenCV library found inside package. Using it!")
            Log.d("GolfPutt", "[OPENCV] OpenCV 초기화 성공!")
        } else {
            Log.e("GolfPutt", "[OPENCV] OpenCV 초기화 실패! 라이브러리 로드 상태를 확인하세요.")
        }

        // VideoAnalyzer 초기화 (CameraScreen 수정 없이 Context 사용 위함)
        VideoAnalyzer.init(applicationContext)


        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    PermissionRequestScreen {
                        CameraScreen()
                    }
                }
            }
        }
    }
}
