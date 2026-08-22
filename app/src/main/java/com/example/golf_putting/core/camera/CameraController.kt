package com.example.golf_putting.core.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class CameraController(
    private val context: Context,
    private val config: HighSpeedConfig,
    private val previewSurface: Surface
) {
    private val TAG = "GolfPutt"

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var currentVideoPath: String? = null

    fun getCurrentPath(): String? = currentVideoPath

    fun openCamera() {
        Log.d(TAG, "[OPEN] 카메라 오픈 요청 시작 (Config FPS: ${config.fpsRange}, Size: ${config.size})")
        startBackgroundThread()
        backgroundHandler?.post {
            try {
                @SuppressLint("MissingPermission")
                manager.openCamera(config.cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        Log.d(TAG, "[OPEN] CameraDevice 오픈 성공 (ID: ${camera.id})")
                        cameraDevice = camera
                        prepareRecorderAndSession()
                    }
                    override fun onDisconnected(camera: CameraDevice) {
                        Log.w(TAG, "[OPEN] CameraDevice 연결 해제됨")
                        release()
                    }
                    override fun onError(camera: CameraDevice, error: Int) { 
                        Log.e(TAG, "[OPEN] 카메라 하드웨어 에러 발생: $error")
                        release() 
                    }
                }, backgroundHandler)
            } catch (e: Exception) {
                Log.e(TAG, "[OPEN] openCamera 예외 발생", e)
            }
        }
    }

    private fun cleanupResources() {
        Log.d(TAG, "[CLEANUP] 기존 자원 정리 시작")
        try {
            session?.apply {
                try {
                    stopRepeating()
                    abortCaptures()
                } catch (e: Exception) {
                    Log.w(TAG, "[CLEANUP] session stop/abort 예외 (무시 가능): ${e.message}")
                }
                Thread.sleep(100)
                close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[CLEANUP] session close 예외: ${e.message}")
        }
        session = null

        try {
            recorder?.apply {
                try { stop() } catch (e: Exception) {}
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[CLEANUP] recorder release 예외: ${e.message}")
        }
        recorder = null
        recorderSurface = null
        Log.d(TAG, "[CLEANUP] 기존 자원 정리 완료")
    }

    private fun prepareRecorderAndSession() {
        val camera = cameraDevice ?: run {
            Log.e(TAG, "[PREPARE] Fail: cameraDevice가 null입니다.")
            return
        }
        val handler = backgroundHandler ?: run {
            Log.e(TAG, "[PREPARE] Fail: backgroundHandler가 null입니다.")
            return
        }
        
        if (Thread.currentThread() != handler.looper.thread) {
            handler.post { prepareRecorderAndSession() }
            return
        }

        try {
            cleanupResources()
            Thread.sleep(200)

            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            recorder = newRecorder

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val movieDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (!movieDir.exists()) movieDir.mkdirs()
            val videoFile = File(movieDir, "GOLF_PUTT_$timeStamp.mp4")
            currentVideoPath = videoFile.absolutePath
            
            Log.d(TAG, "[PREPARE] MediaRecorder 설정 시작 -> Target Path: $currentVideoPath")
            Log.d(TAG, "[PREPARE] 설정 파라미터 - FPS: ${config.fpsRange.upper}, Size: ${config.size.width}x${config.size.height}, Bitrate: 60Mbps")

            with(newRecorder) {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(currentVideoPath)
                setVideoEncodingBitRate(60_000_000)
                setVideoFrameRate(config.fpsRange.upper)
                setVideoSize(config.size.width, config.size.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setOrientationHint(config.sensorOrientation) 
                prepare()
                recorderSurface = surface
            }
            Log.d(TAG, "[PREPARE] MediaRecorder.prepare() 성공")

            val surfaces = listOf(previewSurface, recorderSurface!!)
            Log.d(TAG, "[PREPARE] createConstrainedHighSpeedCaptureSession 호출 시도 (Surfaces count: ${surfaces.size})")

            camera.createConstrainedHighSpeedCaptureSession(
                surfaces, 
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        Log.d(TAG, "[SESSION] HighSpeedCaptureSession 구성 완료 (onConfigured)")
                        session = s as CameraConstrainedHighSpeedCaptureSession
                        try {
                            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            builder.addTarget(previewSurface)
                            builder.addTarget(recorderSurface!!)
                            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.fpsRange)

                            Log.d(TAG, "[SESSION] createHighSpeedRequestList 생성 시도 - Target FPS Range: ${config.fpsRange}")
                            val requests = session!!.createHighSpeedRequestList(builder.build())
                            Log.d(TAG, "[SESSION] HighSpeedRequestList 생성 완료! 요청 분할 수: ${requests.size}")

                            session!!.setRepeatingBurst(requests, null, backgroundHandler)
                            Log.d(TAG, "[SESSION] setRepeatingBurst 성공! 240fps 고속 버스트 스트리밍 시작됨")
                        } catch (e: Exception) { 
                            Log.e(TAG, "[SESSION] HighSpeed Request/Burst 설정 중 에러 발생!", e) 
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) { 
                        Log.e(TAG, "[SESSION] HighSpeedCaptureSession 구성 실패 (onConfigureFailed)") 
                    }
                }, 
                backgroundHandler
            )
        } catch (e: Exception) { 
            Log.e(TAG, "[PREPARE] prepareRecorderAndSession 예외 발생", e) 
        }
    }

    fun startRecording() {
        backgroundHandler?.post {
            try {
                Log.d(TAG, "[RECORD] MediaRecorder.start() 호출 시도")
                recorder?.start()
                Log.d(TAG, "[RECORD] MediaRecorder.start() 성공! 실제로 240fps 녹화가 진행 중입니다.")
            } catch (e: Exception) { 
                Log.e(TAG, "[RECORD] MediaRecorder.start() 실패!", e) 
            }
        }
    }

    suspend fun stopRecording() = suspendCoroutine<Unit> { continuation ->
        val handler = backgroundHandler
        if (handler == null) {
            continuation.resume(Unit)
            return@suspendCoroutine
        }

        handler.post {
            try {
                Log.d(TAG, "[STOP] 녹화 종료 절차 시작")
                try {
                    session?.stopRepeating()
                    session?.abortCaptures()
                    Log.d(TAG, "[STOP] session stopRepeating 및 abortCaptures 완료")
                } catch (e: Exception) {
                    Log.w(TAG, "[STOP] session stop 예외: ${e.message}")
                }
                Thread.sleep(100)

                val pathToScan = currentVideoPath
                try {
                    recorder?.stop()
                    Log.d(TAG, "[STOP] MediaRecorder.stop() 성공!")
                    if (pathToScan != null) {
                        MediaScannerConnection.scanFile(context, arrayOf(pathToScan), null) { path, uri ->
                            Log.d(TAG, "[SCAN] 미디어 스캔 완료 -> Path: $path, URI: $uri")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[STOP] MediaRecorder.stop() 실패!", e)
                }

                session?.close()
                session = null
                cameraDevice?.close()
                cameraDevice = null

                Thread.sleep(200)
                Log.d(TAG, "[STOP] 다음 촬영을 위한 openCamera() 재호출")
                openCamera()

            } catch (e: Exception) {
                Log.e(TAG, "[STOP] stopRecording 전체 예외 발생", e)
            } finally {
                continuation.resume(Unit)
            }
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
            Log.d(TAG, "[THREAD] CameraBackground 쓰레드 시작됨")
        }
    }

    fun release() {
        backgroundHandler?.post {
            Log.d(TAG, "[RELEASE] 카메라 자원 완전 해제 시작")
            cleanupResources()
            cameraDevice?.close()
            cameraDevice = null
            backgroundThread?.quitSafely()
            backgroundThread = null
            Log.d(TAG, "[RELEASE] 카메라 자원 완전 해제 완료")
        }
    }
}