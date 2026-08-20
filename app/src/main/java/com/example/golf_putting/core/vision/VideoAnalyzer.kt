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
import java.io.OutputStream
import java.util.*
import kotlin.math.abs

object VideoAnalyzer {
    private const val TAG = "GolfPutt/Analyzer"

    @SuppressLint("StaticFieldLeak")
    private var appContext: Context? = null

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

        Log.i(TAG, "[분석 시작] 프리셋: ${calib.presetName}, 학습 반경: ${calib.ballPixelRadius}")

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
            var isInputEOS = false
            var isOutputEOS = false
            var frameCount = 0
            var foundA = false; var foundB = false
            var gateAY = 0; var gateBY = 0
            var lineARect: Rect? = null; var lineBRect: Rect? = null

            val hsvMin = Scalar(calib.ballHsvMin[0].toDouble(), calib.ballHsvMin[1].toDouble(), calib.ballHsvMin[2].toDouble())
            val hsvMax = Scalar(calib.ballHsvMax[0].toDouble(), calib.ballHsvMax[1].toDouble(), calib.ballHsvMax[2].toDouble())

            // [수정] loop@ 라벨 추가
            loop@ while (!isOutputEOS) {
                if (!isInputEOS) {
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor?.readSampleData(inputBuffer, 0) ?: -1
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEOS = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor?.sampleTime ?: 0, 0)
                                extractor?.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10000)
                if (outIndex >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) isOutputEOS = true
                    if (info.size > 0) {
                        try {
                            val outputBuffer = decoder.getOutputBuffer(outIndex)
                            val outFormat = decoder.getOutputFormat(outIndex)
                            val bufW = outFormat.getInteger(MediaFormat.KEY_WIDTH)
                            val bufH = outFormat.getInteger(MediaFormat.KEY_HEIGHT)
                            val stride = if (outFormat.containsKey(MediaFormat.KEY_STRIDE)) outFormat.getInteger(MediaFormat.KEY_STRIDE) else bufW
                            val cropLeft = if (outFormat.containsKey("crop-left")) outFormat.getInteger("crop-left") else 0
                            val cropTop = if (outFormat.containsKey("crop-top")) outFormat.getInteger("crop-top") else 0

                            if (outputBuffer != null) {
                                val yData = ByteArray(bufW * bufH)
                                for (row in 0 until bufH) {
                                    outputBuffer.position(info.offset + (cropTop + row) * stride + cropLeft)
                                    val length = if (outputBuffer.remaining() < bufW) outputBuffer.remaining() else bufW
                                    if (length > 0) outputBuffer.get(yData, row * bufW, length)
                                }

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
                                    lastPxPerCmX = (calib.ballPixelRadius * 2.0) / 4.27
                                    lastPxPerCmY = abs(gateAY - gateBY).toDouble() / calib.realDistanceCm

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
                                            visualizeDetection(frameMat, x, gateAY.toDouble(), gateBY.toDouble(), info.presentationTimeUs, "Gate A", calib)
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
                                            visualizeDetection(frameMat, x, gateBY.toDouble(), gateAY.toDouble(), info.presentationTimeUs, "Gate B", calib)
                                        }
                                        roi.release()
                                    }
                                }
                                frameCount++
                                frameMat.release()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "프레임 분석 에러: ${e.message}")
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (foundB) break@loop
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "분석 전체 에러: ${e.message}")
        } finally {
            bgLineA?.release(); bgLineB?.release(); diffMat?.release(); binaryMat?.release(); hierarchy?.release()
            try { decoder?.stop(); decoder?.release() } catch (e: Exception) {}
            extractor?.release()
        }
        results
    }

    private fun findBallX(roi: Mat, binaryMask: Mat, hsvMin: Scalar, hsvMax: Scalar, hierarchy: Mat): Double {
        val contours = ArrayList<MatOfPoint>()

        // HSV 필터링 대신 움직임(차분) 바이너리 마스크를 바로 활용하여 윤곽선 검출
        Imgproc.findContours(binaryMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        var ballX = -1.0
        var maxArea = 0.0

        for (contour in contours) {
            val area = Geometry.contourArea(contour)
            // 노이즈 제거를 위한 최소 면적 스레시홀드
            if (area > 120.0 && area > maxArea) {
                maxArea = area
                val m = Geometry.moments(contour)
                if (m.m00 != 0.0) {
                    ballX = m.m10 / m.m00
                }
            }
        }

        contours.forEach { it.release() }
        return ballX
    }

    private fun visualizeDetection(frame: Mat, x: Double, y: Double, otherY: Double, timeUs: Long, label: String, calib: CalibrationData) {
        val drawMat = Mat()
        Imgproc.cvtColor(frame, drawMat, Imgproc.COLOR_GRAY2RGBA)
        val finalW = frame.cols().toDouble()
        val finalH = frame.rows().toDouble()

        Imgproc.line(drawMat, Point(finalW / 2.0, 0.0), Point(finalW / 2.0, finalH), Scalar(255.0, 255.0, 255.0, 100.0), 3)
        Imgproc.line(drawMat, Point(0.0, y), Point(finalW, y), Scalar(0.0, 255.0, 0.0, 255.0), 3)
        Imgproc.line(drawMat, Point(0.0, otherY), Point(finalW, otherY), Scalar(255.0, 0.0, 0.0, 255.0), 3)

        val radius = calib.ballPixelRadius.toInt()
        Imgproc.circle(drawMat, Point(x, y), radius, Scalar(255.0, 255.0, 0.0, 255.0), 3)
        Imgproc.circle(drawMat, Point(x, y), 5, Scalar(255.0, 0.0, 0.0, 255.0), -1)

        val text = "$label [${timeUs / 1000}ms] X:${x.toInt()}"
        Imgproc.putText(drawMat, text, Point(50.0, y + radius + 40), Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, Scalar(0.0, 255.0, 0.0, 255.0), 3)

        saveImageToGallery(drawMat, "${label.replace(" ", "_")}_Detected_${System.currentTimeMillis()}")
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
            if (uri != null) {
                val outputStream: OutputStream? = context.contentResolver.openOutputStream(uri)
                outputStream?.use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "이미지 저장 실패: ${e.message}")
        }
    }
}
