package com.example.golf_putting.ui.screens.calibration

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.golf_putting.core.vision.CalibrationManager
import com.example.golf_putting.data.model.CalibrationData
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.util.*

class CalibrationViewModel : ViewModel() {
    var currentStep by mutableIntStateOf(1)
    var calibrationData by mutableStateOf(CalibrationManager.activeCalibrationData)
    
    var backgroundScanResult by mutableStateOf<Bitmap?>(null)
    var ballDetectionResult by mutableStateOf<Bitmap?>(null)
    var statusMessage by mutableStateOf("")

    fun updateBaseSettings(dist: Float, ballY: Float, gateA: Float, gateB: Float) {
        calibrationData = calibrationData.copy(
            realDistanceCm = dist,
            ballYRatio = ballY,
            gateAYRatio = gateA,
            gateBYRatio = gateB
        )
    }

    fun updateWarpPoint(index: Int, x: Float, y: Float) {
        val newWarp = calibrationData.warpPoints.copyOf()
        newWarp[index * 2] = x.coerceIn(0f, 1f)
        newWarp[index * 2 + 1] = y.coerceIn(0f, 1f)
        calibrationData = calibrationData.copy(warpPoints = newWarp)
    }

    fun scanBackground(frame: Bitmap) {
        val mat = Mat()
        Utils.bitmapToMat(frame, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        val mask = Mat()
        Imgproc.threshold(gray, mask, 235.0, 255.0, Imgproc.THRESH_BINARY)
        val highlight = Mat(mat.size(), mat.type(), Scalar(255.0, 165.0, 0.0, 255.0))
        val resultMat = Mat()
        mat.copyTo(resultMat)
        highlight.copyTo(resultMat, mask)
        val resultBitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(resultMat, resultBitmap)
        backgroundScanResult = resultBitmap
        statusMessage = "빛 반사 영역 스캔 완료"
        mat.release(); gray.release(); mask.release(); highlight.release(); resultMat.release()
    }

    fun detectAndExtractBallColor(frame: Bitmap) {
        val mat = Mat()
        Utils.bitmapToMat(frame, mat)
        val hsv = Mat()
        Imgproc.cvtColor(mat, hsv, Imgproc.COLOR_RGB2HSV)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(9.0, 9.0), 2.0)
        val circles = Mat()
        Imgproc.HoughCircles(gray, circles, Imgproc.HOUGH_GRADIENT, 1.0, 
            gray.rows() / 8.0, 100.0, 30.0, 20, 100)
            
        if (circles.cols() > 0) {
            val circleData = circles.get(0, 0)
            val center = Point(circleData[0], circleData[1])
            val radius = circleData[2].toInt()
            
            val mask = Mat.zeros(hsv.size(), CvType.CV_8UC1)
            Imgproc.circle(mask, center, (radius * 0.8).toInt(), Scalar(255.0), -1)
            val hValues = mutableListOf<Int>(); val sValues = mutableListOf<Int>(); val vValues = mutableListOf<Int>()
            for (r in 0 until hsv.rows()) {
                for (c in 0 until hsv.cols()) {
                    if (mask.get(r, c)[0] > 0) {
                        val p = hsv.get(r, c)
                        hValues.add(p[0].toInt()); sValues.add(p[1].toInt()); vValues.add(p[2].toInt())
                    }
                }
            }
            if (hValues.isNotEmpty()) {
                hValues.sort(); sValues.sort(); vValues.sort()
                val minIdx = (hValues.size * 0.05).toInt(); val maxIdx = (hValues.size * 0.95).toInt()
                
                // [고도화] HSV 범위와 함께 공의 픽셀 반지름도 저장
                calibrationData = calibrationData.copy(
                    ballHsvMin = intArrayOf(hValues[minIdx], sValues[minIdx], vValues[minIdx]),
                    ballHsvMax = intArrayOf(hValues[maxIdx], sValues[maxIdx], vValues[maxIdx]),
                    ballPixelRadius = radius.toFloat()
                )
                
                val resultMat = Mat()
                mat.copyTo(resultMat)
                Imgproc.circle(resultMat, center, radius, Scalar(57.0, 255.0, 20.0, 255.0), 5)
                val resultBitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(resultMat, resultBitmap)
                ballDetectionResult = resultBitmap
                statusMessage = "공 인식 성공! 반경: ${radius}px"
                resultMat.release()
            }
            mask.release()
        } else {
            statusMessage = "공을 찾을 수 없습니다."
        }
        mat.release(); hsv.release(); gray.release(); circles.release()
    }

    fun saveAndFinish() {
        CalibrationManager.saveActiveCalibration(calibrationData)
    }
}
