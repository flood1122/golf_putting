package com.example.golf_putting.core.vision

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
import kotlin.math.atan2
import kotlin.math.sqrt

object VideoAnalyzer {
    private const val TAG = "GolfPutt/Analyzer"

    suspend fun analyzeVideo(
        context: Context,
        filePath: String,
        calib: CalibrationData,
        readyTimestampUs: Long = 0L
    ): List<PointData> = withContext(Dispatchers.IO) {
        val results = mutableListOf<PointData>()
        val appContext = context.applicationContext

        Log.i(TAG, "[분석 시작] 프리셋: ${calib.presetName} | 기준 READY Timestamp: $readyTimestampUs us")

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var hierarchy: Mat? = null

        try {
            hierarchy = Mat()

            val file = File(filePath)
            if (!file.exists()) {
                Log.e(TAG, "[에러] 분석할 파일이 존재하지 않음: $filePath")
                throw Exception("파일 없음: $filePath")
            }

            extractor = MediaExtractor()
            FileInputStream(file).use { fis -> extractor?.setDataSource(fis.fd) }

            val trackIndex = selectVideoTrack(extractor!!)
            if (trackIndex < 0) {
                Log.e(TAG, "[에러] 비디오 트랙을 찾을 수 없음")
                throw Exception("유효한 비디오 트랙을 찾을 수 없습니다.")
            }
            extractor?.selectTrack(trackIndex)

            val format = extractor?.getTrackFormat(trackIndex)!!
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0

            Log.d(TAG, "[디코더 준비] MIME: $mime, Rotation: $rotation")

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false

            val hsvMin = Scalar(calib.ballHsvMin[0].toDouble(), calib.ballHsvMin[1].toDouble(), calib.ballHsvMin[2].toDouble())
            val hsvMax = Scalar(calib.ballHsvMax[0].toDouble(), calib.ballHsvMax[1].toDouble(), calib.ballHsvMax[2].toDouble())

            var currentRoiRect: Rect? = null
            var reportFrameMat: Mat? = null

            var startPt: Point? = null
            var isMotionStarted = false

            var detectedBallRadiusPx = calib.ballPixelRadius.toDouble()
            var lastPxPerCmX = (detectedBallRadiusPx * 2.0) / 4.27
            var lastPxPerCmY = lastPxPerCmX
            var missCount = 0
            var processedFrameCount = 0

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
                                Log.d(TAG, "[스트림 완료] Input EOS 도달")
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
                            if (readyTimestampUs > 0L && info.presentationTimeUs < readyTimestampUs) {
                                decoder.releaseOutputBuffer(outIndex, false)
                                continue@loop
                            }

                            processedFrameCount++
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
                                    Point((finalW * calib.warpPoints[0]).toDouble(), (finalH * calib.warpPoints[1]).toDouble()),
                                    Point((finalW * calib.warpPoints[2]).toDouble(), (finalH * calib.warpPoints[3]).toDouble()),
                                    Point((finalW * calib.warpPoints[4]).toDouble(), (finalH * calib.warpPoints[5]).toDouble()),
                                    Point((finalW * calib.warpPoints[6]).toDouble(), (finalH * calib.warpPoints[7]).toDouble())
                                )
                                val frameMat = PerspectiveTransformer.transform(rotatedMat, srcPoints, finalW, finalH)
                                rotatedMat.release()

                                if (startPt == null) {
                                    val globalX = finalW * 0.5
                                    val initialY = finalH * calib.ballYRatio.toDouble()

                                    detectedBallRadiusPx = calib.ballPixelRadius.toDouble()
                                    lastPxPerCmX = (detectedBallRadiusPx * 2.0) / 4.27
                                    lastPxPerCmY = lastPxPerCmX

                                    val searchW = (detectedBallRadiusPx * 4.0).toInt().coerceAtLeast(160)
                                    val searchH = (detectedBallRadiusPx * 8.0).toInt().coerceAtLeast(300)

                                    val roiLeft = (globalX - searchW / 2).toInt().coerceIn(0, finalW - searchW)
                                    val roiTop = (initialY - searchH / 4).toInt().coerceIn(0, finalH - searchH)
                                    val initRoi = Rect(roiLeft, roiTop, searchW, searchH)

                                    val roiMat = frameMat.submat(initRoi)
                                    val detectedLocalPt = BallTracker.findInitialBallCenter(roiMat, hierarchy!!)
                                    roiMat.release()

                                    val correctedX = if (detectedLocalPt != null) initRoi.x + detectedLocalPt.x else globalX
                                    val correctedY = if (detectedLocalPt != null) initRoi.y + detectedLocalPt.y else initialY

                                    startPt = Point(correctedX, correctedY)
                                    Log.i(TAG, "[왜곡 보정 완료] 매트 왜곡 흡수 후 확정된 StartPt: (${startPt!!.x}, ${startPt!!.y})")

                                    val dynamicRoiSize = (detectedBallRadiusPx * 3.5).toInt().coerceAtLeast(140)
                                    currentRoiRect = createRoiAround(startPt!!, dynamicRoiSize, finalW, finalH)
                                    reportFrameMat = frameMat.clone()

                                    saveDebugFirstFrame(appContext, frameMat, startPt!!, currentRoiRect!!, detectedBallRadiusPx)
                                } else if (currentRoiRect != null) {
                                    val roi = frameMat.submat(currentRoiRect!!)

                                    // ★ 원형도 및 크기 검증 로직이 포함된 추적 함수 호출 ★
                                    val trackedResult = BallTracker.trackBallHybridDetailed(
                                        roi, hierarchy!!, detectedBallRadiusPx
                                    )
                                    val trackedPtLocal = trackedResult.centerPt
                                    roi.release()

                                    if (trackedPtLocal != null) {
                                        missCount = 0

                                        val globalX = currentRoiRect!!.x + trackedPtLocal.x
                                        val globalY = currentRoiRect!!.y + trackedPtLocal.y
                                        val detectedPt = Point(globalX, globalY)

                                        val margin = detectedBallRadiusPx * 0.5
                                        val isOutOfBounds = globalX < -margin || globalX > (finalW + margin) ||
                                                globalY < -margin || globalY > (finalH + margin)

                                        if (isOutOfBounds) {
                                            Log.i(TAG, "[분석 종료] 공이 화면 밖으로 벗어남 (X: $globalX, Y: $globalY)")
                                            frameMat.release()
                                            decoder.releaseOutputBuffer(outIndex, false)
                                            break@loop
                                        }

                                        val moveDistanceY = startPt!!.y - detectedPt.y
                                        val requiredThreshold = detectedBallRadiusPx * 0.35

                                        if (!isMotionStarted) {
//                                            Log.d(TAG, "[STABILIZING -> PUTTING 대기] Frame #$processedFrameCount | Y이동: ${String.format(Locale.US, "%.1f", moveDistanceY)}px / 기준: ${String.format(Locale.US, "%.1f", requiredThreshold)}px")

                                            if (moveDistanceY >= requiredThreshold) {
                                                isMotionStarted = true
                                                Log.i(TAG, "==========================================")
                                                Log.i(TAG, "[PUTTING 감지 성공!] Frame #$processedFrameCount - 퍼팅 시작됨")
                                                Log.i(TAG, "==========================================")

                                                results.add(PointData(info.presentationTimeUs, startPt!!.x, startPt!!.y))
                                                results.add(PointData(info.presentationTimeUs, detectedPt.x, detectedPt.y))

                                                saveMotionStartImage(
                                                    appContext,
                                                    frameMat,
                                                    startPt!!,
                                                    detectedPt,
                                                    currentRoiRect!!,
                                                    detectedBallRadiusPx,
                                                    trackedResult.contour
                                                )
                                            } else {
                                                val dynamicRoiSize = (detectedBallRadiusPx * 3.5).toInt().coerceAtLeast(140)
                                                currentRoiRect = createRoiAround(startPt!!, dynamicRoiSize, finalW, finalH)
                                            }
                                        } else {
                                            results.add(PointData(info.presentationTimeUs, detectedPt.x, detectedPt.y))
//                                            Log.d(TAG, "[PUTTING 추적 중] Frame #$processedFrameCount - 좌표: (${detectedPt.x}, ${detectedPt.y}) | Pts: ${results.size}")

                                            val nextCenterY = if (results.size >= 2) {
                                                val prevPt = results[results.size - 2]
                                                val velocityY = detectedPt.y - prevPt.y
                                                (detectedPt.y + velocityY).coerceIn(0.0, finalH.toDouble())
                                            } else {
                                                (detectedPt.y - 15.0).coerceAtLeast(0.0)
                                            }

                                            val dynamicRoiSize = (detectedBallRadiusPx * 3.5).toInt().coerceAtLeast(140)
                                            currentRoiRect = createRoiAround(Point(detectedPt.x, nextCenterY), dynamicRoiSize, finalW, finalH)
                                        }
                                    } else {
                                        // 퍼터/손에 가려지거나 형태가 찌그러져 공으로 검출되지 않은 경우 (Skip 처리 및 예측 이동)
                                        if (isMotionStarted) {
                                            missCount++
                                            Log.w(TAG, "[PUTTING 추적 건너뜀] Frame #$processedFrameCount - 퍼터 가림/오검출 필터링됨 ($missCount/8)")

                                            val lastPt = if (results.isNotEmpty()) Point(results.last().x, results.last().y) else startPt!!
                                            val predictedY = (lastPt.y - (detectedBallRadiusPx * 0.8 * missCount)).coerceAtLeast(0.0)
                                            val dynamicRoiSize = (detectedBallRadiusPx * 4.0).toInt().coerceAtLeast(160)
                                            currentRoiRect = createRoiAround(Point(lastPt.x, predictedY), dynamicRoiSize, finalW, finalH)

                                            if (missCount >= 8) {
                                                Log.i(TAG, "[분석 종료] 퍼팅 추적 완료 (연속 미검출 8회 도달)")
                                                frameMat.release()
                                                decoder.releaseOutputBuffer(outIndex, false)
                                                break@loop
                                            }
                                        }
                                    }
                                }

                                frameMat.release()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[프레임 분석 에러] Frame #$processedFrameCount - ${e.message}", e)
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                }
            }

            Log.i(TAG, "[루프 완료] 총 처리 프레임: $processedFrameCount, 수집된 좌표 개수: ${results.size}")

            // ★ 리포트 생성 및 저장 로직 ★
            if (results.size >= 5 && reportFrameMat != null) {
                try {
                    val metrics = MetricsCalculator.calculatePuttingMetrics(results, calib, lastPxPerCmX, lastPxPerCmY)
                    ReportVisualizer.visualizeAndSaveReport(
                        appContext,
                        reportFrameMat!!,
                        results,
                        metrics,
                        detectedBallRadiusPx
                    )
                    Log.i(TAG, "[리포트 저장 완료] 성공적으로 퍼팅 궤적 이미지가 갤러리에 저장되었습니다.")
                } catch (e: Exception) {
                    Log.e(TAG, "[리포트 생성 및 저장 중 에러 발생]: ${e.message}", e)
                }
            } else {
                Log.w(TAG, "[리포트 저장 건너뜀] 수집 좌표 부족(${results.size}개/최소 5개 필요) 또는 FrameMat Null (Mat: ${reportFrameMat != null})")
            }
            reportFrameMat?.release()

        } catch (e: Exception) {
            Log.e(TAG, "[분석 전체 에러] 발생: ${e.message}", e)
        } finally {
            hierarchy?.release()
            try { decoder?.stop(); decoder?.release() } catch (e: Exception) {}
            extractor?.release()
            Log.i(TAG, "[분석 리소스 정리 완료]")
        }
        results
    }

    private fun saveMotionStartImage(
        context: Context,
        frameMat: Mat,
        startPt: Point,
        detectedPt: Point,
        roiRect: Rect,
        radiusPx: Double,
        contour: MatOfPoint?
    ) {
        try {
            val drawMat = Mat()
            Imgproc.cvtColor(frameMat, drawMat, Imgproc.COLOR_GRAY2RGBA)

            Imgproc.rectangle(drawMat, roiRect.tl(), roiRect.br(), Scalar(0.0, 255.0, 0.0, 255.0), 2)
            Imgproc.circle(drawMat, startPt, radiusPx.toInt(), Scalar(255.0, 255.0, 0.0, 255.0), 2)

            if (contour != null) {
                val globalContour = MatOfPoint()
                val points = contour.toArray().map { Point(it.x + roiRect.x, it.y + roiRect.y) }.toTypedArray()
                globalContour.fromArray(*points)
                Imgproc.drawContours(drawMat, listOf(globalContour), -1, Scalar(255.0, 0.0, 0.0, 255.0), 2)
                globalContour.release()
            }

            Imgproc.circle(drawMat, detectedPt, 6, Scalar(255.0, 0.0, 0.0, 255.0), -1)

            val label = String.format(Locale.US, "[Motion Start] Center: (%.1f, %.1f)", detectedPt.x, detectedPt.y)
            Imgproc.putText(drawMat, label, Point(30.0, 80.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(0.0, 255.0, 0.0, 255.0), 2)

            val fileName = "Putt_MotionStart_${System.currentTimeMillis()}"
            ReportVisualizer.saveImageToGallery(context, drawMat, fileName)
            Log.i(TAG, "[이미지 저장 complete] 모션 시작 순간 이미지 갤러리 저장 완료: $fileName.jpg")

            drawMat.release()
        } catch (e: Exception) {
            Log.e(TAG, "[모션 시작 이미지 저장 실패]: ${e.message}", e)
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return i
        }
        return -1
    }

    private fun saveDebugFirstFrame(
        context: Context,
        frameMat: Mat,
        startPt: Point,
        roiRect: Rect,
        radiusPx: Double
    ) {
        try {
            val debugMat = Mat()
            Imgproc.cvtColor(frameMat, debugMat, Imgproc.COLOR_GRAY2RGBA)

            Imgproc.rectangle(debugMat, roiRect.tl(), roiRect.br(), Scalar(0.0, 255.0, 0.0, 255.0), 3)
            Imgproc.circle(debugMat, startPt, radiusPx.toInt(), Scalar(255.0, 255.0, 0.0, 255.0), 2)
            Imgproc.circle(debugMat, startPt, 5, Scalar(0.0, 0.0, 255.0, 255.0), -1)

            val text = "Debug First Frame (StartPt & ROI)"
            Imgproc.putText(debugMat, text, Point(30.0, 80.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(0.0, 255.0, 0.0, 255.0), 2)

            ReportVisualizer.saveImageToGallery(context, debugMat, "Putt_Debug_FirstFrame_${System.currentTimeMillis()}")
            debugMat.release()
            Log.i(TAG, "[디버그 이미지 저장 완료] 첫 프레임 ROI 확인용 이미지 저장됨")
        } catch (e: Exception) {
            Log.e(TAG, "[디버그 이미지 저장 실패]: ${e.message}")
        }
    }

    private fun createRoiAround(pt: Point, size: Int, maxW: Int, maxH: Int): Rect {
        val half = size / 2
        val left = (pt.x - half).toInt().coerceIn(0, maxW - size)
        val top = (pt.y - half).toInt().coerceIn(0, maxH - size)
        return Rect(left, top, size.coerceAtMost(maxW), size.coerceAtMost(maxH))
    }

    // --- 내부 helper 객체 ---

    data class TrackResult(
        val centerPt: Point?,
        val contour: MatOfPoint?
    )

    private object BallTracker {
        fun findInitialBallCenter(roi: Mat, hierarchy: Mat): Point? {
            val contours = ArrayList<MatOfPoint>()
            val grayBinary = Mat()
            Imgproc.threshold(roi, grayBinary, 100.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.findContours(grayBinary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            var bestPt: Point? = null
            var maxArea = 0.0

            for (contour in contours) {
                val area = Geometry.contourArea(contour)
                if (area > 80.0 && area > maxArea) {
                    maxArea = area
                    val m = Geometry.moments(contour)
                    if (m.m00 != 0.0) {
                        bestPt = Point(m.m10 / m.m00, m.m01 / m.m00)
                    }
                }
            }

            grayBinary.release()
            contours.forEach { it.release() }
            return bestPt
        }

        /**
         * ★ Geometry 모듈 전용 + MatOfPoint2f 안전 형변환 적용 ★
         */
        fun trackBallHybridDetailed(
            roi: Mat,
            hierarchy: Mat,
            targetRadiusPx: Double
        ): TrackResult {
            val contours = ArrayList<MatOfPoint>()
            val grayBinary = Mat()

            Imgproc.threshold(roi, grayBinary, 90.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.findContours(grayBinary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            var bestPt: Point? = null
            var bestContour: MatOfPoint? = null
            var minScoreDiff = Double.MAX_VALUE

            val expectedArea = Math.PI * targetRadiusPx * targetRadiusPx

            for (contour in contours) {
                val area = Geometry.contourArea(contour)

                // ★ MatOfPoint2f 명시적 캐스팅으로 Geometry.arcLength 에러 해결 ★
                val contour2f = MatOfPoint2f(*contour.toArray())
                val perimeter = Geometry.arcLength(contour2f, true)
                contour2f.release()

                if (perimeter <= 0.0) continue

                // 1. 원형도 계산: (4 * π * Area) / (Perimeter^2) [1.0에 가까울수록 정원형]
                val circularity = (4.0 * Math.PI * area) / (perimeter * perimeter)

                // 2. 면적 비율 계산 (기준 공 크기 대비)
                val areaRatio = area / expectedArea

                // 원형도가 0.55 이상이고, 면적이 공 크기의 40% ~ 220% 내일 때만 공으로 선택
                if (circularity >= 0.55 && areaRatio in 0.4..2.2) {
                    val m = Geometry.moments(contour)
                    if (m.m00 != 0.0) {
                        val score = Math.abs(1.0 - circularity) + Math.abs(1.0 - areaRatio)
                        if (score < minScoreDiff) {
                            minScoreDiff = score
                            bestPt = Point(m.m10 / m.m00, m.m01 / m.m00)
                            bestContour = contour
                        }
                    }
                }
            }

            grayBinary.release()
            contours.forEach { if (it != bestContour) it.release() }
            return TrackResult(bestPt, bestContour)
        }
    }

    data class PuttingMetrics(
        val initialSpeedCmS: Double,
        val launchAngleDeg: Double,
        val decelerationCmS2: Double,
        val slopeM: Double,
        val interceptC: Double
    )

    private object MetricsCalculator {
        fun calculatePuttingMetrics(
            points: List<PointData>,
            calib: CalibrationData,
            pxPerCmX: Double,
            pxPerCmY: Double
        ): PuttingMetrics {
            val n = points.size
            val firstUs = points.first().timeUs

            var sumY = 0.0; var sumX = 0.0; var sumYY = 0.0; var sumYX = 0.0
            val tList = DoubleArray(n)
            val dList = DoubleArray(n)

            val startX = points.first().x
            val startY = points.first().y

            for (i in 0 until n) {
                val p = points[i]
                sumY += p.y
                sumX += p.x
                sumYY += p.y * p.y
                sumYX += p.y * p.x

                val t = (p.timeUs - firstUs) / 1_000_000.0
                val dxCm = (p.x - startX) / pxPerCmX
                val dyCm = (startY - p.y) / pxPerCmY
                val distCm = sqrt(dxCm * dxCm + dyCm * dyCm)

                tList[i] = t
                dList[i] = distCm
            }

            val denom = (n * sumYY - sumY * sumY)
            val m = if (denom != 0.0) (n * sumYX - sumY * sumX) / denom else 0.0
            val c = (sumX - m * sumY) / n

            val dx = (points.last().x - startX) / pxPerCmX
            val dy = (startY - points.last().y) / pxPerCmY
            val angleDeg = Math.toDegrees(atan2(dx, dy))

            var sumT2 = 0.0; var sumT3 = 0.0; var sumT4 = 0.0
            var sumTD = 0.0; var sumT2D = 0.0

            for (i in 0 until n) {
                val t = tList[i]
                val d = dList[i]
                val t2 = t * t
                sumT2 += t2
                sumT3 += t2 * t
                sumT4 += t2 * t2
                sumTD += t * d
                sumT2D += t2 * d
            }

            val det = sumT2 * sumT4 - sumT3 * sumT3
            val rawV0 = if (det != 0.0) (sumTD * sumT4 - sumT2D * sumT3) / det else 0.0
            val halfA = if (det != 0.0) (sumT2 * sumT2D - sumT3 * sumTD) / det else 0.0
            val rawDecel = -2.0 * halfA

            val greenFactor = calib.greenSpeedFactor.toDouble()

            return PuttingMetrics(
                initialSpeedCmS = abs(rawV0) * greenFactor,
                launchAngleDeg = angleDeg,
                decelerationCmS2 = (if (rawDecel > 0) rawDecel else 0.0) * greenFactor,
                slopeM = m,
                interceptC = c
            )
        }
    }

    object ReportVisualizer {
        fun visualizeAndSaveReport(
            context: Context,
            frame: Mat,
            points: List<PointData>,
            metrics: PuttingMetrics,
            detectedBallRadiusPx: Double
        ) {
            val drawMat = Mat()
            Imgproc.cvtColor(frame, drawMat, Imgproc.COLOR_GRAY2RGBA)
            val finalW = frame.cols().toDouble()
            val finalH = frame.rows().toDouble()

            Imgproc.line(drawMat, Point(finalW / 2.0, 0.0), Point(finalW / 2.0, finalH), Scalar(255.0, 255.0, 255.0, 100.0), 2)

            for (i in points.indices) {
                val p = Point(points[i].x, points[i].y)
                Imgproc.circle(drawMat, p, 4, Scalar(255.0, 0.0, 0.0, 255.0), -1)

                if (i > 0) {
                    val prevP = Point(points[i - 1].x, points[i - 1].y)
                    Imgproc.line(drawMat, prevP, p, Scalar(255.0, 255.0, 0.0, 200.0), 2)
                }
            }

            val startY = points.first().y
            val endY = 0.0
            val startX = metrics.slopeM * startY + metrics.interceptC
            val endX = metrics.slopeM * endY + metrics.interceptC
            Imgproc.line(drawMat, Point(startX, startY), Point(endX, endY), Scalar(0.0, 255.0, 255.0, 255.0), 3)

            val startPt = Point(points.first().x, points.first().y)
            Imgproc.circle(drawMat, startPt, detectedBallRadiusPx.toInt(), Scalar(255.0, 255.0, 0.0, 255.0), 3)

            val line1 = String.format(Locale.US, "[Report] Samples: %d pts | Radius: %.1f px", points.size, detectedBallRadiusPx)
            val line2 = String.format(Locale.US, "Speed: %.1f cm/s | Angle: %.2f deg", metrics.initialSpeedCmS, metrics.launchAngleDeg)
            val line3 = String.format(Locale.US, "Decel: %.1f cm/s2", metrics.decelerationCmS2)

            Imgproc.putText(drawMat, line1, Point(30.0, 60.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(0.0, 255.0, 0.0, 255.0), 2)
            Imgproc.putText(drawMat, line2, Point(30.0, 110.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.1, Scalar(0.0, 255.0, 255.0, 255.0), 3)
            Imgproc.putText(drawMat, line3, Point(30.0, 160.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(255.0, 165.0, 0.0, 255.0), 2)

            saveImageToGallery(context, drawMat, "Putt_Report_${System.currentTimeMillis()}")
            drawMat.release()
        }

        fun saveImageToGallery(context: Context, mat: Mat, fileName: String) {
            try {
                Log.d(TAG, "[이미지 저장 시도] 파일명: $fileName.jpg, 크기: ${mat.cols()}x${mat.rows()}")
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
                        val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
                        Log.i(TAG, "[이미지 저장 성공] Uri: $uri | Compress 성공 여부: $success")
                    } ?: Log.e(TAG, "[이미지 저장 실패] OutputStream 생성 실패")
                } else {
                    Log.e(TAG, "[이미지 저장 실패] MediaStore URI 반환값 null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[이미지 저장 실패 예외 발생]: ${e.message}", e)
            }
        }
    }
}