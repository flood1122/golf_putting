package com.example.golf_putting.ui.screens.calibration

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.golf_putting.core.camera.CameraController
import com.example.golf_putting.core.camera.HighSpeedConfig
import com.example.golf_putting.core.camera.findHighSpeedConfiguration
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize

enum class PitchStatus(val label: String, val color: Color) {
    GOOD("좋음", Color(0xFF39FF14)),
    NORMAL("보통", Color.Yellow),
    BAD("나쁨", Color.Red)
}

enum class HandleTarget {
    NONE, BALL, GATE_A, GATE_B
}

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

    var showDistanceInputDialog by remember { mutableStateOf(false) }
    var distanceInputText by remember { mutableStateOf("") }

    var showSavePresetDialog by remember { mutableStateOf(false) }
    var presetNameInputText by remember { mutableStateOf("매트 프리셋 1") }

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
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    if (highSpeedConfig == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("240fps 고속 촬영 미지원 기기입니다.", color = Color.White)
        }
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (step == 1) {
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

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val centerX = size.width * 0.5f
                    drawLine(
                        color = Color.White.copy(alpha = 0.5f),
                        start = Offset(centerX, 0f),
                        end = Offset(centerX, size.height),
                        strokeWidth = 3f
                    )
                }
            } else {
                viewModel.imageA?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Image A Preview",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                when (step) {
                    2 -> SymmetricMatOverlay(viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    3 -> GateSetupOverlay(
                        viewModel = viewModel,
                        activeHandle = activeHandle,
                        onHandleChanged = { activeHandle = it },
                        modifier = Modifier.fillMaxSize()
                    )
                    4 -> SensingDistanceOverlay(
                        viewModel = viewModel,
                        onValueClick = {
                            distanceInputText = viewModel.calibrationData.realDistanceCm.roundToInt().toString()
                            showDistanceInputDialog = true
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 상단 타이틀 라벨
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
                        3 -> "3단계: 센싱 라인 설정"
                        else -> "4단계: 실측 거리 입력"
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

            // 하단 가이드 메시지 카드
            val calibData = viewModel.calibrationData
            val isGateBActiveAndNearTop = (step == 3) && (activeHandle == HandleTarget.GATE_B) && (calibData.gateBYRatio <= 0.35f)
            val cardAlpha = if (isGateBActiveAndNearTop) 0.25f else 1.0f

            val guideAlignment = if (step == 3) Alignment.TopCenter else Alignment.BottomCenter
            val guidePadding = if (step == 3) Modifier.padding(top = 96.dp) else Modifier.padding(bottom = 90.dp)

            Box(
                modifier = Modifier
                    .align(guideAlignment)
                    .fillMaxWidth()
                    .then(guidePadding)
                    .alpha(cardAlpha),
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
                        1 -> "매트의 센터라인과 맞게 스마트폰을 설치해 주세요. 빛반사를 확인합니다."
                        2 -> "화면을 좌우로 드래그하여 폭을 맞추고, 위아래로 드래그하여 원근 각도를 조절해 주세요."
                        3 -> "공(노랑), 센싱 시작선(초록), 센싱 종료선(빨강)을 드래그하여 위치를 맞춰주세요."
                        else -> "센싱 시작선(초록)과 센싱 종료선(빨강) 사이의 실제 거리를 입력해 주세요."
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
                            Text(
                                text = "• 공(노랑) ↔ 센싱 시작선(초록) 권장: 10 ~ 20 cm",
                                color = Color.LightGray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                            Text(
                                text = "• 센싱 시작선(초록) ↔ 센싱 종료선(빨강) 권장: 30 ~ 50 cm",
                                color = Color.LightGray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // 하단 이전 / 다음 버튼
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
                            } else if (step == 3) {
                                viewModel.currentStep = 4
                            } else {
                                showSavePresetDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
                    ) {
                        Text(if (step < 4) "다음" else "저장 및 완료")
                    }
                }
            }

            // 4단계 거리 입력 팝업
            if (showDistanceInputDialog) {
                AlertDialog(
                    onDismissRequest = { showDistanceInputDialog = false },
                    title = { Text("실측 거리 입력") },
                    text = {
                        Column {
                            Text("센싱 시작선 ↔ 센싱 종료선 사이의 매트 실제 거리를 cm 단위로 입력하세요.", fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = distanceInputText,
                                onValueChange = { distanceInputText = it },
                                label = { Text("거리 (cm)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val parsedVal = distanceInputText.toFloatOrNull()
                            if (parsedVal != null && parsedVal in 10f..200f) {
                                viewModel.updateRealDistance(parsedVal)
                            }
                            showDistanceInputDialog = false
                        }) {
                            Text("확인")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDistanceInputDialog = false }) {
                            Text("취소")
                        }
                    }
                )
            }

            // 프리셋 저장 팝업
            if (showSavePresetDialog) {
                AlertDialog(
                    onDismissRequest = { showSavePresetDialog = false },
                    title = { Text("프리셋 저장") },
                    text = {
                        Column {
                            Text("현재 설정한 캘리브레이션 정보를 저장할 프리셋 이름을 입력하세요.", fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = presetNameInputText,
                                onValueChange = { presetNameInputText = it },
                                label = { Text("프리셋 이름") },
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val finalPresetName = presetNameInputText.ifBlank { "기본 매트 프리셋" }
                            viewModel.saveAndFinishWithPreset(finalPresetName)
                            showSavePresetDialog = false
                            onFinished()
                        }) {
                            Text("저장 완료")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSavePresetDialog = false }) {
                            Text("취소")
                        }
                    }
                )
            }
        }
    }
}
enum class DragControlMode {
    NONE, WIDTH_ONLY, PERSPECTIVE_ONLY
}

/**
 * 2단계: 축 고정(Lock) 드래그 제어 및 꼭짓점 원 제거 버전
 */
@Composable
fun SymmetricMatOverlay(
    viewModel: CalibrationViewModel,
    modifier: Modifier = Modifier
) {
    val currentCalibData by rememberUpdatedState(viewModel.calibrationData)

    val warp = currentCalibData.warpPoints
    val currentBottomWidth = (warp[6] - warp[4]).coerceIn(0.2f, 0.9f)
    val currentTopWidth = (warp[2] - warp[0])
    val currentPerspective = (currentTopWidth / currentBottomWidth).coerceIn(0.4f, 1.0f)

    var bottomWidth by remember { mutableFloatStateOf(currentBottomWidth) }
    var perspectiveRatio by remember { mutableFloatStateOf(currentPerspective) }

    // 드래그 방향 고정(Lock) 상태 관리를 위한 변수
    var controlMode by remember { mutableStateOf(DragControlMode.NONE) }
    var accumulatedDx by remember { mutableFloatStateOf(0f) }
    var accumulatedDy by remember { mutableFloatStateOf(0f) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        controlMode = DragControlMode.NONE
                        accumulatedDx = 0f
                        accumulatedDy = 0f
                    },
                    onDragEnd = { controlMode = DragControlMode.NONE },
                    onDragCancel = { controlMode = DragControlMode.NONE },
                    onDrag = { change, dragAmount ->
                        change.consume()

                        accumulatedDx += dragAmount.x
                        accumulatedDy += dragAmount.y

                        // 아직 제어 모드가 결정되지 않은 경우 초기 이동 방향 판별 (10px 이상 이동 시 판별)
                        if (controlMode == DragControlMode.NONE) {
                            val distSq = accumulatedDx * accumulatedDx + accumulatedDy * accumulatedDy
                            if (distSq > 100f) { // 10px 이상의 감지 스레시홀드
                                controlMode = if (abs(accumulatedDx) > abs(accumulatedDy)) {
                                    DragControlMode.WIDTH_ONLY
                                } else {
                                    DragControlMode.PERSPECTIVE_ONLY
                                }
                            }
                        }

                        // 결정된 제어 모드에 따라 한 가지 축의 값만 조절
                        when (controlMode) {
                            DragControlMode.WIDTH_ONLY -> {
                                val deltaX = dragAmount.x / size.width
                                bottomWidth = (bottomWidth + deltaX * 1.5f).coerceIn(0.2f, 0.9f)
                                viewModel.updateSymmetricWarp(bottomWidth, perspectiveRatio)
                            }
                            DragControlMode.PERSPECTIVE_ONLY -> {
                                val deltaY = dragAmount.y / size.height
                                perspectiveRatio = (perspectiveRatio - deltaY * 1.2f).coerceIn(0.4f, 1.0f)
                                viewModel.updateSymmetricWarp(bottomWidth, perspectiveRatio)
                            }
                            DragControlMode.NONE -> {}
                        }
                    }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val centerX = w * 0.5f

        // 1. 세로 Center Line
        drawLine(
            color = Color.White.copy(alpha = 0.4f),
            start = Offset(centerX, 0f),
            end = Offset(centerX, h),
            strokeWidth = 3f
        )

        // 2. 대칭 사다리꼴 좌표 계산
        val p1 = Offset(warp[0] * w, warp[1] * h) // 좌상
        val p2 = Offset(warp[2] * w, warp[3] * h) // 우상
        val p3 = Offset(warp[4] * w, warp[5] * h) // 좌하
        val p4 = Offset(warp[6] * w, warp[7] * h) // 우하

        // 선택/조작 중인 모드에 따라 두께 하이라이트 제공
        val widthLineStroke = if (controlMode == DragControlMode.WIDTH_ONLY) 8f else 5f
        val perspectiveLineStroke = if (controlMode == DragControlMode.PERSPECTIVE_ONLY) 5f else 2f

        // 좌우 핵심 세로 정렬선 (Cyan)
        drawLine(Color.Cyan, p1, p3, strokeWidth = widthLineStroke)
        drawLine(Color.Cyan, p2, p4, strokeWidth = widthLineStroke)

        // 상단/하단 경계 보조선 (원근 조절 모드 시 조금 더 선명하게 표시)
        val horizontalAlpha = if (controlMode == DragControlMode.PERSPECTIVE_ONLY) 0.6f else 0.2f
        drawLine(Color.Cyan.copy(alpha = horizontalAlpha), p1, p2, strokeWidth = perspectiveLineStroke)
        drawLine(Color.Cyan.copy(alpha = horizontalAlpha), p3, p4, strokeWidth = perspectiveLineStroke)
    }
}

