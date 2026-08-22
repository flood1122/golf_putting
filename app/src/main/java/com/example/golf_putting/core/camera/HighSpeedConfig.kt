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

        val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
        val highSpeedSizes = configs.getHighSpeedVideoSizes() ?: continue

        for (size in highSpeedSizes) {
            val fpsRanges = configs.getHighSpeedVideoFpsRangesFor(size)
            
            // 1순위: [240, 240] 고정 FPS 범위 (하한선과 상한선이 모두 240)
            val fixed240Range = fpsRanges.find { it.lower == 240 && it.upper == 240 }
            if (fixed240Range != null) {
                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                return HighSpeedConfig(cameraId, size, fixed240Range, sensorOrientation)
            }

            // 2순위: 상한선이 240 이상인 범위 중 가장 높은 하한선을 가진 범위 선택
            val maxLowerRange = fpsRanges
                .filter { it.upper >= 240 }
                .maxByOrNull { it.lower }

            if (maxLowerRange != null) {
                val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
                return HighSpeedConfig(cameraId, size, maxLowerRange, sensorOrientation)
            }
        }
    }
    return null
}