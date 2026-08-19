package com.example.golf_putting.core.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import android.util.Size

data class HighSpeedConfig(
    val cameraId: String,
    val size: Size,
    val fpsRange: Range<Int>,
    val sensorOrientation: Int
)

fun findHighSpeedConfiguration(cameraManager: CameraManager): HighSpeedConfig? {
    for (cameraId in cameraManager.cameraIdList) {
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
        if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) continue

        val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val highSpeedSizes = configs?.getHighSpeedVideoSizes() ?: continue

        for (size in highSpeedSizes) {
            val fpsRanges = configs.getHighSpeedVideoFpsRangesFor(size)
            for (range in fpsRanges) {
                if (range.upper >= 240) {
                    val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                    return HighSpeedConfig(cameraId, size, range, sensorOrientation)
                }
            }
        }
    }
    return null
}
