package com.example.golf_putting.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.golf_putting.core.vision.CalibrationManager
import com.example.golf_putting.ui.navigation.Screen
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(navController: NavController) {
    val activeCalib = remember { CalibrationManager.activeCalibrationData }
    var greenSpeed by remember { mutableFloatStateOf(activeCalib.greenSpeedFactor) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "환경 설정",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // 캘리브레이션 진입 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.5f)),
            onClick = { navController.navigate(Screen.CalibrationWizard.route) }
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SettingsBackupRestore,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = Color.Cyan
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        "비전 캘리브레이션",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("공 인식 및 매트 영역 재설정", color = Color.Gray, fontSize = 14.sp)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))

        // 그린 빠르기(영점 조절) 카드
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.DarkGray.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = Color.Yellow
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "연습 매트 비거리 영점 조절",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "그린 빠르기 환산 비율: ${(greenSpeed * 100).roundToInt()}%",
                    color = Color.Yellow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Slider(
                    value = greenSpeed,
                    onValueChange = { 
                        greenSpeed = it
                        CalibrationManager.saveActiveCalibration(activeCalib.copy(greenSpeedFactor = it))
                    },
                    valueRange = 0.5f..1.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Yellow,
                        activeTrackColor = Color.Yellow
                    )
                )

                Text(
                    text = "• 실제 친 거리보다 표시 거리가 짧다면 비율을 높이고, 길다면 비율을 줄이세요.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Text("버전: 1.0.0", color = Color.DarkGray, fontSize = 12.sp)
    }
}

