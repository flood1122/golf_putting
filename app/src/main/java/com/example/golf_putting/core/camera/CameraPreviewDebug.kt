package com.example.golf_putting.core.camera

import android.graphics.Matrix
import android.util.Log

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
}
