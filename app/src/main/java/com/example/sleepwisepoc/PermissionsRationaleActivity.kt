package com.example.sleepwisepoc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sleepwisepoc.ui.theme.SleepWisePOCTheme

/**
 * This activity is required by Health Connect to explain WHY we need health permissions.
 * It's shown when users tap "Learn more" in the Health Connect permission dialog.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SleepWisePOCTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "SleepWise Data Access",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            "SleepWise needs access to your health data to:",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text("• Analyze your sleep patterns from your Galaxy Watch")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("• Monitor heart rate and HRV during sleep")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("• Detect optimal wake-up times in light sleep")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("• Trigger smart alarms at the best moment")
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            "Your data stays on your device and is only used to improve your sleep quality.",
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.weight(1f))

                        Button(onClick = { finish() }) {
                            Text("Got it")
                        }
                    }
                }
            }
        }
    }
}
