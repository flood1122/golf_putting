package com.example.golf_putting.ui.screens.calibration

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.golf_putting.core.camera.CameraController
import com.example.golf_putting.core.camera.HighSpeedConfig
import com.example.golf_putting.core.camera.findHighSpeedConfiguration
import com.example.golf_putting.core.vision.CalibrationManager
import com.example.golf_putting.ui.screens.practice.SliderRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationWizardScreen(
    onFinished: () -> Unit,
    viewModel: CalibrationViewModel = viewModel()
) {
    val context = LocalContext.current
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val highSpeedConfig = remember { findHighSpeedConfiguration(cameraManager) }
    val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
    
    val step = viewModel.currentStep

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("캘리브레이션 위저드 (${step}/4)", color = Color.White) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        },
        bottomBar = {
            BottomBar(step, onNext = {
                if (step < 4) viewModel.currentStep++
                else {
                    viewModel.saveAndFinish()
                    onFinished()
                }
            }, onPrev = {
                if (step > 1) viewModel.currentStep--
            })
        },
        containerColor = Color(0xFF121212)
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // Preview Area (Canvas Overlay added for Step 4)
            Box(modifier = Modifier.fillMaxWidth().height(350.dp).background(Color.Black)) {
                if (step == 2 && viewModel.backgroundScanResult != null) {
                    Image(bitmap = viewModel.backgroundScanResult!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else if (step == 3 && viewModel.ballDetectionResult != null) {
                    Image(bitmap = viewModel.ballDetectionResult!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else if (highSpeedConfig != null) {
                    CameraPreview(highSpeedConfig, textureViewRef)
                }

                if (step == 4) {
                    PerspectiveOverlay(viewModel)
                }
            }

            Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                when (step) {
                    1 -> Step1MatSettings(viewModel)
                    2 -> Step2BackgroundScan(viewModel, textureViewRef)
                    3 -> Step3BallCalibration(viewModel, textureViewRef)
                    4 -> Step4LayerAdjustment(viewModel)
                }
                if (viewModel.statusMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(viewModel.statusMessage, color = Color.Cyan, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
fun PerspectiveOverlay(viewModel: CalibrationViewModel) {
    val data = viewModel.calibrationData
    
    Canvas(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
        detectDragGestures { change, _ ->
            val touchX = change.position.x / size.width
            val touchY = change.position.y / size.height
            
            // 가장 가까운 점 찾기 (Warp Points: 4개)
            var minIdx = -1
            var minDist = 0.1f
            for (i in 0 until 4) {
                val dx = touchX - data.warpPoints[i*2]
                val dy = touchY - data.warpPoints[i*2 + 1]
                val d = Math.sqrt((dx*dx + dy*dy).toDouble()).toFloat()
                if (d < minDist) {
                    minDist = d
                    minIdx = i
                }
            }
            
            if (minIdx != -1) {
                viewModel.updateWarpPoint(minIdx, touchX, touchY)
            }
        }
    }) {
        val w = size.width
        val h = size.height
        
        // 1. Warp 영역 사각형 그리기
        val p1 = Offset(data.warpPoints[0] * w, data.warpPoints[1] * h)
        val p2 = Offset(data.warpPoints[2] * w, data.warpPoints[3] * h)
        val p3 = Offset(data.warpPoints[4] * w, data.warpPoints[5] * h)
        val p4 = Offset(data.warpPoints[6] * w, data.warpPoints[7] * h)
        
        drawLine(Color.Cyan, p1, p2, 2f)
        drawLine(Color.Cyan, p2, p4, 2f)
        drawLine(Color.Cyan, p4, p3, 2f)
        drawLine(Color.Cyan, p3, p1, 2f)
        
        drawCircle(Color.Cyan, 12f, p1); drawCircle(Color.Cyan, 12f, p2)
        drawCircle(Color.Cyan, 12f, p3); drawCircle(Color.Cyan, 12f, p4)

        // 2. 게이트 라인 및 공 위치 그리기
        drawLine(Color.Green.copy(alpha = 0.6f), Offset(0f, data.gateAYRatio * h), Offset(w, data.gateAYRatio * h), 4f)
        drawLine(Color.Red.copy(alpha = 0.6f), Offset(0f, data.gateBYRatio * h), Offset(w, data.gateBYRatio * h), 4f)
        drawCircle(Color.Yellow.copy(alpha = 0.5f), 30f, Offset(w/2, data.ballYRatio * h))
    }
}

@Composable
fun CameraPreview(config: HighSpeedConfig, textureViewRef: MutableState<TextureView?>) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var controller: CameraController? = null
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        textureViewRef.value = this@apply
                        st.setDefaultBufferSize(config.size.width, config.size.height)
                        controller = CameraController(ctx, config, Surface(st))
                        controller?.openCamera()
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { controller?.release(); return true }
                }
            }
        }
    )
}

