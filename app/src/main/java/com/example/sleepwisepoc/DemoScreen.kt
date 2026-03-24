package com.example.sleepwisepoc

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Demo screen for presenting the smart alarm POC.
 * SIMPLIFIED UI - easy to understand at a glance.
 */
@Composable
fun DemoScreen(
    context: Context,
    predictor: TFLiteSleepPredictor?,
    onBack: () -> Unit
) {
    var demoSimulator by remember { mutableStateOf<DemoNightSimulator?>(null) }
    val demoState by demoSimulator?.state?.collectAsState() ?: remember { mutableStateOf(DemoNightSimulator.DemoState()) }

    // Settings
    var demoMinutes by remember { mutableStateOf(120) }
    var wakeWindowMinutes by remember { mutableStateOf(30) }

    // Initialize simulator
    LaunchedEffect(predictor) {
        if (predictor != null) {
            demoSimulator = DemoNightSimulator(context, predictor)
        }
    }

    // Auto-scroll to bottom of history
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(demoState.sleepHistory.size) {
        if (demoState.sleepHistory.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(demoState.sleepHistory.size - 1)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = {
                demoSimulator?.stopDemo()
                onBack()
            }) {
                Text("< Back")
            }
            Text(
                text = "Smart Alarm Demo",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(60.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Settings (only show when not running)
        if (!demoState.isRunning && !demoState.alarmTriggered) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Demo Settings", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "1 second = 1 minute of simulated sleep",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Night duration: ${demoMinutes} min", fontSize = 14.sp)
                    Slider(
                        value = demoMinutes.toFloat(),
                        onValueChange = { demoMinutes = it.toInt() },
                        valueRange = 60f..180f,
                        steps = 11
                    )

                    Text("Wake window: ${wakeWindowMinutes} min before alarm", fontSize = 14.sp)
                    Slider(
                        value = wakeWindowMinutes.toFloat(),
                        onValueChange = { wakeWindowMinutes = it.toInt() },
                        valueRange = 10f..60f,
                        steps = 4
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { demoSimulator?.startDemo(demoMinutes, wakeWindowMinutes) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3F51B5))
            ) {
                Text("Start Sleep Demo", fontSize = 18.sp)
            }
        }

        // Running state
        if (demoState.isRunning || demoState.alarmTriggered) {
            // Main status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        demoState.alarmTriggered -> Color(0xFFFFE0B2)
                        demoState.inWakeWindow -> Color(0xFFE3F2FD)
                        else -> Color(0xFFE8F5E9)
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Time
                    val hours = demoState.currentMinute / 60
                    val mins = demoState.currentMinute % 60
                    Text(
                        text = String.format("%02d:%02d", hours, mins),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // Progress bar
                    LinearProgressIndicator(
                        progress = { demoState.currentMinute.toFloat() / demoState.alarmMinute },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .height(8.dp),
                        color = if (demoState.inWakeWindow) Color(0xFF2196F3) else Color(0xFF4CAF50),
                    )

                    Text(
                        text = demoState.statusMessage,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = Color.Gray
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Prediction card (only in wake window)
            if (demoState.inWakeWindow && demoState.prediction != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Actual vs Predicted
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Actual
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("ACTUAL", fontSize = 12.sp, color = Color.Gray)
                                Spacer(modifier = Modifier.height(4.dp))
                                StageBadge(stage = demoState.currentStage, large = true)
                            }

                            // Arrow
                            Column(
                                modifier = Modifier.padding(top = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("→", fontSize = 24.sp, color = Color.Gray)
                            }

                            // Predicted
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("PREDICTED", fontSize = 12.sp, color = Color.Gray)
                                Spacer(modifier = Modifier.height(4.dp))
                                StageBadge(
                                    stage = demoState.prediction!!.sleepStage,
                                    large = true,
                                    showCheckmark = demoState.prediction!!.isStable
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Confidence bar
                        val confidence = if (demoState.prediction!!.sleepStage == "Light") {
                            1f - demoState.prediction!!.emaDeepProb
                        } else {
                            demoState.prediction!!.emaDeepProb
                        }

                        Text(
                            "Confidence: ${(confidence * 100).toInt()}%",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFE0E0E0))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(confidence)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(getStageColor(demoState.prediction!!.sleepStage))
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Status text
                        val statusText = when {
                            demoState.prediction!!.sleepStage == "Light" && demoState.prediction!!.isStable ->
                                "✓ Light sleep confirmed - READY TO WAKE"
                            demoState.prediction!!.sleepStage == "Light" ->
                                "Light sleep detected (${demoState.prediction!!.consecutiveCount}/3 confirmations)"
                            demoState.prediction!!.sleepStage == "Deep" && demoState.prediction!!.isStable ->
                                "Deep sleep - waiting for light sleep..."
                            else ->
                                "Analyzing sleep stage..."
                        }

                        Text(
                            statusText,
                            fontSize = 14.sp,
                            fontWeight = if (demoState.prediction!!.isStable && demoState.prediction!!.sleepStage == "Light")
                                FontWeight.Bold else FontWeight.Normal,
                            color = if (demoState.prediction!!.isStable && demoState.prediction!!.sleepStage == "Light")
                                Color(0xFF4CAF50) else Color(0xFF666666),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Alarm triggered banner
            if (demoState.alarmTriggered) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("WAKE UP!", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Light sleep detected!", fontSize = 16.sp, color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { demoSimulator?.stopAlarm() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                        ) {
                            Text("Dismiss", color = Color(0xFF4CAF50))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sleep history (simplified)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        "Sleep Timeline",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(8.dp)
                    )

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(demoState.sleepHistory) { minute ->
                            SimpleSleepRow(minute, demoState.wakeWindowStart)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stop/Reset button
            Button(
                onClick = { demoSimulator?.stopDemo() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (demoState.alarmTriggered) Color(0xFF3F51B5) else Color(0xFFE57373)
                )
            ) {
                Text(if (demoState.alarmTriggered) "Reset Demo" else "Stop Demo")
            }
        }
    }
}

@Composable
fun StageBadge(stage: String, large: Boolean = false, showCheckmark: Boolean = false) {
    Box(
        modifier = Modifier
            .background(getStageColor(stage), RoundedCornerShape(8.dp))
            .padding(
                horizontal = if (large) 16.dp else 8.dp,
                vertical = if (large) 8.dp else 4.dp
            )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stage,
                color = Color.White,
                fontSize = if (large) 18.sp else 12.sp,
                fontWeight = FontWeight.Bold
            )
            if (showCheckmark) {
                Text(" ✓", color = Color.White, fontSize = if (large) 18.sp else 12.sp)
            }
        }
    }
}

@Composable
fun SimpleSleepRow(minute: DemoNightSimulator.SleepMinute, wakeWindowStart: Int) {
    val isInWakeWindow = minute.minute >= wakeWindowStart
    val isCorrectPrediction = minute.predictedStage != null &&
        ((minute.actualStage in listOf("Deep", "REM") && minute.predictedStage == "Deep") ||
         (minute.actualStage in listOf("Light", "Wake") && minute.predictedStage == "Light"))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                when {
                    minute.isStable == true && minute.predictedStage == "Light" -> Color(0xFFC8E6C9)
                    isInWakeWindow -> Color(0xFFE3F2FD)
                    else -> Color.Transparent
                }
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Time
        Text(
            text = String.format("%02d:%02d", minute.minute / 60, minute.minute % 60),
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.width(45.dp)
        )

        // Actual stage
        StageBadge(stage = minute.actualStage)

        // Prediction
        if (minute.predictedStage != null) {
            Text("→", fontSize = 14.sp, color = Color.Gray)

            Row(verticalAlignment = Alignment.CenterVertically) {
                StageBadge(stage = minute.predictedStage)

                // Result indicator
                Text(
                    text = when {
                        minute.isStable == true && minute.predictedStage == "Light" -> " ✓✓"
                        isCorrectPrediction -> " ✓"
                        else -> " ✗"
                    },
                    fontSize = 14.sp,
                    color = when {
                        minute.isStable == true && minute.predictedStage == "Light" -> Color(0xFF4CAF50)
                        isCorrectPrediction -> Color(0xFF8BC34A)
                        else -> Color(0xFFE57373)
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Spacer(modifier = Modifier.width(80.dp))
        }
    }
}

fun getStageColor(stage: String): Color {
    return when (stage.lowercase()) {
        "deep" -> Color(0xFF3F51B5)   // Blue - bad to wake
        "light" -> Color(0xFF4CAF50)  // Green - good to wake
        "rem" -> Color(0xFF9C27B0)    // Purple
        "wake" -> Color(0xFFFF9800)   // Orange
        else -> Color.Gray
    }
}
