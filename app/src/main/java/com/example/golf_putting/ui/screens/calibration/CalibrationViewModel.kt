package com.example.golf_putting.ui.screens.calibration

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.golf_putting.core.vision.CalibrationManager
import com.example.golf_putting.core.vision.PerspectiveTransformer
import com.example.golf_putting.data.model.CalibrationData
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import java.util.ArrayList
import kotlin.math.roundToInt

enum class BallFeedbackType {
    SUCCESS, TOO_SMALL, TOO_LARGE
}

class CalibrationViewModel : ViewModel() {
    private val TAG = "GolfPutt/CalibViewModel"

    companion object {
        const val DEFAULT_BALL_RADIUS = 35f // 표준 촬영 거리 기준 기본 반지름 (px)
    }

    var currentStep by mutableIntStateOf(1)
    var calibrationData by mutableStateOf(
        CalibrationManager.activeCalibrationData.copy(
            userSetRadius = DEFAULT_BALL_RADIUS,
            ballPixelRadius = DEFAULT_BALL_RADIUS
        )
    )

    var imageA by mutableStateOf<Bitmap?>(null)

    var isBallDetected by mutableStateOf(false)
    var statusMessage by mutableStateOf("")

    // 스냅 대화상자(Confirm Dialog) 피드백 상태
    var showConfirmDialog by mutableStateOf(false)
    var lastCapturedFrame by mutableStateOf<Bitmap?>(null)

    fun updateSymmetricWarp(bottomWidth: Float, perspectiveRatio: Float) {
        val topWidth = bottomWidth * perspectiveRatio

        val leftBottomX = ((1.0f - bottomWidth) / 2.0f).coerceIn(0.0f, 0.4f)
        val rightBottomX = (1.0f - leftBottomX).coerceIn(0.6f, 1.0f)

        val leftTopX = ((1.0f - topWidth) / 2.0f).coerceIn(0.0f, 0.45f)
        val rightTopX = (1.0f - leftTopX).coerceIn(0.55f, 1.0f)

        // ★ 하단 Y축 좌표(warp[5], warp[7])를 0.9f에서 1.0f(화면 바닥)로 수정
        val newWarp = floatArrayOf(
            leftTopX, 0.15f,         // Top-Left (x, y)
            rightTopX, 0.15f,        // Top-Right (x, y)
            leftBottomX, 1.0f,       // Bottom-Left (x, y) -> 1.0f 로 하단 잘림 방지
            rightBottomX, 1.0f       // Bottom-Right (x, y) -> 1.0f 로 하단 잘림 방지
        )

        calibrationData = calibrationData.copy(warpPoints = newWarp)
    }

    fun updateBallYRatio(ballY: Float) {
        calibrationData = calibrationData.copy(
            ballYRatio = ballY.coerceIn(0.1f, 0.95f)
        )
    }

    fun updateGreenSpeedFactor(factor: Float) {
        calibrationData = calibrationData.copy(
            greenSpeedFactor = factor.coerceIn(0.5f, 1.5f)
        )
    }

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

