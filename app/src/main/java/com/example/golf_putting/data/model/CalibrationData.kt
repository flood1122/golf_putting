package com.example.golf_putting.data.model

data class CalibrationData(
    val presetName: String = "기본 매트",
    val realDistanceCm: Float = 30f,
    val ballYRatio: Float = 0.8f,
    val gateAYRatio: Float = 0.5f,
    val gateBYRatio: Float = 0.3f,
    val ballHsvMin: IntArray = intArrayOf(0, 0, 180),
    val ballHsvMax: IntArray = intArrayOf(180, 30, 255),
    val ballPixelRadius: Float = 30f, // 학습된 공의 픽셀 반지름 추가
    val warpPoints: FloatArray = floatArrayOf(
        0.1f, 0.1f, 0.9f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f
    )
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CalibrationData
        if (presetName != other.presetName) return false
        if (realDistanceCm != other.realDistanceCm) return false
        if (ballYRatio != other.ballYRatio) return false
        if (gateAYRatio != other.gateAYRatio) return false
        if (gateBYRatio != other.gateBYRatio) return false
        if (ballPixelRadius != other.ballPixelRadius) return false
        if (!ballHsvMin.contentEquals(other.ballHsvMin)) return false
        if (!ballHsvMax.contentEquals(other.ballHsvMax)) return false
        if (!warpPoints.contentEquals(other.warpPoints)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = presetName.hashCode()
        result = 31 * result + realDistanceCm.hashCode()
        result = 31 * result + ballYRatio.hashCode()
        result = 31 * result + gateAYRatio.hashCode()
        result = 31 * result + gateBYRatio.hashCode()
        result = 31 * result + ballPixelRadius.hashCode()
        result = 31 * result + ballHsvMin.contentHashCode()
        result = 31 * result + ballHsvMax.contentHashCode()
        result = 31 * result + warpPoints.contentHashCode()
        return result
    }
}
