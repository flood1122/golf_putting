package com.example.golf_putting

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

        // 1. 시간 차이 계산 (microseconds -> seconds)
        val timeDeltaUs = pointB.timeUs - pointA.timeUs
        val timeDeltaSec = timeDeltaUs / 1_000_000.0

        // 예외 처리: 너무 짧은 시간(0.05초 미만)이거나 음수인 경우 오탐지로 간주
        if (timeDeltaSec < 0.05) return null

        // 2. 픽셀 변화량 계산
        val pixelDeltaX = pointB.x - pointA.x // 좌우 편차 픽셀
        val pixelDeltaY = pointB.y - pointA.y // 전진 픽셀 (아래에서 위로 가므로 음수일 수 있으므로 절댓값 또는 Y축 스케일 활용)

        // 3. 실제 물리 거리로 환산 (cm)
        // Y축 전진 거리는 사용자가 입력한 realDistanceCm을 그대로 활용 (또는 Y축 스케일 적용)
        val distanceYCm = realDistanceCm
        val distanceXCm = pixelDeltaX / pxPerCmX

        // 4. 초기 속도 계산 (v = 이동 거리 / 시간)
        // 총 이동 거리(cm)를 미터(m)로 변환 후 초당 미터(m/s) 산출
        val totalDistanceCm = sqrt(distanceXCm * distanceXCm + distanceYCm * distanceYCm)
        val speedMs = (totalDistanceCm / 100.0) / timeDeltaSec

        // 5. 예상 비거리 계산 (마찰계수 0.8 가정: Distance = 0.8 * v^2)
        val distanceM = 0.8 * (speedMs * speedMs)

        // 6. 좌우 편차 각도 계산 (θ = arctan(ΔX / ΔY))
        // 전진 거리가 Y축이므로 ΔX와 Y축 거리(realDistanceCm)를 활용
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