/**
 * 3단계: 센싱 라인 설정 오버레이
 */
@Composable
fun GateSetupOverlay(
    viewModel: CalibrationViewModel,
    activeHandle: HandleTarget,
    onHandleChanged: (HandleTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentCalibData by rememberUpdatedState(viewModel.calibrationData)
    val currentActiveHandle by rememberUpdatedState(activeHandle)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val touchY = startOffset.y / size.height
                        val data = currentCalibData

                        val ballDist = abs(touchY - data.ballYRatio)
                        val gateADist = abs(touchY - data.gateAYRatio)
                        val gateBDist = abs(touchY - data.gateBYRatio)

                        val minDist = minOf(ballDist, gateADist, gateBDist)

                        if (minDist < 0.15f) {
                            val target = when (minDist) {
                                ballDist -> HandleTarget.BALL
                                gateADist -> HandleTarget.GATE_A
                                else -> HandleTarget.GATE_B
                            }
                            onHandleChanged(target)
                        }
                    },
                    onDragEnd = { onHandleChanged(HandleTarget.NONE) },
                    onDragCancel = { onHandleChanged(HandleTarget.NONE) },
                    onDrag = { change, _ ->
                        change.consume()

                        val touchY = change.position.y / size.height
                        val data = currentCalibData

                        when (currentActiveHandle) {
                            HandleTarget.BALL -> {
                                val newBallY = touchY.coerceIn(data.gateAYRatio + 0.04f, 0.96f)
                                viewModel.updateYRatios(newBallY, data.gateAYRatio, data.gateBYRatio)
                            }
                            HandleTarget.GATE_A -> {
                                val newGateAY = touchY.coerceIn(data.gateBYRatio + 0.04f, data.ballYRatio - 0.04f)
                                viewModel.updateYRatios(data.ballYRatio, newGateAY, data.gateBYRatio)
                            }
                            HandleTarget.GATE_B -> {
                                val newGateBY = touchY.coerceIn(0.04f, data.gateAYRatio - 0.04f)
                                viewModel.updateYRatios(data.ballYRatio, data.gateAYRatio, newGateBY)
                            }
                            HandleTarget.NONE -> {}
                        }
                    }
                )
            }
    ) {
        val data = currentCalibData
        val w = size.width
        val h = size.height

        val centerX = w * 0.5f

        drawLine(
            color = Color.White.copy(alpha = 0.3f),
            start = Offset(centerX, 0f),
            end = Offset(centerX, h),
            strokeWidth = 2f
        )

        val ballY = data.ballYRatio * h
        val gateAY = data.gateAYRatio * h
        val gateBY = data.gateBYRatio * h

        val isGateBSelected = currentActiveHandle == HandleTarget.GATE_B
        val gateBStroke = if (isGateBSelected) 9f else 4f

        val isGateASelected = currentActiveHandle == HandleTarget.GATE_A
        val gateAStroke = if (isGateASelected) 9f else 4f

        val isBallSelected = currentActiveHandle == HandleTarget.BALL
        val ballStroke = if (isBallSelected) 9f else 4f

        val greenColor = Color(0xFF39FF14)

        // 1. 센싱 종료선 (Gate B)
        drawLine(Color.Red, Offset(0f, gateBY), Offset(w, gateBY), strokeWidth = gateBStroke)
        drawCircle(Color.Red.copy(alpha = 0.4f), radius = if (isGateBSelected) 40f else 32f, center = Offset(centerX, gateBY))
        drawCircle(Color.Red, radius = 9f, center = Offset(centerX, gateBY))

        // 2. 센싱 시작선 (Gate A)
        drawLine(greenColor, Offset(0f, gateAY), Offset(w, gateAY), strokeWidth = gateAStroke)
        drawCircle(greenColor.copy(alpha = 0.4f), radius = if (isGateASelected) 40f else 32f, center = Offset(centerX, gateAY))
        drawCircle(greenColor, radius = 9f, center = Offset(centerX, gateAY))

        // 3. 공 위치
        drawLine(Color.Yellow, Offset(0f, ballY), Offset(w, ballY), strokeWidth = ballStroke)
        drawCircle(Color.Yellow.copy(alpha = 0.4f), radius = if (isBallSelected) 40f else 32f, center = Offset(centerX, ballY))
        drawCircle(Color.Yellow, radius = 9f, center = Offset(centerX, ballY))
    }
}

