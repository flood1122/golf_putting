package com.example.golf_putting.core.vision

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.golf_putting.data.model.CalibrationData
import com.example.golf_putting.data.model.PointData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileInputStream
import java.util.*
import kotlin.math.abs

object VideoAnalyzer {
    private const val TAG = "GolfPutt/Analyzer"

    @SuppressLint("StaticFieldLeak")
    private var appContext: Context? = null

    // 실제 분석 결과에 사용될 계수
    var lastPxPerCmX: Double = 1.0
    var lastPxPerCmY: Double = 1.0

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun analyzeVideo(
        filePath: String,
        calib: CalibrationData
    ): List<PointData> = withContext(Dispatchers.IO) {
        val results = mutableListOf<PointData>()
        val startTime = System.currentTimeMillis()

        Log.i(TAG, "[분석 시작] 프리셋: ${calib.presetName}, 공 학습 반경: ${calib.ballPixelRadius}px")

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null

        var bgLineA: Mat? = null; var bgLineB: Mat? = null
        var diffMat: Mat? = null; var binaryMat: Mat? = null
        var hierarchy: Mat? = null

        try {
            bgLineA = Mat(); bgLineB = Mat()
            diffMat = Mat(); binaryMat = Mat()
            hierarchy = Mat()

            val file = File(filePath)
            if (!file.exists()) throw Exception("파일 없음: $filePath")

            extractor = MediaExtractor()
            FileInputStream(file).use { fis -> extractor?.setDataSource(fis.fd) }

            val trackIndex = selectVideoTrack(extractor!!)
            extractor?.selectTrack(trackIndex)
            val format = extractor?.getTrackFormat(trackIndex)!!
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var isOutputEOS = false
            var frameCount = 0
            var foundA = false; var foundB = false
            var gateAY = 0; var gateBY = 0
            var lineARect: Rect? = null; var lineBRect: Rect? = null

            // 캘리브레이션 HSV 범위 (Value 채널 기반 필터링으로 활용)
            val hsvMin = Scalar(calib.ballHsvMin[0].toDouble(), calib.ballHsvMin[1].toDouble(), calib.ballHsvMin[2].toDouble())
            val hsvMax = Scalar(calib.ballHsvMax[0].toDouble(), calib.ballHsvMax[1].toDouble(), calib.ballHsvMax[2].toDouble())

            while (!isOutputEOS) {
                val inIndex = decoder.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inIndex)
                    val sampleSize = extractor?.readSampleData(inputBuffer!!, 0) ?: -1
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor?.sampleTime ?: 0, 0)
                        extractor?.advance()
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10000)
                if (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) isOutputEOS = true
                    if (info.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outIndex)
                        val outFormat = decoder.getOutputFormat(outIndex)
                        val bufW = outFormat.getInteger(MediaFormat.KEY_WIDTH)
                        val bufH = outFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        
                        val yData = ByteArray(bufW * bufH)
                        outputBuffer?.get(yData)
                        val rawMat = Mat(bufH, bufW, CvType.CV_8UC1)
                        rawMat.put(0, 0, yData)

                        val rotatedMat = Mat()
                        when (rotation) {
                            90 -> Core.rotate(rawMat, rotatedMat, Core.ROTATE_90_CLOCKWISE)
                            180 -> Core.rotate(rawMat, rotatedMat, Core.ROTATE_180)
                            270 -> Core.rotate(rawMat, rotatedMat, Core.ROTATE_90_COUNTERCLOCKWISE)
                            else -> rawMat.copyTo(rotatedMat)
                        }
                        rawMat.release()

                        val finalW = rotatedMat.cols()
                        val finalH = rotatedMat.rows()

                        // 1. Perspective Transform (원근 보정) 실행
                        val srcPoints = listOf(
                            Point(finalW * calib.warpPoints[0].toDouble(), finalH * calib.warpPoints[1].toDouble()),
                            Point(finalW * calib.warpPoints[2].toDouble(), finalH * calib.warpPoints[3].toDouble()),
                            Point(finalW * calib.warpPoints[4].toDouble(), finalH * calib.warpPoints[5].toDouble()),
                            Point(finalW * calib.warpPoints[6].toDouble(), finalH * calib.warpPoints[7].toDouble())
                        )
                        val frameMat = PerspectiveTransformer.transform(rotatedMat, srcPoints, finalW, finalH)
                        rotatedMat.release()

