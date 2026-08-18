package com.example.golf_putting

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.media.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.geometry.Geometry
import java.io.File
import java.io.FileInputStream
import java.util.ArrayList
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import org.opencv.android.Utils
import java.io.OutputStream
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

    // [수정] UI에서 설정한 동적 비율들을 파라미터로 받음
    suspend fun analyzeVideo(
        filePath: String,
        realDistanceCm: Float,
        ballYRatio: Float,
        gateAYRatio: Float,
        gateBYRatio: Float
    ): List<PointData> = withContext(Dispatchers.IO) {
        val results = mutableListOf<PointData>()
        val startTime = System.currentTimeMillis()

        Log.i(TAG, "==================================================")
        Log.i(TAG, "[분석 시작] 거리: ${realDistanceCm}cm, Ball:$ballYRatio, GateA:$gateAYRatio, GateB:$gateBYRatio")

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null

        var bgLineA: Mat? = null
        var bgLineB: Mat? = null
        var currLine: Mat? = null
        var diffMat: Mat? = null
        var binaryMat: Mat? = null
        var hierarchy: Mat? = null

        try {
            bgLineA = Mat(); bgLineB = Mat()
            currLine = Mat(); diffMat = Mat(); binaryMat = Mat()
            hierarchy = Mat()

            val file = File(filePath)
            if (!file.exists()) throw Exception("파일이 존재하지 않습니다: $filePath")

            extractor = MediaExtractor()
            FileInputStream(file).use { fis -> extractor?.setDataSource(fis.fd) }

            val trackIndex = selectVideoTrack(extractor!!)
            if (trackIndex < 0) throw Exception("비디오 트랙을 찾을 수 없습니다.")

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
            var foundA = false
            var foundB = false

            var ballRadius = 30
            var gateAY = 0
            var gateBY = 0
            var lineARect: Rect? = null
            var lineBRect: Rect? = null

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
                            val cropRight = if (outFormat.containsKey("crop-right")) outFormat.getInteger("crop-right") else bufW - 1
                            val cropBottom = if (outFormat.containsKey("crop-bottom")) outFormat.getInteger("crop-bottom") else bufH - 1

                            val actualW = cropRight - cropLeft + 1
                            val actualH = cropBottom - cropTop + 1

                            if (outputBuffer != null) {
                                val yData = ByteArray(actualW * actualH)
                                for (row in 0 until actualH) {
                                    outputBuffer.position(info.offset + (cropTop + row) * stride + cropLeft)
                                    outputBuffer.get(yData, row * actualW, actualW)
                                }

                                val rawMat = Mat(actualH, actualW, CvType.CV_8UC1)
                                rawMat.put(0, 0, yData)

                                val frameMat = Mat()
                                when (rotation) {
                                    90 -> Core.rotate(rawMat, frameMat, Core.ROTATE_90_CLOCKWISE)
                                    180 -> Core.rotate(rawMat, frameMat, Core.ROTATE_180)
                                    270 -> Core.rotate(rawMat, frameMat, Core.ROTATE_90_COUNTERCLOCKWISE)
                                    else -> rawMat.copyTo(frameMat)
                                }
                                rawMat.release()

                                val finalW = frameMat.cols()
                                val finalH = frameMat.rows()

                                if (frameCount == 0) {
                                    // [동적 파라미터 적용] 사용자가 세팅한 공 위치
                                    val expectedBallX = (finalW * 0.5).toInt()
                                    val expectedBallY = (finalH * ballYRatio).toInt()

                                    val searchBoxSize = 400
                                    val startX = (expectedBallX - searchBoxSize / 2).coerceAtLeast(0)
                                    val startY = (expectedBallY - searchBoxSize / 2).coerceAtLeast(0)
                                    val endX = (expectedBallX + searchBoxSize / 2).coerceAtMost(finalW)
                                    val endY = (expectedBallY + searchBoxSize / 2).coerceAtMost(finalH)

                                    val roiRect = Rect(startX, startY, endX - startX, endY - startY)
                                    val restrictedRoi = frameMat.submat(roiRect)

                                    val thresh = Mat()
                                    Imgproc.threshold(restrictedRoi, thresh, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

                                    val contours = ArrayList<MatOfPoint>()
                                    Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                                    var maxRadius = 30.0
                                    for (c in contours) {
                                        val area = Geometry.contourArea(c)
                                        if (area > 300) {
                                            val centerP = Point()
                                            val radiusF = FloatArray(1)
                                            val c2f = MatOfPoint2f(*c.toArray())
                                            Geometry.minEnclosingCircle(c2f, centerP, radiusF)
                                            if (radiusF[0] > maxRadius) maxRadius = radiusF[0].toDouble()
                                            c2f.release()
                                        }
                                    }
                                    contours.forEach { it.release() }
                                    thresh.release()
                                    restrictedRoi.release()

                                    ballRadius = maxRadius.toInt()
                                    lastPxPerCmX = (ballRadius * 2.0) / 4.27

                                    // [동적 파라미터 적용] 사용자가 세팅한 Gate 위치
                                    gateAY = (finalH * gateAYRatio).toInt()
                                    gateBY = (finalH * gateBYRatio).toInt()

                                    val pixelDistanceY = abs(gateAY - gateBY).toDouble()
                                    lastPxPerCmY = pixelDistanceY / realDistanceCm.toDouble()

                                    Log.i(TAG, "[Hybrid-Calib] 공 반경: ${ballRadius}px, X축: $lastPxPerCmX, Y축: $lastPxPerCmY")

                                    val detectAY = (gateAY - ballRadius).coerceIn(0, finalH - 10)
                                    val detectBY = (gateBY - ballRadius).coerceIn(0, finalH - 10)

                                    lineARect = Rect(0, detectAY, finalW, 10)
                                    lineBRect = Rect(0, detectBY, finalW, 10)

                                    val subA = frameMat.submat(lineARect!!)
                                    val subB = frameMat.submat(lineBRect!!)
                                    subA.copyTo(bgLineA!!)
                                    subB.copyTo(bgLineB!!)
                                    subA.release()
                                    subB.release()
                                }
                                else if (!foundA && lineARect != null) {
                                    val roi = frameMat.submat(lineARect!!)
                                    roi.copyTo(currLine!!)
                                    Core.absdiff(bgLineA!!, currLine!!, diffMat!!)
                                    Imgproc.threshold(diffMat!!, binaryMat!!, 25.0, 255.0, Imgproc.THRESH_BINARY)
                                    val x = findBallX(binaryMat!!, hierarchy!!)
                                    if (x > 0) {
                                        foundA = true
                                        results.add(PointData(info.presentationTimeUs, x, gateAY.toDouble()))
                                        Log.i(TAG, ">>> [Gate A 검출] ${info.presentationTimeUs / 1000}ms, X: $x")

                                        val drawMat = Mat()
                                        Imgproc.cvtColor(frameMat, drawMat, Imgproc.COLOR_GRAY2RGBA)

                                        Imgproc.line(drawMat, Point(finalW / 2.0, 0.0), Point(finalW / 2.0, finalH.toDouble()), Scalar(255.0, 255.0, 255.0, 100.0), 3)
                                        Imgproc.line(drawMat, Point(0.0, gateAY.toDouble()), Point(finalW.toDouble(), gateAY.toDouble()), Scalar(0.0, 255.0, 0.0, 255.0), 3)
                                        Imgproc.line(drawMat, Point(0.0, gateBY.toDouble()), Point(finalW.toDouble(), gateBY.toDouble()), Scalar(255.0, 0.0, 0.0, 255.0), 3)

                                        Imgproc.circle(drawMat, Point(x, gateAY.toDouble()), ballRadius, Scalar(255.0, 255.0, 0.0, 255.0), 3)
                                        Imgproc.circle(drawMat, Point(x, gateAY.toDouble()), 5, Scalar(255.0, 0.0, 0.0, 255.0), -1)

                                        val text = "Gate A [${info.presentationTimeUs / 1000}ms] X:${x.toInt()}"
                                        Imgproc.putText(drawMat, text, Point(50.0, gateAY.toDouble() + ballRadius + 40), Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, Scalar(0.0, 255.0, 0.0, 255.0), 3)

                                        saveImageToGallery(drawMat, "Gate_A_Detected_${System.currentTimeMillis()}")
                                        drawMat.release()
                                    }
                                    roi.release()
                                } else if (!foundB && lineBRect != null) {
                                    val roi = frameMat.submat(lineBRect!!)
                                    roi.copyTo(currLine!!)
                                    Core.absdiff(bgLineB!!, currLine!!, diffMat!!)
                                    Imgproc.threshold(diffMat!!, binaryMat!!, 25.0, 255.0, Imgproc.THRESH_BINARY)
                                    val x = findBallX(binaryMat!!, hierarchy!!)
                                    if (x > 0) {
                                        foundB = true
                                        results.add(PointData(info.presentationTimeUs, x, gateBY.toDouble()))
                                        Log.i(TAG, ">>> [Gate B 검출] ${info.presentationTimeUs / 1000}ms, X: $x")

                                        val drawMat = Mat()
                                        Imgproc.cvtColor(frameMat, drawMat, Imgproc.COLOR_GRAY2RGBA)

                                        Imgproc.line(drawMat, Point(finalW / 2.0, 0.0), Point(finalW / 2.0, finalH.toDouble()), Scalar(255.0, 255.0, 255.0, 100.0), 3)
                                        Imgproc.line(drawMat, Point(0.0, gateAY.toDouble()), Point(finalW.toDouble(), gateAY.toDouble()), Scalar(0.0, 255.0, 0.0, 255.0), 3)
                                        Imgproc.line(drawMat, Point(0.0, gateBY.toDouble()), Point(finalW.toDouble(), gateBY.toDouble()), Scalar(255.0, 0.0, 0.0, 255.0), 3)

                                        Imgproc.circle(drawMat, Point(x, gateBY.toDouble()), ballRadius, Scalar(255.0, 255.0, 0.0, 255.0), 3)
                                        Imgproc.circle(drawMat, Point(x, gateBY.toDouble()), 5, Scalar(255.0, 0.0, 0.0, 255.0), -1)

                                        val text = "Gate B [${info.presentationTimeUs / 1000}ms] X:${x.toInt()}"
                                        Imgproc.putText(drawMat, text, Point(50.0, gateBY.toDouble() + ballRadius + 40), Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, Scalar(255.0, 0.0, 0.0, 255.0), 3)

                                        saveImageToGallery(drawMat, "Gate_B_Detected_${System.currentTimeMillis()}")
                                        drawMat.release()
                                    }
                                    roi.release()
                                }
                                frameCount++
                                frameMat.release()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "프레임 메모리 복사 중 오류: ${e.message}")
                        }
                    }

                    decoder.releaseOutputBuffer(outIndex, false)
                    if (foundB) break@loop
                }
            }

        } catch (t: Throwable) {
            Log.e(TAG, "분석 중 심각한 오류 발생: ${t.javaClass.simpleName} - ${t.message}")
            t.printStackTrace()
        } finally {
            bgLineA?.release(); bgLineB?.release()
            currLine?.release(); diffMat?.release(); binaryMat?.release(); hierarchy?.release()
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) { }
            extractor?.release()

            Log.i(TAG, "[분석 종료] 소요 시간: ${System.currentTimeMillis() - startTime}ms")
        }

        results
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return i
        }
        return -1
    }

    private fun findBallX(binaryLine: Mat, hierarchy: Mat): Double {
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binaryLine, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        var ballX = -1.0
        var maxArea = 0.0
        for (contour in contours) {
            val area = Geometry.contourArea(contour)
            if (area > 150.0 && area > maxArea) {
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
        } catch (e: Exception) {}
    }
}