/**
 * 4단계: Gate A와 Gate B 세로 중앙 정렬 및 Chip 우측 외부 미니 아이콘 배치 오버레이 (Scope 에러 해결본)
 */
@Composable
fun SensingDistanceOverlay(
    viewModel: CalibrationViewModel,
    onValueClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val data = viewModel.calibrationData
    val density = LocalDensity.current

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { layoutCoordinates ->
                canvasSize = layoutCoordinates.size
            }
    ) {
        val wPx = canvasSize.width.toFloat()
        val hPx = canvasSize.height.toFloat()

        val gateAYPx = data.gateAYRatio * hPx
        val gateBYPx = data.gateBYRatio * hPx
        val ballYPx = data.ballYRatio * hPx

        val greenColor = Color(0xFF39FF14)

        // 1. 센싱 가이드 라인 3개 및 세로 양방향 화살표 렌더링
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (wPx > 0 && hPx > 0) {
                drawLine(Color.Red, Offset(0f, gateBYPx), Offset(wPx, gateBYPx), strokeWidth = 3f)
                drawLine(greenColor, Offset(0f, gateAYPx), Offset(wPx, gateAYPx), strokeWidth = 3f)
                drawLine(Color.Yellow, Offset(0f, ballYPx), Offset(wPx, ballYPx), strokeWidth = 3f)

                // 센싱 시작선 ~ 종료선 사이 양방향 화살표 (우측 70% 지점)
                val arrowX = wPx * 0.7f
                val startY = gateBYPx + 20f
                val endY = gateAYPx - 20f

                if (startY < endY) {
                    drawLine(Color.Yellow, Offset(arrowX, startY), Offset(arrowX, endY), strokeWidth = 4f)

                    // 상단 화살표 (Gate B)
                    val topPath = Path().apply {
                        moveTo(arrowX, startY - 15f)
                        lineTo(arrowX - 12f, startY + 12f)
                        lineTo(arrowX + 12f, startY + 12f)
                        close()
                    }
                    drawPath(topPath, Color.Yellow)

                    // 하단 화살표 (Gate A)
                    val bottomPath = Path().apply {
                        moveTo(arrowX, endY + 15f)
                        lineTo(arrowX - 12f, endY - 12f)
                        lineTo(arrowX + 12f, endY - 12f)
                        close()
                    }
                    drawPath(bottomPath, Color.Yellow)
                }
            }
        }

        // 2. Gate A와 Gate B 세로 중앙 좌표 계산 (Dp 변환)
        val arrowCenterYPx = (gateAYPx + gateBYPx) / 2f
        val centerYDp = with(density) { arrowCenterYPx.toDp() }
        val offsetXDp = with(density) { (wPx * 0.72f).toDp() }

        // 3. Chip과 우측 미니 아이콘 배치
        if (wPx > 0 && hPx > 0) {
            Row(
                modifier = Modifier
                    .offset(x = offsetXDp, y = centerYDp - 18.dp)
                    .clickable { onValueClick() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                    shadowElevation = 6.dp
                ) {
                    Text(
                        text = "${data.realDistanceCm.roundToInt()} cm",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Yellow.copy(alpha = 0.5f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "수정",
                        tint = Color.Yellow,
                        modifier = Modifier
                            .padding(6.dp)
                            .size(14.dp)
                    )
                }
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