                        if (frameCount == 0) {
                            gateAY = (finalH * calib.gateAYRatio).toInt()
                            gateBY = (finalH * calib.gateBYRatio).toInt()
                            
                            // [고도화] 학습된 공 크기와 게이트 거리를 기반으로 정밀 계수 산출
                            // 골프공 공인 규격 지름: 4.27cm
                            lastPxPerCmX = (calib.ballPixelRadius * 2.0) / 4.27
                            lastPxPerCmY = abs(gateAY - gateBY).toDouble() / calib.realDistanceCm
                            
                            Log.i(TAG, "물리 거리 계수 확정 - X: $lastPxPerCmX px/cm, Y: $lastPxPerCmY px/cm")

                            lineARect = Rect(0, (gateAY - 30).coerceIn(0, finalH - 60), finalW, 60)
                            lineBRect = Rect(0, (gateBY - 30).coerceIn(0, finalH - 60), finalW, 60)

                            frameMat.submat(lineARect!!).copyTo(bgLineA!!)
                            frameMat.submat(lineBRect!!).copyTo(bgLineB!!)
                        } else {
                            if (!foundA) {
                                val roi = frameMat.submat(lineARect!!)
                                Core.absdiff(bgLineA!!, roi, diffMat!!)
                                Imgproc.threshold(diffMat!!, binaryMat!!, 30.0, 255.0, Imgproc.THRESH_BINARY)
                                val x = findBallX(roi, binaryMat!!, hsvMin, hsvMax, hierarchy!!)
                                if (x > 0) {
                                    foundA = true
                                    results.add(PointData(info.presentationTimeUs, x, gateAY.toDouble()))
                                    visualize(frameMat, x, gateAY.toDouble(), "GateA")
                                }
                                roi.release()
                            } else if (!foundB) {
                                val roi = frameMat.submat(lineBRect!!)
                                Core.absdiff(bgLineB!!, roi, diffMat!!)
                                Imgproc.threshold(diffMat!!, binaryMat!!, 30.0, 255.0, Imgproc.THRESH_BINARY)
                                val x = findBallX(roi, binaryMat!!, hsvMin, hsvMax, hierarchy!!)
                                if (x > 0) {
                                    foundB = true
                                    results.add(PointData(info.presentationTimeUs, x, gateBY.toDouble()))
                                    visualize(frameMat, x, gateBY.toDouble(), "GateB")
                                }
                                roi.release()
                            }
                        }
                        frameCount++
                        frameMat.release()
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (foundB) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "분석 에러: ${e.message}")
        } finally {
            bgLineA?.release(); bgLineB?.release(); diffMat?.release(); binaryMat?.release(); hierarchy?.release()
            decoder?.stop(); decoder?.release(); extractor?.release()
            Log.i(TAG, "[분석 종료] 검출 포인트 수: ${results.size}")
        }
        results
    }

    private fun findBallX(roi: Mat, binaryMask: Mat, hsvMin: Scalar, hsvMax: Scalar, hierarchy: Mat): Double {
        val hsvRoi = Mat()
        val colorMask = Mat()
        Imgproc.cvtColor(roi, hsvRoi, Imgproc.COLOR_GRAY2RGB)
        Imgproc.cvtColor(hsvRoi, hsvRoi, Imgproc.COLOR_RGB2HSV)
        Core.inRange(hsvRoi, hsvMin, hsvMax, colorMask)
        
        val finalMask = Mat()
        Core.bitwise_and(binaryMask, colorMask, finalMask)
        
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(finalMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        var ballX = -1.0
        var maxArea = 0.0
        for (contour in contours) {
            val area = Geometry.contourArea(contour)
            if (area > 100.0 && area > maxArea) {
                maxArea = area
                val m = Geometry.moments(contour)
                if (m.m00 != 0.0) ballX = m.m10 / m.m00
            }
        }
        hsvRoi.release(); colorMask.release(); finalMask.release()
        contours.forEach { it.release() }
        return ballX
    }

    private fun visualize(frame: Mat, x: Double, y: Double, label: String) {
        val drawMat = Mat()
        Imgproc.cvtColor(frame, drawMat, Imgproc.COLOR_GRAY2RGBA)
        Imgproc.circle(drawMat, Point(x, y), 35, Scalar(0.0, 255.0, 255.0, 255.0), 3)
        // 디버깅용 이미지 저장 활성화
        saveImageToGallery(drawMat, "${label}_Detected_${System.currentTimeMillis()}")
        drawMat.release()
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return i
        }
        return -1
    }

    private fun saveImageToGallery(mat: Mat, fileName: String) {
        val context = appContext ?: return
        try {
            val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, bitmap)
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$fileName.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GolfPutt")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) } }
        } catch (e: Exception) {}
    }
}
