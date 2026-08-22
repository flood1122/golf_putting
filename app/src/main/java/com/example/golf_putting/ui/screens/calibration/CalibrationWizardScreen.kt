package com.example.golf_putting.ui.screens.calibration

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.golf_putting.core.camera.CameraController
import com.example.golf_putting.core.camera.HighSpeedConfig
import com.example.golf_putting.core.camera.findHighSpeedConfiguration
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
fun CalibrationWizardScreen(
    onFinished: () -> Unit,
    viewModel: CalibrationViewModel = viewModel()
) {
    val context = LocalContext.current
    val cameraManager = remember { context.getSystemService(Context.CAMERA_SERVICE) as CameraManager }
    val highSpeedConfig = remember { findHighSpeedConfiguration(cameraManager) }
    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
    val cameraController = remember { mutableStateOf<CameraController?>(null) }

    val step = viewModel.currentStep
    var currentPitchDegree by remember { mutableFloatStateOf(0f) }
    var pitchStatus by remember { mutableStateOf(PitchStatus.BAD) }
    var activeHandle by remember { mutableStateOf(HandleTarget.NONE) }
    var showSavePresetDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]

                    val norm = sqrt((x * x + y * y + z * z).toDouble())
                    if (norm > 0) {
                        val pitch = Math.toDegrees(acos((z / norm).coerceIn(-1.0, 1.0))).toFloat()
                        currentPitchDegree = pitch
                        pitchStatus = when (pitch) {
                            in 10f..30f -> PitchStatus.GOOD
                            in 30f..45f -> PitchStatus.NORMAL
                            else -> PitchStatus.BAD
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    if (highSpeedConfig == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("240fps 고속 촬영 미지원 기기입니다.", color = Color.White)
        }
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
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

                            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                configureTransform(this@apply, w, h, highSpeedConfig)
                            }

                            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}

                            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                controller?.release()
                                cameraController.value = null
                                textureViewRef.value = null
                                return true
                            }
                        }
                    }
                }
            )

            when (step) {
                1 -> {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centerX = size.width * 0.5f
                        drawLine(
                            color = Color.White.copy(alpha = 0.5f),
                            start = Offset(centerX, 0f),
                            end = Offset(centerX, size.height),
                            strokeWidth = 3f
                        )
                    }
                }
                2 -> SymmetricMatOverlay(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                3 -> BallSetupOverlay(
                    viewModel = viewModel,
                    textureViewRef = textureViewRef,
                    activeHandle = activeHandle,
                    onHandleChanged = { activeHandle = it },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 48.dp, end = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    val stepTitle = when (step) {
                        1 -> "1단계: 라인 정렬"
                        2 -> "2단계: 매트 정렬"
                        else -> "3단계: 반자동 공 가이드 설정"
                    }
                    Text(
                        text = stepTitle,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (step == 3 && viewModel.statusMessage.isNotEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = if (viewModel.isBallDetected) {
                            Color(0xFF2E7D32).copy(alpha = 0.92f)
                        } else {
                            Color(0xFFE65100).copy(alpha = 0.92f)
                        },
                        shape = RoundedCornerShape(12.dp),
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (viewModel.isBallDetected) "✓ " else "⚠ ",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = viewModel.statusMessage,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            val calibData = viewModel.calibrationData
            val guideAlignment = if (step == 3) Alignment.TopCenter else Alignment.BottomCenter
            val guidePadding = if (step == 3) Modifier.padding(top = 155.dp) else Modifier.padding(bottom = 90.dp)

            Box(
                modifier = Modifier.align(guideAlignment).fillMaxWidth().then(guidePadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val guideText = when (step) {
                        1 -> "매트의 센터라인과 맞게 스마트폰을 설치해 주세요."
                        2 -> "화면을 좌우/위아래로 드래그하여 원근 사다리꼴 형태를 조절하세요."
                        else -> "화면의 가이드 점 중심에 공을 놓고 아래 '검증' 버튼을 누르세요."
                    }

                    Text(
                        text = guideText,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = if (step == 1 || step == 3) 8.dp else 0.dp)
                    )

                    if (step == 1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                .padding(vertical = 8.dp)
                        ) {
                            Text(
                                text = "스마트폰 각도: ${currentPitchDegree.roundToInt()}° ",
                                color = Color.LightGray,
                                fontSize = 14.sp
                            )
                            Surface(
                                color = pitchStatus.color,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.padding(start = 6.dp)
                            ) {
                                Text(
                                    text = pitchStatus.label,
                                    color = Color.Black,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    } else if (step == 3) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    Log.i("GolfPutt/BallSetup", "================ [Snap 버튼 클릭됨!] ================")
                                    val textureView = textureViewRef.value
                                    if (textureView != null && textureView.isAvailable) {
                                        val frame = textureView.getBitmap(640, 480)
                                        if (frame != null) {
                                            viewModel.adjustBallManualLive(
                                                0.5f,
                                                calibData.ballYRatio,
                                                frame
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (viewModel.isBallDetected) Color(0xFF00897B) else Color(0xFFD84315)
                                )
                            ) {
                                Text(
                                    text = if (viewModel.isBallDetected) "공 위치 스캔 성공 (재검증)" else "현재 영역 스캔 및 검증 (Snap)",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (step > 1) {
                        OutlinedButton(
                            onClick = { viewModel.currentStep-- },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("이전")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(80.dp))
                    }

                    Button(
                        onClick = {
                            if (step == 1) {
                                val bitmapA = textureViewRef.value?.getBitmap(640, 480)
                                bitmapA?.let { viewModel.imageA = it }
                                viewModel.analyzeAndDetectMatShape()
                                viewModel.currentStep = 2
                            } else if (step == 2) {
                                viewModel.currentStep = 3
                            } else {
                                showSavePresetDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
                    ) {
                        Text(if (step < 3) "다음" else "설정 확정 및 저장")
                    }
                }
            }

            if (showSavePresetDialog) {
                SavePresetDialog(
                    initialPresetName = "매트 프리셋 1",
                    onDismiss = { showSavePresetDialog = false },
                    onConfirm = { presetName ->
                        viewModel.saveAndFinishWithPreset(presetName)
                        showSavePresetDialog = false
                        onFinished()
                    }
                )
            }

            if (viewModel.showConfirmDialog) {
                BallConfirmDialog(
                    isDetected = viewModel.isBallDetected,
                    onFeedback = { feedbackType ->
                        viewModel.applyUserFeedback(feedbackType)
                    },
                    onDismiss = { viewModel.showConfirmDialog = false }
                )
            }
        }
    }
}

private fun configureTransform(view: TextureView, viewWidth: Int, viewHeight: Int, config: HighSpeedConfig) {
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, config.size.height.toFloat(), config.size.width.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
    val scale = max(viewHeight.toFloat() / config.size.width, viewWidth.toFloat() / config.size.height)
    matrix.postScale(scale, scale, centerX, centerY)
    view.setTransform(matrix)
}