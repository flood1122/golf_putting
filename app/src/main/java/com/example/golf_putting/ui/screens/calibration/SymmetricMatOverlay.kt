package com.example.golf_putting.ui.screens.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

@Composable
fun SymmetricMatOverlay(
    viewModel: CalibrationViewModel,
    modifier: Modifier = Modifier
) {
    val currentCalibData by rememberUpdatedState(viewModel.calibrationData)

    val warp = currentCalibData.warpPoints
    // ★ 상한값을 0.9f -> 1.0f 로 변경
    val currentBottomWidth = (warp[6] - warp[4]).coerceIn(0.2f, 1.0f)
    val currentTopWidth = (warp[2] - warp[0])
    val currentPerspective = (currentTopWidth / currentBottomWidth).coerceIn(0.3f, 1.0f)

    var bottomWidth by remember { mutableFloatStateOf(currentBottomWidth) }
    var perspectiveRatio by remember { mutableFloatStateOf(currentPerspective) }

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

                        if (controlMode == DragControlMode.NONE) {
                            val distSq = accumulatedDx * accumulatedDx + accumulatedDy * accumulatedDy
                            if (distSq > 100f) {
                                controlMode = if (abs(accumulatedDx) > abs(accumulatedDy)) {
                                    DragControlMode.WIDTH_ONLY
                                } else {
                                    DragControlMode.PERSPECTIVE_ONLY
                                }
                            }
                        }

                        when (controlMode) {
                            DragControlMode.WIDTH_ONLY -> {
                                val deltaX = dragAmount.x / size.width
                                // ★ 상한값을 0.9f -> 1.0f 로 변경하여 화면 전체 너비 활용 가능하도록 수정
                                bottomWidth = (bottomWidth + deltaX * 1.5f).coerceIn(0.2f, 1.0f)
                                viewModel.updateSymmetricWarp(bottomWidth, perspectiveRatio)
                            }
                            DragControlMode.PERSPECTIVE_ONLY -> {
                                val deltaY = dragAmount.y / size.height
                                perspectiveRatio = (perspectiveRatio - deltaY * 1.2f).coerceIn(0.3f, 1.0f)
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

        drawLine(
            color = Color.White.copy(alpha = 0.4f),
            start = Offset(centerX, 0f),
            end = Offset(centerX, h),
            strokeWidth = 3f
        )

        val p1 = Offset(warp[0] * w, warp[1] * h)
        val p2 = Offset(warp[2] * w, warp[3] * h)
        val p3 = Offset(warp[4] * w, warp[5] * h)
        val p4 = Offset(warp[6] * w, warp[7] * h)

        val widthLineStroke = if (controlMode == DragControlMode.WIDTH_ONLY) 8f else 5f
        val perspectiveLineStroke = if (controlMode == DragControlMode.PERSPECTIVE_ONLY) 5f else 2f

        drawLine(Color.Cyan, p1, p3, strokeWidth = widthLineStroke)
        drawLine(Color.Cyan, p2, p4, strokeWidth = widthLineStroke)

        val horizontalAlpha = if (controlMode == DragControlMode.PERSPECTIVE_ONLY) 0.6f else 0.2f
        drawLine(Color.Cyan.copy(alpha = horizontalAlpha), p1, p2, strokeWidth = perspectiveLineStroke)
        drawLine(Color.Cyan.copy(alpha = horizontalAlpha), p3, p4, strokeWidth = perspectiveLineStroke)
    }
}