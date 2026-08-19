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
        startBackgroundThread()
        backgroundHandler?.post {
            try {
                @SuppressLint("MissingPermission")
                manager.openCamera(config.cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        prepareRecorderAndSession()
                    }
                    override fun onDisconnected(camera: CameraDevice) { release() }
                    override fun onError(camera: CameraDevice, error: Int) { 
                        Log.e("GolfPutt", "카메라 하드웨어 에러: $error")
                        release() 
                    }
                }, backgroundHandler)
            } catch (e: Exception) { Log.e("GolfPutt", "Open Error", e) }
        }
    }

    private fun cleanupResources() {
        try {
            session?.apply {
                try {
                    stopRepeating()
                    abortCaptures()
                } catch (e: Exception) {}
                Thread.sleep(100)
                close()
            }
        } catch (e: Exception) {}
        session = null

        try {
            recorder?.apply {
                try { stop() } catch (e: Exception) {}
                release()
            }
        } catch (e: Exception) {}
        recorder = null
        recorderSurface = null
    }

    private fun prepareRecorderAndSession() {
        val camera = cameraDevice ?: return
        val handler = backgroundHandler ?: return
        
        if (Thread.currentThread() != handler.looper.thread) {
            handler.post { prepareRecorderAndSession() }
            return
        }

        try {
            cleanupResources()
            Thread.sleep(500)

            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            recorder = newRecorder

            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val movieDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (!movieDir.exists()) movieDir.mkdirs()
            val videoFile = File(movieDir, "GOLF_PUTT_$timeStamp.mp4")
            currentVideoPath = videoFile.absolutePath
            
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

            camera.createConstrainedHighSpeedCaptureSession(listOf(previewSurface, recorderSurface!!), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s as CameraConstrainedHighSpeedCaptureSession
                    try {
                        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder.addTarget(previewSurface)
                        builder.addTarget(recorderSurface!!)
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.fpsRange)
                        val requests = session!!.createHighSpeedRequestList(builder.build())
                        session!!.setRepeatingBurst(requests, null, backgroundHandler)
                    } catch (e: Exception) { Log.e("GolfPutt", "Burst Error", e) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) { Log.e("GolfPutt", "Session Fail") }
            }, backgroundHandler)
        } catch (e: Exception) { Log.e("GolfPutt", "Prepare Error", e) }
    }

    fun startRecording() {
        backgroundHandler?.post {
            try { recorder?.start() } catch (e: Exception) { Log.e("GolfPutt", "Start Error", e) }
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
                try {
                    session?.stopRepeating()
                    session?.abortCaptures()
                } catch (e: Exception) {}
                Thread.sleep(150)

                val pathToScan = currentVideoPath
                try {
                    recorder?.stop()
                    if (pathToScan != null) {
                        MediaScannerConnection.scanFile(context, arrayOf(pathToScan), null) { _, _ -> }
                    }
                } catch (e: Exception) {}

                session?.close()
                session = null
                cameraDevice?.close()
                cameraDevice = null
                
                Thread.sleep(500)
                openCamera()

            } catch (e: Exception) {
                Log.e("GolfPutt", "Stop Error", e)
            } finally {
                continuation.resume(Unit)
            }
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    fun release() {
        backgroundHandler?.post {
            cleanupResources()
            cameraDevice?.close()
            cameraDevice = null
            backgroundThread?.quitSafely()
            backgroundThread = null
        }
    }
}