                // ★ 위치 기반으로 전달하거나 명시적 파라미터명(bottomWidth) 사용
                updateSymmetricWarp(bottomWidth = bottomWidthRatio, perspectiveRatio = perspectiveRatio)
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
        // ★ bottomWidthRatio -> bottomWidth 로 파라미터명 수정
        updateSymmetricWarp(bottomWidth = 0.70f, perspectiveRatio = 0.75f)
    }

    fun detectBallFromLiveFrame(liveBitmap: Bitmap) {
        try {
            val liveMat = Mat()
            Utils.bitmapToMat(liveBitmap, liveMat)

            val liveW = liveMat.cols()
            val liveH = liveMat.rows()
            val scaleFactor = 640f / liveW.toFloat()

            val warp = calibrationData.warpPoints
            val srcPoints = listOf(
                Point(liveW * warp[0].toDouble(), liveH * warp[1].toDouble()),
                Point(liveW * warp[2].toDouble(), liveH * warp[3].toDouble()),
                Point(liveW * warp[4].toDouble(), liveH * warp[5].toDouble()),
                Point(liveW * warp[6].toDouble(), liveH * warp[7].toDouble())
            )

            val warpedMat = PerspectiveTransformer.transform(liveMat, srcPoints, liveW, liveH)

            val startY = (liveH * 0.50).toInt()
            val endY = (liveH * 0.95).toInt().coerceAtMost(liveH - 1)
            val roiHeight = endY - startY

            if (roiHeight > 0) {
                val roiRect = Rect(0, startY, liveW, roiHeight)
                val roiMat = warpedMat.submat(roiRect)

                val hsvMat = Mat()
                Imgproc.cvtColor(roiMat, hsvMat, Imgproc.COLOR_RGBA2BGR)
                Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_BGR2HSV)

                val currentMin = calibrationData.ballHsvMin
                val currentMax = calibrationData.ballHsvMax
                val hsvMin = Scalar(currentMin[0].toDouble(), currentMin[1].toDouble(), currentMin[2].toDouble())
                val hsvMax = Scalar(currentMax[0].toDouble(), currentMax[1].toDouble(), currentMax[2].toDouble())

                val binaryMat = Mat()
                Core.inRange(hsvMat, hsvMin, hsvMax, binaryMat)

                val grayMat = Mat()
                Imgproc.cvtColor(roiMat, grayMat, Imgproc.COLOR_RGBA2GRAY)
                val grayBinary = Mat()
                Imgproc.threshold(grayMat, grayBinary, 135.0, 255.0, Imgproc.THRESH_BINARY)

                val combined = Mat()
                val activeHsvPixels = Core.countNonZero(binaryMat)

                if (activeHsvPixels < 15) {
                    grayBinary.copyTo(combined)
                } else {
                    Core.bitwise_and(binaryMat, grayBinary, combined)
                }

                val contours = ArrayList<MatOfPoint>()
                val hierarchy = Mat()
                Imgproc.findContours(combined, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                var bestCenterY = -1.0
                var bestCenterX = -1.0
                var bestRadius = 15.0
                var maxScore = -1.0

                for (contour in contours) {
                    val area = Geometry.contourArea(contour)
                    if (area in 25.0..900.0) {
                        val contour2f = MatOfPoint2f(*contour.toArray())
                        val perimeter = Geometry.arcLength(contour2f, true)
                        contour2f.release()

                        if (perimeter > 0) {
                            val circularity = (4 * Math.PI * area) / (perimeter * perimeter)
                            if (circularity > 0.25) {
                                val moments = Geometry.moments(contour)
                                if (moments.m00 != 0.0) {
                                    val lx = moments.m10 / moments.m00
                                    val ly = moments.m01 / moments.m00
                                    val gy = startY + ly
                                    val radius = Math.sqrt(area / Math.PI)

                                    val distFromCenterX = Math.abs(lx - (liveW / 2.0))
                                    val centerWeight = 1.0 / (1.0 + distFromCenterX * 0.01)
                                    val score = circularity * area * centerWeight

                                    if (score > maxScore) {
                                        maxScore = score
                                        bestCenterX = lx
                                        bestCenterY = gy
                                        bestRadius = radius
                                    }
                                }
                            }
                        }
                    }
                }

                if (bestCenterX >= 0 && bestCenterY >= 0) {
                    val detectedYRatio = (bestCenterY / liveH).toFloat()
                    val standardRadius = (bestRadius * scaleFactor).toFloat().coerceIn(15f, 50f)

                    val alpha = 0.4f
                    val prevYRatio = calibrationData.ballYRatio
                    val prevRadius = calibrationData.ballPixelRadius

                    val smoothedYRatio = if (prevYRatio == 0.8f) detectedYRatio else (prevYRatio * (1f - alpha) + detectedYRatio * alpha)
                    val smoothedRadius = if (prevRadius == 30f) standardRadius else (prevRadius * (1f - alpha) + standardRadius * alpha)

                    calibrationData = calibrationData.copy(
                        ballYRatio = smoothedYRatio.coerceIn(0.1f, 0.95f),
                        ballPixelRadius = smoothedRadius
                    )
                    isBallDetected = true
                    statusMessage = "실시간 공 감지 중! (지름: ${(smoothedRadius * 2).roundToInt()}px)"
                } else {
                    isBallDetected = false
                    statusMessage = "매트 위에 공을 올려놓아 주세요."
                }

                roiMat.release(); hsvMat.release(); binaryMat.release()
                grayMat.release(); grayBinary.release(); combined.release()
                hierarchy.release()
                contours.forEach { it.release() }
            }

            liveMat.release()
            warpedMat.release()
        } catch (e: Exception) {
            Log.e(TAG, "실시간 라이브 공 탐지 에러: ${e.message}")
        }
    }

    /**
     * [3단계 Snap & Confirm] 고정 영점 가이드 기반 정밀 스캔
     */
    fun adjustBallManualLive(percentX: Float, percentY: Float, bitmap: Bitmap) {
        lastCapturedFrame = bitmap
        val calib = calibrationData
        val userRadius = calib.userSetRadius

        val frameMat = Mat()
        Utils.bitmapToMat(bitmap, frameMat)

        val centerX = (percentX * frameMat.cols()).toInt()
        val centerY = (percentY * frameMat.rows()).toInt()

        val roiRadius = (userRadius * 1.3f).toInt().coerceAtLeast(40)
        val roiRect = Rect(
            (centerX - roiRadius).coerceIn(0, frameMat.cols() - roiRadius * 2),
            (centerY - roiRadius).coerceIn(0, frameMat.rows() - roiRadius * 2),
            (roiRadius * 2).coerceAtMost(frameMat.cols()),
            (roiRadius * 2).coerceAtMost(frameMat.rows())
        )

        val roiMat = frameMat.submat(roiRect)
        val hsvMat = Mat()
        Imgproc.cvtColor(roiMat, hsvMat, Imgproc.COLOR_RGB2HSV)

        val mask = Mat.zeros(hsvMat.size(), CvType.CV_8UC1)
        val maskCenter = Point((roiRect.width / 2).toDouble(), (roiRect.height / 2).toDouble())
        Imgproc.circle(mask, maskCenter, userRadius.toInt(), Scalar(255.0), -1)

        val meanVal = MatOfDouble()
        val stdDevVal = MatOfDouble()
        Core.meanStdDev(hsvMat, meanVal, stdDevVal, mask)

        val meanArr = meanVal.toArray()
        val stdArr = stdDevVal.toArray()

        val hMean = if (meanArr.isNotEmpty()) meanArr[0] else 0.0
        val hStd = if (stdArr.isNotEmpty()) stdArr[0].coerceAtLeast(10.0) else 10.0
        val sMean = if (meanArr.size > 1) meanArr[1] else 0.0
        val sStd = if (stdArr.size > 1) stdArr[1].coerceAtLeast(20.0) else 20.0
        val vMean = if (meanArr.size > 2) meanArr[2] else 0.0
        val vStd = if (stdArr.size > 2) stdArr[2].coerceAtLeast(30.0) else 30.0

        val hsvMin = Scalar((hMean - hStd * 2.5).coerceAtLeast(0.0), (sMean - sStd * 2.5).coerceAtLeast(20.0), (vMean - vStd * 2.5).coerceAtLeast(40.0))
        val hsvMax = Scalar((hMean + hStd * 2.5).coerceAtMost(180.0), (sMean + sStd * 2.5).coerceAtMost(255.0), (vMean + vStd * 2.5).coerceAtMost(255.0))

        val binaryMat = Mat()
        Core.inRange(hsvMat, hsvMin, hsvMax, binaryMat)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binaryMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        var validRadius = userRadius
        var isVisionValid = false
        var bestCircularity = 0.0

        val minAllowedR = userRadius * 0.6f
        val maxAllowedR = userRadius * 1.4f

        for (contour in contours) {
            val area = Geometry.contourArea(contour)
            if (area > 80.0) {
                val contour2f = MatOfPoint2f(*contour.toArray())
                val perimeter = Geometry.arcLength(contour2f, true)
                val circularity = if (perimeter > 0) (4 * Math.PI * area) / (perimeter * perimeter) else 0.0

                val center = Point()
                val radiusArr = FloatArray(1)
                Geometry.minEnclosingCircle(contour2f, center, radiusArr)
                val detectedR = radiusArr[0]

                if (circularity > 0.45 && detectedR in minAllowedR..maxAllowedR) {
                    if (circularity > bestCircularity) {
                        bestCircularity = circularity
                        validRadius = detectedR
                        isVisionValid = true
                    }
                }
                contour2f.release()
            }
        }

        val rFinal = if (isVisionValid) (0.35f * validRadius + 0.65f * userRadius) else userRadius

        frameMat.release(); roiMat.release(); hsvMat.release(); mask.release()
        binaryMat.release(); hierarchy.release(); meanVal.release(); stdDevVal.release()
        contours.forEach { it.release() }

        calibrationData = calib.copy(
            ballYRatio = percentY,
            ballHsvMin = intArrayOf(hsvMin.`val`[0].toInt(), hsvMin.`val`[1].toInt(), hsvMin.`val`[2].toInt()),
            ballHsvMax = intArrayOf(hsvMax.`val`[0].toInt(), hsvMax.`val`[1].toInt(), hsvMax.`val`[2].toInt()),
            ballPixelRadius = rFinal,
            userSetRadius = userRadius
        )

        isBallDetected = isVisionValid
        statusMessage = if (isVisionValid) "공 검출 스캔이 완료되었습니다." else "기준 영역으로 고정 스캔되었습니다."

        showConfirmDialog = true
    }

    /**
     * 사용자 피드백으로 가이드 및 OpenCV 파라미터 재조정
     */
    fun applyUserFeedback(feedback: BallFeedbackType) {
        val currentRadius = calibrationData.userSetRadius
        when (feedback) {
            BallFeedbackType.SUCCESS -> {
                statusMessage = "공 세팅이 완료되었습니다."
                showConfirmDialog = false
            }
            BallFeedbackType.TOO_SMALL -> {
                val newRadius = (currentRadius * 1.15f).coerceAtMost(120f)
                calibrationData = calibrationData.copy(userSetRadius = newRadius, ballPixelRadius = newRadius)
                lastCapturedFrame?.let { adjustBallManualLive(0.5f, calibrationData.ballYRatio, it) }
            }
            BallFeedbackType.TOO_LARGE -> {
                val newRadius = (currentRadius * 0.85f).coerceAtLeast(15f)
                calibrationData = calibrationData.copy(userSetRadius = newRadius, ballPixelRadius = newRadius)
                lastCapturedFrame?.let { adjustBallManualLive(0.5f, calibrationData.ballYRatio, it) }
            }
        }
    }

    fun saveAndFinishWithPreset(presetName: String) {
        val finalData = calibrationData.copy(presetName = presetName)
        CalibrationManager.saveActiveCalibration(finalData)
        CalibrationManager.savePreset(presetName, finalData)
    }
}