@Composable
fun Step1MatSettings(viewModel: CalibrationViewModel) {
    Text("1. 매트 규격 설정", color = Color.White, fontSize = 18.sp, style = MaterialTheme.typography.titleLarge)
    Spacer(modifier = Modifier.height(16.dp))
    Text("Gate A ~ Gate B 실제 거리 (cm)", color = Color.Gray, fontSize = 14.sp)
    Slider(
        value = viewModel.calibrationData.realDistanceCm,
        onValueChange = { viewModel.updateBaseSettings(it, viewModel.calibrationData.ballYRatio, viewModel.calibrationData.gateAYRatio, viewModel.calibrationData.gateBYRatio) },
        valueRange = 10f..100f
    )
    Text("${viewModel.calibrationData.realDistanceCm.toInt()} cm", color = Color.White, modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.End))
}

@Composable
fun Step2BackgroundScan(viewModel: CalibrationViewModel, textureView: MutableState<TextureView?>) {
    Text("2. 배경 및 빛 반사 스캔", color = Color.White, fontSize = 18.sp)
    Text("공을 치운 후 버튼을 누르세요. 주황색 영역이 빛 반사 지점입니다.", color = Color.Gray, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = { textureView.value?.getBitmap(640, 480)?.let { viewModel.scanBackground(it) } }, modifier = Modifier.fillMaxWidth()) { Text("배경 스캔 시작") }
}

@Composable
fun Step3BallCalibration(viewModel: CalibrationViewModel, textureView: MutableState<TextureView?>) {
    Text("3. 공 색상 및 형태 학습", color = Color.White, fontSize = 18.sp)
    Text("공을 퍼팅 위치에 놓으세요. 녹색 링이 생기면 인식 성공입니다.", color = Color.Gray, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = { textureView.value?.getBitmap(640, 480)?.let { viewModel.detectAndExtractBallColor(it) } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.Green, contentColor = Color.Black)) { Text("공 인식 및 HSV 학습") }
}

@Composable
fun Step4LayerAdjustment(viewModel: CalibrationViewModel) {
    Text("4. 원근 및 위치 미세 조정", color = Color.White, fontSize = 18.sp)
    Text("화면의 하늘색 점을 드래그하여 매트 영역을 맞추고, 슬라이더로 라인을 조정하세요.", color = Color.Gray, fontSize = 13.sp)
    Spacer(modifier = Modifier.height(16.dp))
    val data = viewModel.calibrationData
    SliderRow("공 위치", data.ballYRatio, 0.5f, 0.95f) { viewModel.updateBaseSettings(data.realDistanceCm, it, data.gateAYRatio, data.gateBYRatio) }
    SliderRow("Gate A (학습용)", data.gateAYRatio, 0.1f, 0.9f) { viewModel.updateBaseSettings(data.realDistanceCm, data.ballYRatio, it, data.gateBYRatio) }
    SliderRow("Gate B (학습용)", data.gateBYRatio, 0.1f, 0.9f) { viewModel.updateBaseSettings(data.realDistanceCm, data.ballYRatio, data.gateAYRatio, it) }
}

@Composable
fun BottomBar(step: Int, onNext: () -> Unit, onPrev: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        if (step > 1) OutlinedButton(onClick = onPrev) { Text("이전") } else Spacer(modifier = Modifier.width(80.dp))
        Button(onClick = onNext) { Text(if (step == 4) "저장 및 완료" else "다음") }
    }
}
