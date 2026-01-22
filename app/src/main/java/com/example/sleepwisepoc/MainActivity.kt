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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var samsungHealthManager: SamsungHealthManager? = null
    private var tfLitePredictor: TFLiteSleepPredictor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Samsung Health SDK
        samsungHealthManager = SamsungHealthManager(this)
        val sdkInitialized = samsungHealthManager?.initialize() ?: false

        // Initialize TFLite predictor
        tfLitePredictor = TFLiteSleepPredictor(this)
        val tfliteInitialized = tfLitePredictor?.initialize() ?: false

        Log.d("MainActivity", "Samsung Health SDK: $sdkInitialized, TFLite: $tfliteInitialized")

        setContent {
            SleepWisePOCTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SleepWisePOCApp(
                        modifier = Modifier.padding(innerPadding),
                        activity = this,
                        samsungHealthManager = if (sdkInitialized) samsungHealthManager else null,
                        tfLitePredictor = if (tfliteInitialized) tfLitePredictor else null
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tfLitePredictor?.close()
    }
}

@Composable
fun SleepWisePOCApp(
    modifier: Modifier = Modifier,
    activity: Activity,
    samsungHealthManager: SamsungHealthManager?,
    tfLitePredictor: TFLiteSleepPredictor?
) {
    val scope = rememberCoroutineScope()

    var predictionResult by remember { mutableStateOf<TFLiteSleepPredictor.SleepPrediction?>(null) }
    var statusMessage by remember { mutableStateOf("TFLite LSTM Model Ready") }
    var isLoading by remember { mutableStateOf(false) }
    var bufferStatus by remember { mutableStateOf("Buffer: 0/10 epochs") }
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
            text = "LSTM Neural Network Demo",
            fontSize = 16.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Model info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (tfLitePredictor != null) "TFLite Model: Loaded" else "TFLite Model: Failed",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (tfLitePredictor != null) Color(0xFF4CAF50) else Color.Red
                )
                Text(
                    text = "Input: 10 epochs (5 min) × 23 features",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = "Deep: 83% | REM: 55% | Light: 56% | Wake: 74%",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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
                    text = "Simulates 5 minutes of sensor data:",
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
                        scope.launch {
                            isLoading = true
                            statusMessage = "Simulating 10 Deep sleep epochs..."
                            predictionResult = runPrediction(tfLitePredictor, "deep")
                            bufferStatus = "Buffer: ${tfLitePredictor?.getBufferSize() ?: 0}/10 epochs"
                            isLoading = false
                        }
                    }

                    ScenarioButton(
                        text = "Light Sleep",
                        color = Color(0xFF2196F3),
                        modifier = Modifier.weight(1f)
                    ) {
                        scope.launch {
                            isLoading = true
                            statusMessage = "Simulating 10 Light sleep epochs..."
                            predictionResult = runPrediction(tfLitePredictor, "light")
                            bufferStatus = "Buffer: ${tfLitePredictor?.getBufferSize() ?: 0}/10 epochs"
                            isLoading = false
                        }
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
                        scope.launch {
                            isLoading = true
                            statusMessage = "Simulating 10 REM sleep epochs..."
                            predictionResult = runPrediction(tfLitePredictor, "rem")
                            bufferStatus = "Buffer: ${tfLitePredictor?.getBufferSize() ?: 0}/10 epochs"
                            isLoading = false
                        }
                    }

                    ScenarioButton(
                        text = "Awake",
                        color = Color(0xFFFF9800),
                        modifier = Modifier.weight(1f)
                    ) {
                        scope.launch {
                            isLoading = true
                            statusMessage = "Simulating 10 Wake epochs..."
                            predictionResult = runPrediction(tfLitePredictor, "wake")
                            bufferStatus = "Buffer: ${tfLitePredictor?.getBufferSize() ?: 0}/10 epochs"
                            isLoading = false
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Mixed scenario (transition)
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            statusMessage = "Simulating sleep transition..."
                            predictionResult = runTransitionPrediction(tfLitePredictor)
                            bufferStatus = "Buffer: ${tfLitePredictor?.getBufferSize() ?: 0}/10 epochs"
                            isLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Mixed (Deep → Light transition)", fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = bufferStatus,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Loading indicator
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Status message
        Text(
            text = statusMessage,
            fontSize = 14.sp,
            color = Color.Gray
        )

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
                    Text("LSTM Prediction", fontWeight = FontWeight.Bold, fontSize = 18.sp)
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

                    Spacer(modifier = Modifier.height(8.dp))

                    // Probability bars
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("Probabilities:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            result.probabilities.forEach { (stage, prob) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stage,
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(50.dp)
                                    )
                                    LinearProgressIndicator(
                                        progress = { prob },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(8.dp),
                                        color = getSleepStageColor(stage),
                                    )
                                    Text(
                                        text = "${(prob * 100).toInt()}%",
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(40.dp),
                                        textAlign = TextAlign.End
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }

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
                    TFLite LSTM Model (156 KB)
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

/**
 * Run prediction with mock data for a given scenario
 */
private suspend fun runPrediction(
    predictor: TFLiteSleepPredictor?,
    scenario: String
): TFLiteSleepPredictor.SleepPrediction? {
    if (predictor == null) return null

    return withContext(Dispatchers.Default) {
        // Clear buffer and fill with new scenario data
        predictor.clearBuffer()

        // Add 10 epochs of the scenario
        for (i in 0 until TFLiteSleepPredictor.SEQUENCE_LENGTH) {
            val epoch = predictor.createMockEpoch(scenario, i, TFLiteSleepPredictor.SEQUENCE_LENGTH)
            predictor.addEpoch(epoch)
        }

        // Run prediction
        predictor.predict()
    }
}

/**
 * Run prediction simulating a transition from Deep to Light sleep
 */
private suspend fun runTransitionPrediction(
    predictor: TFLiteSleepPredictor?
): TFLiteSleepPredictor.SleepPrediction? {
    if (predictor == null) return null

    return withContext(Dispatchers.Default) {
        predictor.clearBuffer()

        // First 5 epochs: Deep sleep
        for (i in 0 until 5) {
            val epoch = predictor.createMockEpoch("deep", i, TFLiteSleepPredictor.SEQUENCE_LENGTH)
            predictor.addEpoch(epoch)
        }

        // Last 5 epochs: Light sleep (transitioning)
        for (i in 5 until TFLiteSleepPredictor.SEQUENCE_LENGTH) {
            val epoch = predictor.createMockEpoch("light", i, TFLiteSleepPredictor.SEQUENCE_LENGTH)
            predictor.addEpoch(epoch)
        }

        predictor.predict()
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

fun getSleepStageColor(stage: String): Color {
    return when (stage) {
        "Wake" -> Color(0xFFFF9800)
        "Light" -> Color(0xFF2196F3)
        "Deep" -> Color(0xFF3F51B5)
        "REM" -> Color(0xFF9C27B0)
        else -> Color.Gray
    }
}
