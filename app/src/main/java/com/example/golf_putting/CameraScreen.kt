package com.example.golf_putting

import android.content.Context
import android.graphics.Color as AndroidColor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import kotlin.math.max
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val highSpeedConfig = remember { findHighSpeedConfiguration(cameraManager) }

    // [수정] 사용자가 직접 조절할 수 있는 가이드라인 Y축 비율 및 실제 거리
    var ballYRatio by remember { mutableFloatStateOf(0.75f) }  // 공 위치 (기본 75%)
    var gateAYRatio by remember { mutableFloatStateOf(0.45f) } // Gate A 위치 (기본 45%)
    var gateBYRatio by remember { mutableFloatStateOf(0.15f) } // Gate B 위치 (기본 15%)
    var realDistanceCm by remember { mutableFloatStateOf(30f) } // 실제 거리 (10~100cm)

    var puttingState by remember { mutableStateOf(PuttingState.SETUP) }
    var isRecording by remember { mutableStateOf(false) }
    var currentBrightness by remember { mutableIntStateOf(0) }
    var baselineBrightness by remember { mutableIntStateOf(0) }

    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) }
    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
    val cameraController = remember { mutableStateOf<CameraController?>(null) }

    LaunchedEffect(puttingState) {
        if (puttingState != PuttingState.SETUP) {
            Toast.makeText(context, "상태: $puttingState", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(highSpeedConfig) {
        if (highSpeedConfig == null) return@LaunchedEffect

        launch {
            var stabilizingStartTime = 0L
            var lastLogTime = 0L

            while (isActive) {
                val tv = textureViewRef.value
                val controller = cameraController.value

                if (tv != null && tv.isAvailable && controller != null) {
                    val bitmap = tv.getBitmap(108, 192)
                    if (bitmap != null) {
                        val x = (bitmap.width * 0.5f).toInt()
                        // 설정된 공 위치 비율(ballYRatio)을 반영하여 밝기 모니터링
                        val y = (bitmap.height * ballYRatio).toInt()

                        var sum = 0
                        var count = 0
                        for (i in -2..2) {
                            for (j in -2..2) {
                                val px = x + i; val py = y + j
                                if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                                    val pixel = bitmap.getPixel(px, py)
                                    sum += (AndroidColor.red(pixel) + AndroidColor.green(pixel) + AndroidColor.blue(pixel)) / 3
                                    count++
                                }
                            }
                        }
                        val avgBrightness = if (count > 0) sum / count else 0
                        currentBrightness = avgBrightness
                        bitmap.recycle()

                        if (puttingState == PuttingState.SETUP) {
                            delay(200)
                            continue
                        }

                        when (puttingState) {
                            PuttingState.WAITING -> {
                                if (avgBrightness > baselineBrightness + 30) {
                                    puttingState = PuttingState.STABILIZING
                                    stabilizingStartTime = System.currentTimeMillis()
                                }
                            }
                            PuttingState.STABILIZING -> {
                                if (avgBrightness > baselineBrightness + 30) {
                                    if (System.currentTimeMillis() - stabilizingStartTime >= 2000) {
                                        puttingState = PuttingState.READY
                                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP)
                                        controller.startRecording()
                                        isRecording = true
                                    }
                                } else {
                                    puttingState = PuttingState.WAITING
                                }
                            }
                            PuttingState.READY -> {
                                if (avgBrightness < baselineBrightness + 15) {
                                    puttingState = PuttingState.PUTTING
                                    scope.launch {
                                        delay(1500)
                                        val savedPath = controller.getCurrentPath()
                                        controller.stopRecording()
                                        isRecording = false
                                        puttingState = PuttingState.WAITING

                                        savedPath?.let { path ->
                                            // 동적으로 설정된 4개의 파라미터를 모두 전달
                                            VideoAnalyzer.analyzeVideo(path, realDistanceCm, ballYRatio, gateAYRatio, gateBYRatio)
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
                delay(100)
            }
        }
    }

    if (highSpeedConfig == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("240fps 고속 촬영 미지원 기기", color = Color.White)
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var controller: CameraController? = null
                            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                textureViewRef.value = this@apply
                                st.setDefaultBufferSize(highSpeedConfig.size.width, highSpeedConfig.size.height)
                                configureTransform(this@apply, w, h, highSpeedConfig)
                                controller = CameraController(ctx, highSpeedConfig, Surface(st))
                                cameraController.value = controller
                                controller?.openCamera()
                            }
                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) { configureTransform(this@apply, w, h, highSpeedConfig) }
                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                controller?.release(); cameraController.value = null; textureViewRef.value = null
                                return true
                            }
                        }
                    }
                }
            )

            // 동적 캔버스 그리기
            Canvas(modifier = Modifier.fillMaxSize()) {
                val ballX = size.width * 0.5f
                val ballY = size.height * ballYRatio
                val gateAY = size.height * gateAYRatio
                val gateBY = size.height * gateBYRatio

                drawLine(color = Color.White.copy(alpha = 0.4f), start = Offset(ballX, 0f), end = Offset(ballX, size.height), strokeWidth = 3f)

                val ballGuideColor = when(puttingState) {
                    PuttingState.SETUP -> Color.White.copy(alpha = 0.5f)
                    PuttingState.READY -> Color.Green.copy(alpha = 0.6f)
                    PuttingState.STABILIZING -> Color.Cyan.copy(alpha = 0.6f)
                    else -> Color.Yellow.copy(alpha = 0.5f)
                }
                drawCircle(color = ballGuideColor, radius = 40f, center = Offset(ballX, ballY))

                val neonGreen = Color(0xFF39FF14)
                drawLine(color = neonGreen.copy(alpha = 0.5f), start = Offset(0f, gateAY), end = Offset(size.width, gateAY), strokeWidth = 2f)
                drawLine(color = neonGreen, start = Offset(0f, gateBY), end = Offset(size.width, gateBY), strokeWidth = 4f)
            }

            // 상단 상태창
            Column(modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(16.dp)) {
                    Text(
                        text = if (puttingState == PuttingState.SETUP) "1단계: 라인 정렬 및 거리 설정" else "상태: $puttingState",
                        color = Color.White, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold
                    )
                }
            }

            // 하단 설정 UI (SETUP 상태에서만 노출)
            if (puttingState == PuttingState.SETUP) {
                Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 30.dp), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.fillMaxWidth(0.9f).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("매트 눈금에 가이드라인을 맞춰주세요", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        // 슬라이더 모음 (공간 절약을 위해 컴팩트하게 배치)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            SliderRow("공 위치 Y", ballYRatio, 0.5f, 0.9f) { ballYRatio = it }
                            SliderRow("Gate A (녹색)", gateAYRatio, 0.3f, 0.7f) { gateAYRatio = it }
                            SliderRow("Gate B (적색)", gateBYRatio, 0.05f, 0.4f) { gateBYRatio = it }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Gate A ~ Gate B 실제 매트 거리: ${"%.1f".format(realDistanceCm)} cm", color = Color.Yellow, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Slider(value = realDistanceCm, onValueChange = { realDistanceCm = it }, valueRange = 10f..100f, colors = SliderDefaults.colors(thumbColor = Color.Yellow, activeTrackColor = Color.Yellow))
                        }

                        Button(
                            onClick = {
                                baselineBrightness = currentBrightness
                                puttingState = PuttingState.WAITING
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text("설정 완료 (시작하기)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(36.dp)) {
        Text(text = label, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.weight(0.3f))
        Slider(
            value = value, onValueChange = onValueChange, valueRange = min..max,
            modifier = Modifier.weight(0.7f)
        )
    }
}

private fun configureTransform(view: TextureView, viewWidth: Int, viewHeight: Int, config: HighSpeedConfig) {
    val matrix = android.graphics.Matrix()
    val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = android.graphics.RectF(0f, 0f, config.size.height.toFloat(), config.size.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
    matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
    val scale = max(viewHeight.toFloat() / config.size.width, viewWidth.toFloat() / config.size.height)
    matrix.postScale(scale, scale, centerX, centerY)
    view.setTransform(matrix)
}