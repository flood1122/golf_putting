package com.example.golf_putting.core.vision

import com.example.golf_putting.data.model.PointData
import kotlin.math.atan
import kotlin.math.sqrt

data class PuttResult(
    val speedMs: Double,      // 초기 속도 (m/s)
    val distanceM: Double,    // 예상 비거리 (m)
    val angleDeg: Double,     // 좌우 편차 각도 (도)
    val directionText: String // "Left 1.5°" 또는 "Right 2.0°" 등
)

object PuttCalculator {

    fun calculate(results: List<PointData>, realDistanceCm: Float, pxPerCmY: Double, pxPerCmX: Double): PuttResult? {
        if (results.size < 2) return null

        val pointA = results[0] // Gate A
        val pointB = results[1] // Gate B

        val timeDeltaUs = pointB.timeUs - pointA.timeUs
        val timeDeltaSec = timeDeltaUs / 1_000_000.0

        if (timeDeltaSec < 0.05) return null

        val pixelDeltaX = pointB.x - pointA.x
        val pixelDeltaY = pointB.y - pointA.y

        val distanceYCm = realDistanceCm
        val distanceXCm = pixelDeltaX / pxPerCmX

        val totalDistanceCm = sqrt(distanceXCm * distanceXCm + distanceYCm * distanceYCm)
        val speedMs = (totalDistanceCm / 100.0) / timeDeltaSec

        val distanceM = 0.8 * (speedMs * speedMs)

        val angleRad = atan(distanceXCm / distanceYCm)
        val angleDeg = Math.toDegrees(angleRad)

        val directionText = if (angleDeg < 0) {
            "Left ${"%.1f".format(kotlin.math.abs(angleDeg))}°"
        } else {
            "Right ${"%.1f".format(angleDeg)}°"
        }

        return PuttResult(
            speedMs = speedMs,
            distanceM = distanceM,
            angleDeg = angleDeg,
            directionText = directionText
        )
    }
}
