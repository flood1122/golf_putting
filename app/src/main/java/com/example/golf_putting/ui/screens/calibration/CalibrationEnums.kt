package com.example.golf_putting.ui.screens.calibration

import androidx.compose.ui.graphics.Color

enum class PitchStatus(val label: String, val color: Color) {
    GOOD("좋음", Color(0xFF39FF14)),
    NORMAL("보통", Color.Yellow),
    BAD("나쁨", Color.Red)
}

enum class HandleTarget {
    NONE, BALL, GATE_A, GATE_B
}

enum class DragControlMode {
    NONE, WIDTH_ONLY, PERSPECTIVE_ONLY
}