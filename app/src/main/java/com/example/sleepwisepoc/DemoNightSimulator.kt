package com.example.sleepwisepoc

import android.media.MediaPlayer
import android.media.RingtoneManager
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Random

/**
 * Simulates a compressed night of sleep for POC demo.
 *
 * Time compression: 1 real second = 1 simulated minute
 * Demo duration: ~3 minutes = simulates last 3 hours of sleep
 *
 * Sleep cycle simulation:
 * - Deep sleep (N3) at start
 * - Cycles through Deep → Light → REM → Light
 * - Ends with Light sleep for natural wake-up
 */
class DemoNightSimulator(
    private val context: Context,
    private val predictor: TFLiteSleepPredictor
) {
    companion object {
        const val TIME_SCALE = 1000L  // 1 second = 1 minute
        const val WAKE_WINDOW_MINUTES = 30  // 30 min before alarm
        const val EPOCH_DURATION_MS = 1000L  // 1 second per epoch in demo
    }

    // State
    private val _state = MutableStateFlow(DemoState())
    val state: StateFlow<DemoState> = _state

    private var simulationJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    data class DemoState(
        val isRunning: Boolean = false,
        val currentMinute: Int = 0,
        val alarmMinute: Int = 180,  // 3 hours from start
        val wakeWindowStart: Int = 150,  // 30 min before alarm
        val inWakeWindow: Boolean = false,
        val currentStage: String = "Starting...",
        val prediction: TFLiteSleepPredictor.SleepPrediction? = null,
        val alarmTriggered: Boolean = false,
        val sleepHistory: List<SleepMinute> = emptyList(),
        val statusMessage: String = "Ready to start demo"
    )

    data class SleepMinute(
        val minute: Int,
        val actualStage: String,
        val predictedStage: String?,
        val confidence: Float?,
        val isStable: Boolean? = null,
        val consecutiveCount: Int? = null,
        val emaDeepProb: Float? = null,  // EMA-smoothed Deep probability
        val rawDeepProb: Float? = null   // Raw Deep probability
    )

    /**
     * Start the demo simulation
     * @param demoMinutes Total simulated minutes (default 180 = 3 hours)
     * @param wakeWindowMinutes Minutes before alarm to start checking (default 30)
     */
    fun startDemo(demoMinutes: Int = 180, wakeWindowMinutes: Int = 30) {
        if (_state.value.isRunning) return

        val alarmMinute = demoMinutes
        val wakeWindowStart = demoMinutes - wakeWindowMinutes

        _state.value = DemoState(
            isRunning = true,
            alarmMinute = alarmMinute,
            wakeWindowStart = wakeWindowStart,
            statusMessage = "Sleep simulation started..."
        )

        predictor.clearBuffer()

        simulationJob = CoroutineScope(Dispatchers.Default).launch {
            simulateNight(alarmMinute, wakeWindowStart)
        }
    }

    fun stopDemo() {
        simulationJob?.cancel()
        stopAlarm()
        _state.value = DemoState(statusMessage = "Demo stopped")
    }

    private suspend fun simulateNight(alarmMinute: Int, wakeWindowStart: Int) {
        val random = Random()
        val history = mutableListOf<SleepMinute>()

        for (minute in 0 until alarmMinute) {
            if (!_state.value.isRunning) break

            // Determine current sleep stage based on position in night
            val actualStage = getSleepStageForMinute(minute, alarmMinute)
            val inWakeWindow = minute >= wakeWindowStart

            // Create mock epoch based on actual stage
            val epoch = predictor.createMockEpoch(
                scenario = actualStage.lowercase(),
                epochIndex = minute,
                totalEpochs = alarmMinute
            )
            predictor.addEpoch(epoch)

            // Run prediction if in wake window and buffer is ready
            var prediction: TFLiteSleepPredictor.SleepPrediction? = null
            if (inWakeWindow && predictor.canPredict()) {
                prediction = predictor.predict()

                // Check if should trigger alarm - requires STABLE prediction (3+ consecutive Light)
                // With EMA smoothing, also check that EMA probability is confident (< 0.3 for Light)
                if (prediction?.sleepStage == "Light" && prediction.isStable && prediction.emaDeepProb < 0.35f) {
                    withContext(Dispatchers.Main) {
                        triggerAlarm()
                    }

                    history.add(SleepMinute(minute, actualStage, prediction?.sleepStage, prediction?.confidence, prediction?.isStable, prediction?.consecutiveCount, prediction?.emaDeepProb, prediction?.rawDeepProb))

                    _state.value = _state.value.copy(
                        currentMinute = minute,
                        inWakeWindow = true,
                        currentStage = actualStage,
                        prediction = prediction,
                        alarmTriggered = true,
                        sleepHistory = history.toList(),
                        statusMessage = "🔔 ALARM! Light sleep confirmed (${prediction.consecutiveCount} consecutive) - optimal wake time!"
                    )
                    return
                }
            }

            history.add(SleepMinute(minute, actualStage, prediction?.sleepStage, prediction?.confidence, prediction?.isStable, prediction?.consecutiveCount, prediction?.emaDeepProb, prediction?.rawDeepProb))

            // Update state
            val timeToAlarm = alarmMinute - minute
            val statusMsg = if (inWakeWindow) {
                "Wake window active - monitoring for Light sleep... (${timeToAlarm} min to alarm)"
            } else {
                "Sleeping... (${timeToAlarm} min to alarm, wake window in ${wakeWindowStart - minute} min)"
            }

            _state.value = _state.value.copy(
                currentMinute = minute,
                inWakeWindow = inWakeWindow,
                currentStage = actualStage,
                prediction = prediction,
                sleepHistory = history.toList(),
                statusMessage = statusMsg
            )

            // Wait for next epoch (1 second = 1 minute in demo)
            delay(EPOCH_DURATION_MS)
        }

        // If we reach alarm time without finding Light sleep, trigger anyway
        if (_state.value.isRunning && !_state.value.alarmTriggered) {
            withContext(Dispatchers.Main) {
                triggerAlarm()
            }
            _state.value = _state.value.copy(
                alarmTriggered = true,
                statusMessage = "🔔 ALARM! Alarm time reached."
            )
        }
    }

    /**
     * Simulate realistic sleep stages based on position in night.
     * End of night typically has more Light/REM, less Deep.
     */
    private fun getSleepStageForMinute(minute: Int, totalMinutes: Int): String {
        val position = minute.toFloat() / totalMinutes
        val cyclePosition = (minute % 90) / 90f  // 90-min sleep cycle

        return when {
            // Last 20% of night - mostly Light with some REM
            position > 0.8 -> {
                when {
                    cyclePosition < 0.3 -> "Light"
                    cyclePosition < 0.5 -> "REM"
                    cyclePosition < 0.7 -> "Light"
                    else -> "Light"
                }
            }
            // Middle of night - mix of all stages
            position > 0.4 -> {
                when {
                    cyclePosition < 0.2 -> "Deep"
                    cyclePosition < 0.4 -> "Light"
                    cyclePosition < 0.6 -> "REM"
                    cyclePosition < 0.8 -> "Light"
                    else -> "Deep"
                }
            }
            // Early night - more Deep sleep
            else -> {
                when {
                    cyclePosition < 0.4 -> "Deep"
                    cyclePosition < 0.6 -> "Light"
                    cyclePosition < 0.8 -> "Deep"
                    else -> "Light"
                }
            }
        }
    }

    private fun triggerAlarm() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                prepare()
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            // Fallback - just update state
        }
    }

    fun stopAlarm() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null

        _state.value = _state.value.copy(
            isRunning = false,
            statusMessage = "Alarm dismissed. Good morning!"
        )
    }
}
