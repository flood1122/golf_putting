package com.example.golf_putting.ui.screens.calibration

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SavePresetDialog(
    initialPresetName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var presetNameInputText by remember { mutableStateOf(initialPresetName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("프리셋 저장") },
        text = {
            Column {
                Text("현재 설정한 캘리브레이션 정보를 저장할 프리셋 이름을 입력하세요.", fontSize = 13.sp)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = presetNameInputText,
                    onValueChange = { presetNameInputText = it },
                    label = { Text("프리셋 이름") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val finalName = presetNameInputText.ifBlank { "기본 매트 프리셋" }
                onConfirm(finalName)
            }) {
                Text("저장 완료")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        }
    )
}