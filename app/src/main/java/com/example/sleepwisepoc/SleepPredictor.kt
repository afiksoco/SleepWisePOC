package com.example.sleepwisepoc

import android.util.Log
import kotlin.math.exp
import kotlin.random.Random

/**
 * SleepPredictor - Local sleep stage prediction
 *
 * This is a rule-based predictor that simulates ML behavior.
 * In production, this would use TensorFlow Lite.
 *
 * Sleep stages: Wake, Light, Deep, REM
 */
class SleepPredictor {

    companion object {
        private const val TAG = "SleepPredictor"
    }

    data class PredictionInput(
        val heartRate: Double,      // BPM
        val hrvRmssd: Double,       // HRV in ms
        val movement: Double,       // 0.0 - 1.0
        val hour: Int               // 0-23
    )

    data class PredictionResult(
        val sleepStage: String,     // Wake, Light, Deep, REM
        val confidence: Double,     // 0.0 - 1.0
        val shouldWake: Boolean,    // Smart alarm recommendation
        val message: String
    )

    /**
     * Predict sleep stage based on physiological signals
     *
     * This uses simplified heuristics based on sleep science:
     * - Deep sleep: Low HR, High HRV, No movement
     * - REM: Variable HR, Low HRV, Eye movement (low body movement)
     * - Light: Moderate HR, Moderate HRV, Some movement
     * - Wake: Higher HR, Lower HRV, More movement
     */
    fun predict(input: PredictionInput): PredictionResult {
        Log.d(TAG, "Predicting for: HR=${input.heartRate}, HRV=${input.hrvRmssd}, Move=${input.movement}, Hour=${input.hour}")

        // Calculate scores for each sleep stage
        val scores = calculateStageScores(input)

        // Find the stage with highest score
        val maxEntry = scores.maxByOrNull { it.value }!!
        val sleepStage = maxEntry.key

        // Calculate confidence (softmax-like normalization)
        val totalScore = scores.values.sum()
        val confidence = if (totalScore > 0) maxEntry.value / totalScore else 0.25

        // Determine if this is a good time to wake up
        val shouldWake = shouldWakeUp(sleepStage, input.hour)

        val message = generateMessage(sleepStage, shouldWake, input.hour)

        Log.d(TAG, "Prediction: $sleepStage (${(confidence * 100).toInt()}%) - shouldWake=$shouldWake")

        return PredictionResult(
            sleepStage = sleepStage,
            confidence = confidence,
            shouldWake = shouldWake,
            message = message
        )
    }

    private fun calculateStageScores(input: PredictionInput): Map<String, Double> {
        val hr = input.heartRate
        val hrv = input.hrvRmssd
        val movement = input.movement
        val hour = input.hour

        // Time factor: sleep is more likely during night hours
        val nightFactor = if (hour in 22..23 || hour in 0..7) 1.5 else 0.5

        // Deep Sleep: Low HR (<60), High HRV (>50), Very low movement (<0.1)
        val deepScore = (
            gaussian(hr, 52.0, 8.0) * 0.4 +
            gaussian(hrv, 60.0, 15.0) * 0.3 +
            gaussian(movement, 0.05, 0.1) * 0.3
        ) * nightFactor

        // Light Sleep: Moderate HR (55-65), Moderate HRV (35-50), Low movement (0.1-0.3)
        val lightScore = (
            gaussian(hr, 60.0, 10.0) * 0.35 +
            gaussian(hrv, 42.0, 12.0) * 0.3 +
            gaussian(movement, 0.2, 0.15) * 0.35
        ) * nightFactor

        // REM: Variable HR (60-75), Lower HRV (25-40), Very low movement
        val remScore = (
            gaussian(hr, 68.0, 12.0) * 0.35 +
            gaussian(hrv, 32.0, 10.0) * 0.3 +
            gaussian(movement, 0.1, 0.1) * 0.35
        ) * nightFactor

        // Wake: Higher HR (>70), Lower HRV (<35), Higher movement (>0.4)
        val wakeScore = (
            gaussian(hr, 75.0, 15.0) * 0.3 +
            gaussian(hrv, 28.0, 10.0) * 0.25 +
            (if (movement > 0.4) 0.8 else movement * 0.5) * 0.45
        ) * (if (hour in 8..21) 1.5 else 0.8)

        return mapOf(
            "Deep" to deepScore,
            "Light" to lightScore,
            "REM" to remScore,
            "Wake" to wakeScore
        )
    }

    /**
     * Gaussian function for scoring
     */
    private fun gaussian(x: Double, mean: Double, std: Double): Double {
        val exponent = -0.5 * ((x - mean) / std) * ((x - mean) / std)
        return exp(exponent)
    }

    /**
     * Determine if user should wake up based on sleep stage and time
     */
    private fun shouldWakeUp(stage: String, hour: Int): Boolean {
        // Best to wake during Light sleep or already awake
        val goodStage = stage == "Light" || stage == "Wake"

        // Reasonable waking hours (5 AM - 10 AM)
        val reasonableHour = hour in 5..10

        return goodStage && reasonableHour
    }

    private fun generateMessage(stage: String, shouldWake: Boolean, hour: Int): String {
        return when {
            shouldWake && stage == "Light" -> "Perfect time to wake up! You're in light sleep."
            shouldWake && stage == "Wake" -> "You're already awake. Good morning!"
            stage == "Deep" -> "Deep sleep detected. Waking now may cause grogginess."
            stage == "REM" -> "REM sleep (dreaming). Better to wait for light sleep."
            stage == "Light" && hour !in 5..10 -> "Light sleep, but it's not morning yet."
            else -> "Current stage: $stage"
        }
    }

    /**
     * Generate mock data for testing/demo purposes
     */
    fun generateMockInput(scenario: String = "random"): PredictionInput {
        return when (scenario) {
            "deep_sleep" -> PredictionInput(
                heartRate = 50.0 + Random.nextDouble() * 8,
                hrvRmssd = 55.0 + Random.nextDouble() * 15,
                movement = Random.nextDouble() * 0.1,
                hour = listOf(1, 2, 3, 4).random()
            )
            "light_sleep" -> PredictionInput(
                heartRate = 58.0 + Random.nextDouble() * 10,
                hrvRmssd = 38.0 + Random.nextDouble() * 12,
                movement = 0.15 + Random.nextDouble() * 0.2,
                hour = listOf(5, 6, 7).random()
            )
            "rem_sleep" -> PredictionInput(
                heartRate = 65.0 + Random.nextDouble() * 12,
                hrvRmssd = 28.0 + Random.nextDouble() * 10,
                movement = Random.nextDouble() * 0.15,
                hour = listOf(4, 5, 6).random()
            )
            "wake" -> PredictionInput(
                heartRate = 72.0 + Random.nextDouble() * 15,
                hrvRmssd = 25.0 + Random.nextDouble() * 10,
                movement = 0.5 + Random.nextDouble() * 0.4,
                hour = listOf(7, 8, 9).random()
            )
            "optimal_wake" -> PredictionInput(
                heartRate = 60.0 + Random.nextDouble() * 8,
                hrvRmssd = 40.0 + Random.nextDouble() * 10,
                movement = 0.2 + Random.nextDouble() * 0.15,
                hour = 6  // 6 AM - good wake time
            )
            else -> PredictionInput(
                heartRate = 50.0 + Random.nextDouble() * 30,
                hrvRmssd = 25.0 + Random.nextDouble() * 40,
                movement = Random.nextDouble(),
                hour = Random.nextInt(24)
            )
        }
    }
}
