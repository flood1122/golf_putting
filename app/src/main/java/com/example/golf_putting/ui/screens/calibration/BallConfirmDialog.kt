package com.example.golf_putting.ui.screens.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BallConfirmDialog(
    isDetected: Boolean,
    onFeedback: (BallFeedbackType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isDetected) "공 외곽선 검출 결과" else "가이드 영역 자동 설정",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "화면의 노란색 가이드 원이 실제 공 크기와 맞게 맞춰졌나요?",
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 사용자 선택 피드백 버튼 그룹
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = { onFeedback(BallFeedbackType.TOO_SMALL) },
                        modifier = Modifier.weight(1f).padding(end = 4.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("실제보다 작음", fontSize = 11.sp, color = Color.Yellow)
                    }

                    OutlinedButton(
                        onClick = { onFeedback(BallFeedbackType.TOO_LARGE) },
                        modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("실제보다 큼", fontSize = 11.sp, color = Color.Cyan)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onFeedback(BallFeedbackType.SUCCESS) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))
            ) {
                Text("잘됨 (확정)", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", color = Color.Gray)
            }
        }
    )
}