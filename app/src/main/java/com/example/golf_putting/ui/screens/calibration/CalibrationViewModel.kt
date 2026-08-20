package com.example.golf_putting.ui.screens.calibration

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.golf_putting.core.vision.CalibrationManager
import com.example.golf_putting.data.model.CalibrationData
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import java.util.ArrayList

class CalibrationViewModel : ViewModel() {
    private val TAG = "GolfPutt/CalibViewModel"

    var currentStep by mutableIntStateOf(1)
    var calibrationData by mutableStateOf(CalibrationManager.activeCalibrationData)

    // 1단계에서 캡처한 이미지 A
    var imageA by mutableStateOf<Bitmap?>(null)

    var statusMessage by mutableStateOf("")

    /**
     * 2단계: 좌우 대칭 폭(bottomWidthRatio)과 원근 비율(perspectiveRatio)을 기반으로 사다리꼴 좌표(warpPoints)를 재계산합니다.
     * @param bottomWidthRatio 하단 좌우 폭 (0.2 ~ 0.9)
     * @param perspectiveRatio 상단 폭 / 하단 폭 비율 (0.4 ~ 1.0, 1.0이면 평행)
     */
    fun updateSymmetricWarp(bottomWidthRatio: Float, perspectiveRatio: Float) {
        val safeBottomWidth = bottomWidthRatio.coerceIn(0.2f, 0.9f)
        val safePerspective = perspectiveRatio.coerceIn(0.4f, 1.0f)

        val halfBottom = safeBottomWidth / 2f
        val halfTop = halfBottom * safePerspective

        // Y축 고정 위치 (상단 Y: 0.15, 하단 Y: 0.90)
        val topY = 0.15f
        val bottomY = 0.90f

        val newWarp = floatArrayOf(
            0.5f - halfTop, topY,       // 좌상
            0.5f + halfTop, topY,       // 우상
            0.5f - halfBottom, bottomY, // 좌하
            0.5f + halfBottom, bottomY  // 우하
        )

        calibrationData = calibrationData.copy(warpPoints = newWarp)
    }

    /**
     * 4단계: 실측 거리(cm)만 업데이트합니다.
     */
    fun updateRealDistance(distCm: Float) {
        calibrationData = calibrationData.copy(realDistanceCm = distCm.coerceIn(10f, 200f))
    }

    /**
     * 3단계: Y축 비율(공, Gate A, Gate B)만 독립적으로 안전하게 업데이트합니다.
     */
    fun updateYRatios(ballY: Float, gateA: Float, gateB: Float) {
        val safeGateB = gateB.coerceIn(0.02f, 0.85f)
        val safeGateA = gateA.coerceIn(safeGateB + 0.03f, 0.90f)
        val safeBallY = ballY.coerceIn(safeGateA + 0.03f, 0.98f)

        calibrationData = calibrationData.copy(
            ballYRatio = safeBallY,
            gateAYRatio = safeGateA,
            gateBYRatio = safeGateB
        )
    }

    fun updateBaseSettings(dist: Float, ballY: Float, gateA: Float, gateB: Float) {
        val safeGateB = gateB.coerceIn(0.02f, 0.85f)
        val safeGateA = gateA.coerceIn(safeGateB + 0.03f, 0.90f)
        val safeBallY = ballY.coerceIn(safeGateA + 0.03f, 0.98f)

        calibrationData = calibrationData.copy(
            realDistanceCm = dist,
            ballYRatio = safeBallY,
            gateAYRatio = safeGateA,
            gateBYRatio = safeGateB
        )
    }

    /**
     * [2단계] Image A에서 세로 매트를 자동 인식하여 대칭 가이드 초깃값을 설정합니다.
     */
    fun analyzeAndDetectMatShape() {
        val bitmap = imageA ?: run {
            setDefaultSymmetricWarp()
            return
        }

        try {
            val srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            val gray = Mat()
            Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

            val edges = Mat()
            Imgproc.Canny(gray, edges, 50.0, 150.0)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            var maxArea = 0.0
            var bestQuad: MatOfPoint2f? = null

            val imgWidth = srcMat.cols().toDouble()
            val imgHeight = srcMat.rows().toDouble()
            val minMatArea = (imgWidth * imgHeight) * 0.08

            for (contour in contours) {
                val contour2f = MatOfPoint2f(*contour.toArray())
                val approx2f = MatOfPoint2f()

                val area = Geometry.contourArea(contour)
                val peri = Geometry.arcLength(contour2f, true)
                Geometry.approxPolyDP(contour2f, approx2f, 0.03 * peri, true)

                if (approx2f.total() == 4L && area > minMatArea && area > maxArea) {
                    maxArea = area
                    bestQuad = approx2f
                }
                contour2f.release()
            }

            if (bestQuad != null) {
                val pts = bestQuad.toArray()
                val sortedByY = pts.sortedBy { it.y }
                val topPts = sortedByY.take(2).sortedBy { it.x }
                val bottomPts = sortedByY.takeLast(2).sortedBy { it.x }

                val topWidthPx = Math.abs(topPts[1].x - topPts[0].x)
                val bottomWidthPx = Math.abs(bottomPts[1].x - bottomPts[0].x)

                val bottomWidthRatio = (bottomWidthPx / imgWidth).toFloat().coerceIn(0.3f, 0.85f)
                val perspectiveRatio = if (bottomWidthPx > 0) (topWidthPx / bottomWidthPx).toFloat().coerceIn(0.5f, 1.0f) else 0.75f

                updateSymmetricWarp(bottomWidthRatio, perspectiveRatio)
                statusMessage = "매트 영역이 자동 감지되었습니다."
                Log.i(TAG, "대칭 매트 자동 검출 성공: 폭 $bottomWidthRatio, 원근 $perspectiveRatio")
                bestQuad.release()
            } else {
                setDefaultSymmetricWarp()
                statusMessage = "매트 감지 실패 - 대칭 기본 가이드를 적용합니다."
                Log.i(TAG, "매트 감지 실패 - 기본 대칭 가이드 적용")
            }

            srcMat.release(); gray.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }

        } catch (e: Exception) {
            Log.e(TAG, "매트 검출 오류: ${e.message}")
            setDefaultSymmetricWarp()
        }
    }

    private fun setDefaultSymmetricWarp() {
        updateSymmetricWarp(bottomWidthRatio = 0.70f, perspectiveRatio = 0.75f)
    }

    fun saveAndFinishWithPreset(presetName: String) {
        val finalData = calibrationData.copy(presetName = presetName)
        CalibrationManager.saveActiveCalibration(finalData)
        CalibrationManager.savePreset(presetName, finalData)
    }
}