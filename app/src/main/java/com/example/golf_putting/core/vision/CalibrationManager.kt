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
            .remove("preset_${presetName}_dist")
            .remove("preset_${presetName}_ballY")
            .remove("preset_${presetName}_gateA")
            .remove("preset_${presetName}_gateB")
            .remove("preset_${presetName}_hsvMin")
            .remove("preset_${presetName}_hsvMax")
            .remove("preset_${presetName}_radius")
            .remove("preset_${presetName}_warp")
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
            .putFloat("${prefix}_dist", data.realDistanceCm)
            .putFloat("${prefix}_ballY", data.ballYRatio)
            .putFloat("${prefix}_gateA", data.gateAYRatio)
            .putFloat("${prefix}_gateB", data.gateBYRatio)
            .putString("${prefix}_hsvMin", hsvMinStr)
            .putString("${prefix}_hsvMax", hsvMaxStr)
            .putFloat("${prefix}_radius", data.ballPixelRadius)
            .putString("${prefix}_warp", warpStr)
            .apply()
    }

    private fun getCalibration(prefix: String): CalibrationData? {
        if (!prefs.contains("${prefix}_name")) return null

        val name = prefs.getString("${prefix}_name", "기본 매트") ?: "기본 매트"
        val dist = prefs.getFloat("${prefix}_dist", 30f)
        val ballY = prefs.getFloat("${prefix}_ballY", 0.8f)
        val gateA = prefs.getFloat("${prefix}_gateA", 0.5f)
        val gateB = prefs.getFloat("${prefix}_gateB", 0.3f)
        val radius = prefs.getFloat("${prefix}_radius", 30f)

        val hsvMinStr = prefs.getString("${prefix}_hsvMin", "0,0,180") ?: "0,0,180"
        val hsvMaxStr = prefs.getString("${prefix}_hsvMax", "180,30,255") ?: "180,30,255"
        val warpStr = prefs.getString("${prefix}_warp", "0.1,0.1,0.9,0.1,0.1,0.9,0.9,0.9") ?: "0.1,0.1,0.9,0.1,0.1,0.9,0.9,0.9"

        val hsvMin = hsvMinStr.split(",").mapNotNull { it.toIntOrNull() }.toIntArray()
        val hsvMax = hsvMaxStr.split(",").mapNotNull { it.toIntOrNull() }.toIntArray()
        val warp = warpStr.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()

        return CalibrationData(
            presetName = name,
            realDistanceCm = dist,
            ballYRatio = ballY,
            gateAYRatio = gateA,
            gateBYRatio = gateB,
            ballHsvMin = if (hsvMin.size == 3) hsvMin else intArrayOf(0, 0, 180),
            ballHsvMax = if (hsvMax.size == 3) hsvMax else intArrayOf(180, 30, 255),
            ballPixelRadius = radius,
            warpPoints = if (warp.size == 8) warp else floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f)
        )
    }
}
