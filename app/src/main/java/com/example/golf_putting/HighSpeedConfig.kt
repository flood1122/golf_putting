package com.example.golf_putting

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Range
import android.util.Size

data class HighSpeedConfig(
    val cameraId: String, 
    val size: Size, 
    val fpsRange: Range<Int>,
    val sensorOrientation: Int
)

fun findAllHighSpeedConfigurations(manager: CameraManager): List<HighSpeedConfig> {
    val configs = mutableListOf<HighSpeedConfig>()
    try {
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue

            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val sizes = map.highSpeedVideoSizes ?: continue

            for (size in sizes) {
                val ranges = map.getHighSpeedVideoFpsRangesFor(size)
                for (range in ranges) {
                    if (range.upper == 240) {
                        configs.add(HighSpeedConfig(id, size, range, sensorOrientation))
                    }
                }
            }
        }
    } catch (e: Exception) {
        Log.e("GolfPutt", "Config Error", e)
    }
    return configs
}

fun findHighSpeedConfiguration(manager: CameraManager): HighSpeedConfig? {
    val configs = findAllHighSpeedConfigurations(manager)
    
    // 우선순위 결정:
    // 1. 고정 FPS 범위 (lower == upper == 240) -> 조도와 상관없이 240fps 강제 시도
    // 2. 가변 범위 중 하한(lower)이 높은 것 (예: [120, 240] > [30, 240])
    // 3. 해상도가 큰 것
    val selected = configs.sortedWith(
        compareByDescending<HighSpeedConfig> { it.fpsRange.lower == it.fpsRange.upper }
            .thenByDescending { it.fpsRange.lower }
            .thenByDescending { it.size.width * it.size.height }
    ).firstOrNull()

    CameraPreviewDebug.logHighSpeedCandidates(configs, selected)
    return selected
}
