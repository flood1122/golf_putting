package com.example.golf_putting

/**
 * 추출된 공의 위치 및 시간 정보를 담는 데이터 클래스
 */
data class PointData(
    val timeUs: Long,
    val x: Double,
    val y: Double
)
