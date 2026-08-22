package com.example.golf_putting.ui.screens.practice

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.example.golf_putting.core.camera.CameraController
import com.example.golf_putting.core.camera.HighSpeedConfig
import com.example.golf_putting.core.camera.findHighSpeedConfiguration
import com.example.golf_putting.core.vision.CalibrationManager
import com.example.golf_putting.core.vision.VideoAnalyzer
import com.example.golf_putting.data.model.PuttingState
import com.example.golf_putting.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import kotlin.math.hypot
import kotlin.math.max

@Composable
fun CameraScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val highSpeedConfig = remember { findHighSpeedConfiguration(cameraManager) }

    val activeCalib = CalibrationManager.activeCalibrationData
    var ballYRatio by remember { mutableFloatStateOf(activeCalib.ballYRatio) }

    var puttingState by remember { mutableStateOf(PuttingState.SETUP) }
    var isRecording by remember { mutableStateOf(false) }

    var readyTimestampUs by remember { mutableLongStateOf(0L) }

    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100) }
    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
    val cameraController = remember { mutableStateOf<CameraController?>(null) }
    var recordingStartSysTimeMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(puttingState) {
        if (puttingState != PuttingState.SETUP) {
            Toast.makeText(context, "상태: $puttingState", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(highSpeedConfig) {
        if (highSpeedConfig == null) return@LaunchedEffect
        launch {
            var staticCheckCount = 0
            var lastBallPt: Pair<Float, Float>? = null
            val checkIntervalMs = 500L // ★ 스코프 오류 방지를 위해 변수를 루프 상단에 선언

            while (isActive) {
                val tv = textureViewRef.value
                val controller = cameraController.value

                if (tv != null && tv.isAvailable && controller != null) {
                    if (puttingState == PuttingState.SETUP) {
                        delay(200); continue
                    }

                    val bitmap = tv.getBitmap(360, 640)

                    if (bitmap != null) {
                        val ballX = bitmap.width * 0.5f
                        val ballY = bitmap.height * ballYRatio
                        val roiRadius = activeCalib.ballPixelRadius * (bitmap.width.toFloat() / 1280f) * 2.5f

                        val currentBallPt = findBallCenterInBitmap(bitmap, ballX, ballY, roiRadius)

                        when (puttingState) {
                            PuttingState.WAITING -> {
                                if (currentBallPt != null) {
                                    puttingState = PuttingState.STABILIZING
                                    staticCheckCount = 0
                                    lastBallPt = currentBallPt
                                    Log.i("GolfPutt", "[STABILIZING 진입] 공 감지됨, 500ms 정지 검출 시작")
                                }
                            }
                            PuttingState.STABILIZING -> {
                                if (currentBallPt != null && lastBallPt != null) {
                                    val dist = hypot(
                                        (currentBallPt.first - lastBallPt.first).toDouble(),
                                        (currentBallPt.second - lastBallPt.second).toDouble()
                                    ).toFloat()

                                    val isStatic = dist < 4.0f

                                    if (isStatic) {
                                        staticCheckCount++
                                        Log.d("GolfPutt", "[STABILIZING] 정지 체크 성공 ($staticCheckCount/4회) | 이동거리: ${String.format("%.2f", dist)}px")
                                    } else {
                                        staticCheckCount = 0
                                        Log.w("GolfPutt", "[STABILIZING] 공 움직임 감지되어 카운트 초기화 | 이동거리: ${String.format("%.2f", dist)}px")
                                    }

                                    // 디버그용 주석처리
//                                    saveStabilizingDebugImage(
//                                        context,
//                                        bitmap,
//                                        ballX,
//                                        ballY,
//                                        roiRadius,
//                                        currentBallPt,
//                                        staticCheckCount,
//                                        dist
//                                    )

                                    lastBallPt = currentBallPt



                                    if (staticCheckCount >= 4) {
                                        puttingState = PuttingState.READY

                                        // 녹화 시작 시점 기록
                                        recordingStartSysTimeMs = System.currentTimeMillis()
                                        readyTimestampUs = 0L // READY가 된 바로 그 시점부터 녹화 시작이므로 상대 PTS는 0us

                                        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP)
                                        controller.startRecording()
                                        isRecording = true
                                        Log.i("GolfPutt", "[READY 완료] 녹화 시작!")
                                    }
                                } else {
                                    puttingState = PuttingState.WAITING
                                    staticCheckCount = 0
                                    lastBallPt = null
                                }
                            }
                            PuttingState.READY -> {
                                if (currentBallPt != null && lastBallPt != null) {
                                    val moveY = lastBallPt.second - currentBallPt.second
                                    if (moveY > 8.0f) {
                                        puttingState = PuttingState.PUTTING
                                        val targetReadyTsUs = readyTimestampUs
                                        scope.launch {
                                            delay(1500)
                                            val savedPath = controller.getCurrentPath()
                                            controller.stopRecording()
                                            isRecording = false
                                            puttingState = PuttingState.WAITING
                                            delay(500)

                                            savedPath?.let { path ->
                                                scope.launch(Dispatchers.Default) {
                                                    try {
                                                        val pts = VideoAnalyzer.analyzeVideo(
                                                            context = context,
                                                            filePath = path,
                                                            calib = CalibrationManager.activeCalibrationData,
                                                            readyTimestampUs = targetReadyTsUs
                                                        )
                                                        Log.i("GolfPutt", "[SCREEN] 분석 완료! 포인트 개수: ${pts.size}")
                                                    } catch (e: Exception) {
                                                        Log.e("GolfPutt", "[SCREEN] 분석 실패", e)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                lastBallPt = currentBallPt
                            }
                            else -> {}
                        }
                        bitmap.recycle()
                    }
                }
                delay(checkIntervalMs)
            }
        }
    }

    if (highSpeedConfig == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
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

            Canvas(modifier = Modifier.fillMaxSize()) {
                val ballX = size.width * 0.5f
                val ballY = size.height * ballYRatio
                drawLine(color = Color.White.copy(alpha = 0.4f), start = Offset(ballX, 0f), end = Offset(ballX, size.height), strokeWidth = 3f)
                val ballGuideColor = when(puttingState) {
                    PuttingState.SETUP -> Color.White.copy(alpha = 0.5f)
                    PuttingState.READY -> Color.Green.copy(alpha = 0.6f)
                    PuttingState.STABILIZING -> Color.Cyan.copy(alpha = 0.6f)
                    else -> Color.Yellow.copy(alpha = 0.5f)
                }
                drawCircle(color = ballGuideColor, radius = 40f, center = Offset(ballX, ballY))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 48.dp, end = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = Color.Black.copy(alpha = 0.7f), shape = RoundedCornerShape(16.dp)) {
                    Text(
                        text = if (puttingState == PuttingState.SETUP) "1단계: 라인 정렬" else "상태: $puttingState",
                        color = Color.White,
                        modifier = Modifier.padding(16.dp, 8.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = { navController.navigate(Screen.CalibrationWizard.route) },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(imageVector = Icons.Default.AutoFixHigh, contentDescription = "Calibration Wizard", tint = Color.Cyan)
                }
            }

            if (puttingState == PuttingState.SETUP) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(0.9f).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("가이드라인 및 그린 빠르기 조정", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))

                        var greenSpeed by remember { mutableFloatStateOf(activeCalib.greenSpeedFactor) }

                        Column(modifier = Modifier.fillMaxWidth()) {
                            SliderRow("공 위치 Y", ballYRatio, 0.5f, 0.9f) { ballYRatio = it }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("그린 빠르기(영점 조절): ${(greenSpeed * 100).toInt()}%", color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Slider(
                                value = greenSpeed,
                                onValueChange = { greenSpeed = it },
                                valueRange = 0.5f..1.5f,
                                colors = SliderDefaults.colors(thumbColor = Color.Yellow, activeTrackColor = Color.Yellow)
                            )
                        }
                        Button(
                            onClick = {
                                CalibrationManager.saveActiveCalibration(activeCalib.copy(
                                    ballYRatio = ballYRatio,
                                    greenSpeedFactor = greenSpeed
                                ))
                                puttingState = PuttingState.WAITING
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) { Text("설정 완료 (시작하기)") }
                    }
                }
            }
        }
    }
}

private fun findBallCenterInBitmap(bitmap: Bitmap, centerX: Float, centerY: Float, roiRadius: Float): Pair<Float, Float>? {
    val left = (centerX - roiRadius).toInt().coerceIn(0, bitmap.width - 1)
    val top = (centerY - roiRadius).toInt().coerceIn(0, bitmap.height - 1)
    val right = (centerX + roiRadius).toInt().coerceIn(left + 1, bitmap.width)
    val bottom = (centerY + roiRadius).toInt().coerceIn(top + 1, bitmap.height)

    var sumX = 0L; var sumY = 0L; var count = 0

    for (y in top until bottom) {
        for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val brightness = (r + g + b) / 3

            if (brightness > 160) {
                sumX += x; sumY += y; count++
            }
        }
    }

    return if (count > 30) {
        Pair(sumX.toFloat() / count, sumY.toFloat() / count)
    } else null
}

private fun saveStabilizingDebugImage(
    context: Context,
    bitmap: Bitmap,
    ballX: Float,
    ballY: Float,
    roiRadius: Float,
    ballPt: Pair<Float, Float>,
    count: Int,
    dist: Float
) {
    try {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = AndroidCanvas(mutableBitmap)

        val paintRoi = Paint().apply { color = android.graphics.Color.GREEN; strokeWidth = 3f; style = Paint.Style.STROKE }
        val paintBall = Paint().apply { color = android.graphics.Color.RED; strokeWidth = 4f; style = Paint.Style.STROKE }
        val paintCenter = Paint().apply { color = android.graphics.Color.YELLOW; style = Paint.Style.FILL }
        val paintText = Paint().apply { color = android.graphics.Color.GREEN; textSize = 22f; isFakeBoldText = true }

        canvas.drawRect(ballX - roiRadius, ballY - roiRadius, ballX + roiRadius, ballY + roiRadius, paintRoi)
        canvas.drawCircle(ballPt.first, ballPt.second, roiRadius * 0.5f, paintBall)
        canvas.drawCircle(ballPt.first, ballPt.second, 5f, paintCenter)

        val text1 = "[STABILIZING 500ms] Pass: $count/4 | Delta: ${String.format("%.2f", dist)}px"
        canvas.drawText(text1, 20f, 40f, paintText)

        val fileName = "Stabilizing_Check_${System.currentTimeMillis()}"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GolfPutt_Stabilizing")
        }

        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            val outputStream: OutputStream? = context.contentResolver.openOutputStream(uri)
            outputStream?.use { mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
    } catch (e: Exception) {
        Log.e("GolfPutt", "[Stabilizing 이미지 저장 에러]: ${e.message}")
    }
}

@Composable
fun SliderRow(label: String, value: Float, min: Float, max: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(36.dp)) {
        Text(text = label, color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.weight(0.3f))
        Slider(value = value, onValueChange = onValueChange, valueRange = min..max, modifier = Modifier.weight(0.7f))
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