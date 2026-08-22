package com.example.golf_putting.core.vision

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.golf_putting.data.model.CalibrationData

object CalibrationManager {
    private const val TAG = "GolfPutt/CalibrationManager"
    private const val PREFS_NAME = "golf_putting_calibration_prefs"

    private const val KEY_ACTIVE_PRESET = "active_preset"
    private const val KEY_PRESETS_LIST = "presets_list"

    private lateinit var prefs: SharedPreferences
    var activeCalibrationData: CalibrationData = CalibrationData()
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadActiveCalibration()
    }

    private fun loadActiveCalibration() {
        activeCalibrationData = getCalibration(KEY_ACTIVE_PRESET) ?: CalibrationData()
        Log.i(TAG, "Loaded active calibration: $activeCalibrationData")
    }

    fun saveActiveCalibration(data: CalibrationData) {
        activeCalibrationData = data
        saveCalibration(KEY_ACTIVE_PRESET, data)
        Log.i(TAG, "Saved active calibration: $data")
    }

    fun getPresets(): List<String> {
        val set = prefs.getStringSet(KEY_PRESETS_LIST, emptySet()) ?: emptySet()
        return set.toList().sorted()
    }

    fun savePreset(presetName: String, data: CalibrationData) {
        val updatedData = data.copy(presetName = presetName)
        saveCalibration("preset_$presetName", updatedData)

        val currentPresets = getPresets().toMutableSet()
        currentPresets.add(presetName)
        prefs.edit().putStringSet(KEY_PRESETS_LIST, currentPresets).apply()
        Log.i(TAG, "Saved preset '$presetName': $updatedData")
    }

    fun loadPreset(presetName: String): CalibrationData? {
        val data = getCalibration("preset_$presetName")
        if (data != null) {
            saveActiveCalibration(data)
        }
        return data
    }

    fun deletePreset(presetName: String) {
        prefs.edit().remove("preset_${presetName}_name")
            .remove("preset_${presetName}_ballY")
            .remove("preset_${presetName}_hsvMin")
            .remove("preset_${presetName}_hsvMax")
            .remove("preset_${presetName}_radius")
            .remove("preset_${presetName}_userRadius")
            .remove("preset_${presetName}_warp")
            .remove("preset_${presetName}_greenSpeed")
            .apply()

        val currentPresets = getPresets().toMutableSet()
        currentPresets.remove(presetName)
        prefs.edit().putStringSet(KEY_PRESETS_LIST, currentPresets).apply()
        Log.i(TAG, "Deleted preset '$presetName'")
    }

    private fun saveCalibration(prefix: String, data: CalibrationData) {
        val hsvMinStr = data.ballHsvMin.joinToString(",")
        val hsvMaxStr = data.ballHsvMax.joinToString(",")
        val warpStr = data.warpPoints.joinToString(",")

        prefs.edit()
            .putString("${prefix}_name", data.presetName)
            .putFloat("${prefix}_ballY", data.ballYRatio)
            .putString("${prefix}_hsvMin", hsvMinStr)
            .putString("${prefix}_hsvMax", hsvMaxStr)
            .putFloat("${prefix}_radius", data.ballPixelRadius)
            .putFloat("${prefix}_userRadius", data.userSetRadius)
            .putString("${prefix}_warp", warpStr)
            .putFloat("${prefix}_greenSpeed", data.greenSpeedFactor)
            .apply()
    }

    private fun getCalibration(prefix: String): CalibrationData? {
        if (!prefs.contains("${prefix}_name")) return null

        val name = prefs.getString("${prefix}_name", "기본 매트") ?: "기본 매트"
        val ballY = prefs.getFloat("${prefix}_ballY", 0.8f)
        val radius = prefs.getFloat("${prefix}_radius", 30f)
        val userRadius = prefs.getFloat("${prefix}_userRadius", radius)
        val speedFactor = prefs.getFloat("${prefix}_greenSpeed", 1.0f)

        val hsvMinStr = prefs.getString("${prefix}_hsvMin", "0,0,180") ?: "0,0,180"
        val hsvMaxStr = prefs.getString("${prefix}_hsvMax", "180,30,255") ?: "180,30,255"
        val warpStr = prefs.getString("${prefix}_warp", "0.1,0.1,0.9,0.1,0.1,0.9,0.9,0.9") ?: "0.1,0.1,0.9,0.1,0.1,0.9,0.9,0.9"

        val hsvMin = hsvMinStr.split(",").mapNotNull { it.toIntOrNull() }.toIntArray()
        val hsvMax = hsvMaxStr.split(",").mapNotNull { it.toIntOrNull() }.toIntArray()
        val warp = warpStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()

        return CalibrationData(
            presetName = name,
            ballYRatio = ballY,
            ballHsvMin = if (hsvMin.size == 3) hsvMin else intArrayOf(0, 0, 180),
            ballHsvMax = if (hsvMax.size == 3) hsvMax else intArrayOf(180, 30, 255),
            ballPixelRadius = radius,
            userSetRadius = userRadius,
            warpPoints = if (warp.size == 8) warp else floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f),
            greenSpeedFactor = speedFactor
        )
    }
}