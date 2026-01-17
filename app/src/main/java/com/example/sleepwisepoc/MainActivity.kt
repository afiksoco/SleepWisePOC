package com.example.sleepwisepoc

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sleepwisepoc.ui.theme.SleepWisePOCTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var samsungHealthManager: SamsungHealthManager? = null
    private val sleepPredictor = SleepPredictor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Try to initialize Samsung Health SDK
        samsungHealthManager = SamsungHealthManager(this)
        val sdkInitialized = samsungHealthManager?.initialize() ?: false

        setContent {
            SleepWisePOCTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SleepWisePOCApp(
                        modifier = Modifier.padding(innerPadding),
                        activity = this,
                        samsungHealthManager = if (sdkInitialized) samsungHealthManager else null,
                        sleepPredictor = sleepPredictor
                    )
                }
            }
        }
    }
}

@Composable
fun SleepWisePOCApp(
    modifier: Modifier = Modifier,
    activity: Activity,
    samsungHealthManager: SamsungHealthManager?,
    sleepPredictor: SleepPredictor
) {
    val scope = rememberCoroutineScope()

    var currentInput by remember { mutableStateOf<SleepPredictor.PredictionInput?>(null) }
    var predictionResult by remember { mutableStateOf<SleepPredictor.PredictionResult?>(null) }
    var statusMessage by remember { mutableStateOf("Select a scenario to demo") }
    var realHealthData by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Text(
            text = "SleepWise POC",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Smart Wake-Up Demo",
            fontSize = 16.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Demo Scenarios Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Demo Scenarios (Mock Data)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = "Select a sleep stage to simulate:",
                    fontSize = 14.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Scenario buttons - 2x2 grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScenarioButton(
                        text = "Deep Sleep",
                        color = Color(0xFF3F51B5),
                        modifier = Modifier.weight(1f)
                    ) {
                        val input = sleepPredictor.generateMockInput("deep_sleep")
                        currentInput = input
                        predictionResult = sleepPredictor.predict(input)
                        statusMessage = "Simulating Deep Sleep..."
                    }

                    ScenarioButton(
                        text = "Light Sleep",
                        color = Color(0xFF2196F3),
                        modifier = Modifier.weight(1f)
                    ) {
                        val input = sleepPredictor.generateMockInput("light_sleep")
                        currentInput = input
                        predictionResult = sleepPredictor.predict(input)
                        statusMessage = "Simulating Light Sleep..."
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScenarioButton(
                        text = "REM Sleep",
                        color = Color(0xFF9C27B0),
                        modifier = Modifier.weight(1f)
                    ) {
                        val input = sleepPredictor.generateMockInput("rem_sleep")
                        currentInput = input
                        predictionResult = sleepPredictor.predict(input)
                        statusMessage = "Simulating REM Sleep..."
                    }

                    ScenarioButton(
                        text = "Awake",
                        color = Color(0xFFFF9800),
                        modifier = Modifier.weight(1f)
                    ) {
                        val input = sleepPredictor.generateMockInput("wake")
                        currentInput = input
                        predictionResult = sleepPredictor.predict(input)
                        statusMessage = "Simulating Awake..."
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Optimal wake scenario
                Button(
                    onClick = {
                        val input = sleepPredictor.generateMockInput("optimal_wake")
                        currentInput = input
                        predictionResult = sleepPredictor.predict(input)
                        statusMessage = "Optimal wake-up scenario!"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Optimal Wake-Up (6 AM, Light Sleep)", fontSize = 14.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Input Data Display
        currentInput?.let { input ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Input Data (Simulated)", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        DataChip("HR", "${input.heartRate.toInt()} bpm", Modifier.weight(1f))
                        DataChip("HRV", "${input.hrvRmssd.toInt()} ms", Modifier.weight(1f))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        DataChip("Movement", String.format("%.2f", input.movement), Modifier.weight(1f))
                        DataChip("Hour", "${input.hour}:00", Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Prediction Result
        predictionResult?.let { result ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (result.shouldWake) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Prediction Result", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Sleep Stage with color
                    Box(
                        modifier = Modifier
                            .background(
                                getSleepStageColor(result.sleepStage),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = result.sleepStage,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Confidence: ${(result.confidence * 100).toInt()}%",
                        fontSize = 16.sp,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Smart Alarm Decision
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (result.shouldWake) Color(0xFF4CAF50) else Color(0xFFE57373)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (result.shouldWake) "WAKE UP NOW" else "DON'T WAKE",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = result.message,
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.9f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Real Data Section (if SDK available)
        if (samsungHealthManager != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFCE4EC))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Real Data (Samsung Health)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "SDK Status: Available",
                        fontSize = 12.sp,
                        color = Color(0xFF4CAF50)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    samsungHealthManager.checkAndRequestPermissions(activity)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
                        ) {
                            Text("Permissions", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    realHealthData = samsungHealthManager.getFormattedHealthData()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE91E63))
                        ) {
                            Text("Load Data", fontSize = 12.sp)
                        }
                    }

                    if (realHealthData.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(realHealthData, fontSize = 11.sp)
                    }
                }
            }
        } else {
            Text(
                text = "Samsung Health SDK: Not available (demo mode only)",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Architecture info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFECEFF1))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Architecture", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = """
                    Watch → Samsung Health → SDK → App
                    ↓
                    Local Predictor (TFLite ready)
                    ↓
                    Smart Alarm Decision
                    """.trimIndent(),
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun ScenarioButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text, fontSize = 13.sp)
    }
}

@Composable
fun DataChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

fun getSleepStageColor(stage: String): Color {
    return when (stage) {
        "Wake" -> Color(0xFFFF9800)
        "Light" -> Color(0xFF2196F3)
        "Deep" -> Color(0xFF3F51B5)
        "REM" -> Color(0xFF9C27B0)
        else -> Color.Gray
    }
}
