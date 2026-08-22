package com.example.golf_putting.ui.screens.calibration

import android.graphics.Matrix
import android.util.Log
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

private const val TAG = "GolfPutt/BallSetup"

@Composable
fun BallSetupOverlay(
    viewModel: CalibrationViewModel,
    textureViewRef: State<TextureView?>,
    activeHandle: HandleTarget,
    onHandleChanged: (HandleTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentCalibData by rememberUpdatedState(viewModel.calibrationData)
    val currentActiveHandle by rememberUpdatedState(activeHandle)
    val isDetected = viewModel.isBallDetected

    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { size = it.size }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    Log.d(TAG, "[Tap Event] Raw Canvas Offset: x=${offset.x}, y=${offset.y}")
                    val ballYPx = currentCalibData.ballYRatio * size.height

                    // 공 가이드 주변 Y축 ±150px 영역을 터치했을 때만 탭 동작 수행
                    if (abs(offset.y - ballYPx) < 150f) {
                        Log.i(TAG, "[Tap Near Ball] 공 주변 영역 터치 감지 -> Manual Live Adjust 실행")
                        val textureView = textureViewRef.value
                        if (textureView != null && textureView.isAvailable) {
                            val highResBitmap = textureView.getBitmap(640, 480)
                            if (highResBitmap != null) {
                                viewModel.adjustBallManualLive(0.5f, currentCalibData.ballYRatio, highResBitmap)
                            }
                        }
                    } else {
                        Log.d(TAG, "[Tap Pass Through] 버튼 영역 터치 보장을 위해 카드 영역 탭 무시")
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val touchY = (startOffset.y / size.height).coerceIn(0f, 1f)
                        val ballYRatio = currentCalibData.ballYRatio
                        val ballDist = abs(touchY - ballYRatio)

                        if (ballDist < 0.12f) {
                            onHandleChanged(HandleTarget.BALL)
                            Log.d(TAG, "[Drag Handle Acquired] Target: BALL")
                        } else {
                            onHandleChanged(HandleTarget.NONE)
                        }
                    },
                    onDragEnd = { onHandleChanged(HandleTarget.NONE) },
                    onDragCancel = { onHandleChanged(HandleTarget.NONE) },
                    onDrag = { change, _ ->
                        if (currentActiveHandle == HandleTarget.BALL) {
                            change.consume()
                            val touchY = (change.position.y / size.height).coerceIn(0.15f, 0.85f)
                            viewModel.updateBallYRatio(touchY)
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val data = currentCalibData
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val centerX = w * 0.5f

            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                start = Offset(centerX, 0f),
                end = Offset(centerX, h),
                strokeWidth = 2f
            )

            val ballY = data.ballYRatio * h
            val isBallSelected = currentActiveHandle == HandleTarget.BALL
            val ballStroke = if (isBallSelected) 8f else 4f

            drawLine(
                color = if (isDetected) Color(0xFF00E676) else Color.Yellow,
                start = Offset(0f, ballY),
                end = Offset(w, ballY),
                strokeWidth = ballStroke
            )

            val ballRadiusPx = data.ballPixelRadius
            val userRadiusPx = data.userSetRadius
            val ballCenter = Offset(centerX, ballY)

            // 사용자 가이드 링 (Cyan)
            drawCircle(
                color = Color.Cyan.copy(alpha = 0.8f),
                radius = userRadiusPx,
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)),
                center = ballCenter
            )

            // 검출 결과 시각화
            if (isDetected) {
                drawCircle(
                    color = Color(0xFF00E676).copy(alpha = 0.25f),
                    radius = ballRadiusPx,
                    center = ballCenter
                )
                drawCircle(
                    color = Color(0xFF00E676),
                    radius = ballRadiusPx,
                    style = Stroke(width = 4f),
                    center = ballCenter
                )
                drawCircle(color = Color(0xFF00E676), radius = 6f, center = ballCenter)
            } else {
                drawCircle(
                    color = Color(0xFFFF9100).copy(alpha = 0.2f),
                    radius = ballRadiusPx,
                    center = ballCenter
                )
                drawCircle(
                    color = Color(0xFFFF9100),
                    radius = ballRadiusPx,
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)),
                    center = ballCenter
                )
                drawCircle(color = Color(0xFFFF9100), radius = 5f, center = ballCenter)
            }
        }
    }
}