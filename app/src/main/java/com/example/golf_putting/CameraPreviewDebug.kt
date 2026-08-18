package com.example.golf_putting

import android.graphics.Matrix
import android.util.Log

/** 
 * Logcat 필터: `GolfPutt/Preview` 
 */
object CameraPreviewDebug {
    private const val TAG = "GolfPutt/Preview"

    fun logHighSpeedCandidates(configs: List<HighSpeedConfig>, selected: HighSpeedConfig?) {
        Log.i(TAG, "=== [CANDIDATES] 240fps candidates (${configs.size}) ===")
        configs.forEachIndexed { index, cfg ->
            val aspect = cfg.size.width.toFloat() / cfg.size.height
            Log.i(TAG, "  [$index] camera=${cfg.cameraId}, size=${cfg.size.width}x${cfg.size.height}, aspect=${"%.4f".format(aspect)}, sensor=${cfg.sensorOrientation}°")
        }
        if (selected != null) {
            Log.i(TAG, "  => Selected: ${selected.size.width}x${selected.size.height}")
        }
    }

    fun logSurfaceReady(viewWidth: Int, viewHeight: Int, bufferWidth: Int, bufferHeight: Int, config: HighSpeedConfig) {
        Log.i(TAG, "=== [SURFACE] Ready ===")
        Log.i(TAG, "  View: ${viewWidth}x${viewHeight}, Buffer: ${bufferWidth}x${bufferHeight}, Sensor: ${config.sensorOrientation}°")
    }

    fun logTransform(details: PreviewTransformDetails) {
        Log.i(TAG, "=== [TRANSFORM] Details ===")
        Log.i(TAG, "  View Aspect: ${details.viewAspectFormatted}")
        Log.i(TAG, "  Visual Content Size: ${details.visualWidth}x${details.visualHeight}")
        Log.i(TAG, "  Visual Aspect: ${details.visualAspectFormatted}")
        Log.i(TAG, "  Target Scale (Crop): ${"%.4f".format(details.targetScale)}")
        Log.i(TAG, "  Matrix Scale: X=${details.matrixScaleXFormatted}, Y=${details.matrixScaleYFormatted}")
        Log.i(TAG, "  Matrix: ${details.matrixValuesFormatted}")
    }

    fun logSessionConfigured(previewWidth: Int, previewHeight: Int, fps: Int, orientationHint: Int) {
        Log.i(TAG, "=== [SESSION] Configured ===")
        Log.i(TAG, "  Size: ${previewWidth}x${previewHeight}, FPS: $fps, Orientation: ${orientationHint}°")
    }
}

data class PreviewTransformDetails(
    val viewWidth: Int,
    val viewHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val visualWidth: Int,
    val visualHeight: Int,
    val sensorOrientation: Int,
    val displayRotationDegrees: Int,
    val isSensorRotated: Boolean,
    val defaultScaleX: Float,
    val defaultScaleY: Float,
    val targetScale: Float,
    val matrixScaleX: Float,
    val matrixScaleY: Float,
    val matrixValues: FloatArray
) {
    val viewAspectFormatted get() = "%.4f".format(viewWidth.toFloat() / viewHeight)
    val visualAspectFormatted get() = "%.4f".format(visualWidth.toFloat() / visualHeight)
    val matrixScaleXFormatted get() = "%.4f".format(matrixScaleX)
    val matrixScaleYFormatted get() = "%.4f".format(matrixScaleY)
    val matrixValuesFormatted get() = matrixValues.joinToString(prefix = "[", postfix = "]") { "%.4f".format(it) }
}

fun Matrix.toLogValues(): FloatArray = FloatArray(9).also { getValues(it) }
