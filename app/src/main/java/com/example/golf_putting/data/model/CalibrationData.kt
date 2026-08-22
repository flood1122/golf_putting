package com.example.golf_putting.data.model

data class CalibrationData(
    val presetName: String = "기본 매트",
    val ballYRatio: Float = 0.8f,
    val ballHsvMin: IntArray = intArrayOf(0, 0, 180),
    val ballHsvMax: IntArray = intArrayOf(180, 30, 255),
    val ballPixelRadius: Float = 30f, // r_final (최종 보정된 반지름 px)
    val userSetRadius: Float = 30f,    // r_u (사용자 가이드 지정 반지름 px)
    val warpPoints: FloatArray = floatArrayOf(
        0.1f, 0.1f, 0.9f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f
    ),
    val greenSpeedFactor: Float = 1.0f // 그린 빠르기 보정 계수
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CalibrationData
        if (presetName != other.presetName) return false
        if (ballYRatio != other.ballYRatio) return false
        if (ballPixelRadius != other.ballPixelRadius) return false
        if (userSetRadius != other.userSetRadius) return false
        if (!ballHsvMin.contentEquals(other.ballHsvMin)) return false
        if (!ballHsvMax.contentEquals(other.ballHsvMax)) return false
        if (!warpPoints.contentEquals(other.warpPoints)) return false
        if (greenSpeedFactor != other.greenSpeedFactor) return false
        return true
    }

    override fun hashCode(): Int {
        var result = presetName.hashCode()
        result = 31 * result + ballYRatio.hashCode()
        result = 31 * result + ballPixelRadius.hashCode()
        result = 31 * result + userSetRadius.hashCode()
        result = 31 * result + ballHsvMin.contentHashCode()
        result = 31 * result + ballHsvMax.contentHashCode()
        result = 31 * result + warpPoints.contentHashCode()
        result = 31 * result + greenSpeedFactor.hashCode()
        return result
    }
}