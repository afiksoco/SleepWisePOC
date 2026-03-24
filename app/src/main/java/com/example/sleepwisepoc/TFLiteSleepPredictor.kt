package com.example.sleepwisepoc

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite based Sleep Stage Predictor
 *
 * Uses a model trained on real sleep data (DREAMT dataset) to predict
 * sleep stages from physiological sensor data.
 *
 * Binary Classification: Deep (N3+REM) vs Not Deep (Wake+N1+N2)
 *
 * Input: Sequence of 5 epochs (5 minutes at 1-min epochs), each with 46 features
 * Output: Probabilities for [Deep, Not Deep]
 */
class TFLiteSleepPredictor(private val context: Context) {

    companion object {
        private const val TAG = "TFLiteSleepPredictor"
        private const val MODEL_FILE = "sleep_stage_model.tflite"
        private const val METADATA_FILE = "tflite_metadata.json"

        const val SEQUENCE_LENGTH = 5  // 5 epochs = 5 minutes (1-min epochs)
        const val NUM_FEATURES = 30    // 12 base (9 HR + 3 Temp) + 16 temporal + 2 extra
        const val NUM_CLASSES = 2      // Binary: Deep vs Light
        const val SMOOTHING_WINDOW = 3 // Require 3 consecutive same predictions

        // EMA Smoothing parameters
        const val EMA_ALPHA = 0.3f     // 30% weight to new prediction, 70% to history

        // Hysteresis thresholds - prevents rapid oscillation between states
        const val THRESHOLD_TO_DEEP = 0.55f   // Need 55% smoothed prob to switch TO Deep
        const val THRESHOLD_TO_LIGHT = 0.35f  // Need <35% smoothed prob to switch TO Light
    }

    private var interpreter: Interpreter? = null
    private var scalerMean: FloatArray? = null
    private var scalerScale: FloatArray? = null
    private var featureNames: List<String> = emptyList()
    private var classNames: List<String> = listOf("Deep", "Light")

    // Buffer to store recent epochs (base features only, temporal computed on-the-fly)
    private val epochBuffer = mutableListOf<FloatArray>()

    // Buffer to store recent predictions for smoothing
    private val predictionHistory = mutableListOf<String>()

    // EMA state for smoothed probability
    private var emaDeepProb = 0.5f  // Start neutral
    private var currentState = "Light"  // Current state for hysteresis

    data class SleepPrediction(
        val sleepStage: String,
        val confidence: Float,
        val probabilities: Map<String, Float>,
        val shouldWake: Boolean,
        val message: String,
        val isStable: Boolean = false,  // True if 3+ consecutive same predictions
        val consecutiveCount: Int = 1,  // How many consecutive same predictions
        val emaDeepProb: Float = 0.5f,  // EMA-smoothed Deep probability
        val rawDeepProb: Float = 0.5f   // Raw (unsmoothed) Deep probability
    )

    /**
     * Base epoch features (12 features) - before temporal features
     * Focused on what Galaxy Watch 5 can provide: HR and Temperature
     * (HRV removed - not available in DREAMT training data)
     */
    data class EpochFeatures(
        // HR features (9)
        val hrMean: Float,
        val hrStd: Float,
        val hrMin: Float,
        val hrMax: Float,
        val hrRange: Float,
        val hrCv: Float,
        val hrMedian: Float,
        val hrIqr: Float,
        val hrSkew: Float,
        // Temperature features (3)
        val tempMean: Float,
        val tempStd: Float,
        val tempTrend: Float
    ) {
        fun toFloatArray(): FloatArray {
            return floatArrayOf(
                hrMean, hrStd, hrMin, hrMax, hrRange, hrCv, hrMedian, hrIqr, hrSkew,
                tempMean, tempStd, tempTrend
            )
        }
    }

    /**
     * Initialize the TFLite interpreter and load metadata
     */
    fun initialize(): Boolean {
        return try {
            // Load model
            val modelBuffer = loadModelFile()
            interpreter = Interpreter(modelBuffer)
            Log.d(TAG, "TFLite model loaded successfully")

            // Load metadata
            loadMetadata()
            Log.d(TAG, "Metadata loaded: ${featureNames.size} features, ${classNames.size} classes")

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TFLite: ${e.message}")
            false
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    private fun loadMetadata() {
        val jsonString = context.assets.open(METADATA_FILE).bufferedReader().use { it.readText() }
        val json = JSONObject(jsonString)

        // Load feature names
        val featuresArray = json.getJSONArray("feature_names")
        featureNames = (0 until featuresArray.length()).map { featuresArray.getString(it) }

        // Load class names
        val classesArray = json.getJSONArray("class_names")
        classNames = (0 until classesArray.length()).map { classesArray.getString(it) }

        // Load scaler parameters
        val meanArray = json.getJSONArray("scaler_mean")
        scalerMean = FloatArray(meanArray.length()) {
            val value = meanArray.get(it)
            if (value.toString() == "NaN") 0f else meanArray.getDouble(it).toFloat()
        }

        val scaleArray = json.getJSONArray("scaler_scale")
        scalerScale = FloatArray(scaleArray.length()) {
            val value = scaleArray.get(it)
            if (value.toString() == "NaN") 1f else scaleArray.getDouble(it).toFloat()
        }
    }

    /**
     * Add a new epoch of features to the buffer
     */
    fun addEpoch(features: EpochFeatures) {
        epochBuffer.add(features.toFloatArray())

        // Keep only the last SEQUENCE_LENGTH epochs
        while (epochBuffer.size > SEQUENCE_LENGTH) {
            epochBuffer.removeAt(0)
        }
    }

    /**
     * Check if we have enough epochs to make a prediction
     */
    fun canPredict(): Boolean = epochBuffer.size >= SEQUENCE_LENGTH

    /**
     * Get current buffer size
     */
    fun getBufferSize(): Int = epochBuffer.size

    /**
     * Clear the epoch buffer and prediction history
     */
    fun clearBuffer() {
        epochBuffer.clear()
        predictionHistory.clear()
        emaDeepProb = 0.5f  // Reset EMA to neutral
        currentState = "Light"  // Reset hysteresis state
    }

    /**
     * Compute temporal features from the epoch buffer
     * Returns full feature vector with base + temporal features
     * Total: 12 base + 16 temporal (8 per key feature) + 2 extra = 30 features
     */
    private fun computeTemporalFeatures(): FloatArray {
        val lookback = 4

        // Feature indices for temporal computation (in 12-feature base vector)
        val hrMeanIdx = 0
        val tempMeanIdx = 9  // After 9 HR features

        val result = mutableListOf<Float>()

        // Add base features from latest epoch (12 features)
        val latestEpoch = epochBuffer.last()
        result.addAll(latestEpoch.toList())

        // Compute temporal features for key features (HR and Temp only)
        val keyFeatureIndices = listOf(hrMeanIdx, tempMeanIdx)

        for (featureIdx in keyFeatureIndices) {
            val values = epochBuffer.map { it[featureIdx] }
            val current = values.last()

            // Lag features (lag1 to lag4)
            for (lag in 1..lookback) {
                val lagIdx = values.size - 1 - lag
                result.add(if (lagIdx >= 0) values[lagIdx] else current)
            }

            // Rolling mean
            val rollingMean = values.takeLast(lookback).average().toFloat()
            result.add(rollingMean)

            // Rolling std
            val rollingValues = values.takeLast(lookback)
            val mean = rollingValues.average()
            val rollingStd = kotlin.math.sqrt(
                rollingValues.map { (it - mean) * (it - mean) }.average()
            ).toFloat()
            result.add(rollingStd)

            // Trend (diff over lookback)
            val trendIdx = values.size - 1 - lookback
            val trend = if (trendIdx >= 0) current - values[trendIdx] else 0f
            result.add(trend)

            // Rate of change
            val rocBase = if (trendIdx >= 0) values[trendIdx] else current
            val roc = if (rocBase != 0f) (current - rocBase) / rocBase else 0f
            result.add(roc)
        }

        // HR stability (rolling std of hr_mean)
        val hrMeanValues = epochBuffer.map { it[hrMeanIdx] }
        val hrMean = hrMeanValues.takeLast(lookback).average()
        val hrStability = kotlin.math.sqrt(
            hrMeanValues.takeLast(lookback).map { (it - hrMean) * (it - hrMean) }.average()
        ).toFloat()
        result.add(hrStability)

        // Sleep cycle position (simplified - based on buffer position)
        val sleepCyclePosition = (epochBuffer.size % 90) / 90f
        result.add(sleepCyclePosition)

        return result.toFloatArray()
    }

    /**
     * Normalize features using the scaler from training
     */
    private fun normalizeFeatures(features: FloatArray): FloatArray {
        val mean = scalerMean ?: return features
        val scale = scalerScale ?: return features

        return FloatArray(features.size) { i ->
            if (i < mean.size && i < scale.size && scale[i] != 0f && !features[i].isNaN()) {
                (features[i] - mean[i]) / scale[i]
            } else {
                0f
            }
        }
    }

    /**
     * Run prediction on the current epoch buffer
     */
    fun predict(): SleepPrediction? {
        if (!canPredict()) {
            Log.w(TAG, "Not enough epochs for prediction: ${epochBuffer.size}/$SEQUENCE_LENGTH")
            return null
        }

        val interp = interpreter ?: run {
            Log.e(TAG, "Interpreter not initialized")
            return null
        }

        // Compute full feature vector with temporal features
        val features = computeTemporalFeatures()
        val normalized = normalizeFeatures(features)

        // Prepare input: [1, NUM_FEATURES] for simple model or [1, SEQ, FEATURES] for LSTM
        // Using simple model format for now
        val input = arrayOf(normalized)

        // Prepare output: [1, 2] for binary
        val output = Array(1) { FloatArray(NUM_CLASSES) }

        // Run inference
        try {
            interp.run(input, output)
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            return null
        }

        // Process results
        val probabilities = output[0]
        val deepIdx = classNames.indexOf("Deep")
        val rawDeepProb = if (deepIdx >= 0) probabilities[deepIdx] else 0f

        // Log raw output for debugging
        Log.d(TAG, "Model output: Deep=${(rawDeepProb*100).toInt()}%, Light=${((1-rawDeepProb)*100).toInt()}%")

        // === EMA SMOOTHING ===
        // Update EMA: new_average = alpha * new_value + (1 - alpha) * old_average
        emaDeepProb = EMA_ALPHA * rawDeepProb + (1f - EMA_ALPHA) * emaDeepProb

        // === HYSTERESIS THRESHOLDS ===
        // Use different thresholds for entering vs leaving a state
        // This prevents rapid oscillation between Deep and Light
        val previousState = currentState
        val predictedClass = when {
            currentState == "Light" && emaDeepProb > THRESHOLD_TO_DEEP -> {
                currentState = "Deep"
                "Deep"
            }
            currentState == "Deep" && emaDeepProb < THRESHOLD_TO_LIGHT -> {
                currentState = "Light"
                "Light"
            }
            else -> currentState  // Stay in current state (hysteresis zone)
        }

        Log.d(TAG, "EMA=${(emaDeepProb*100).toInt()}% | State: $previousState -> $predictedClass | Thresholds: <${(THRESHOLD_TO_LIGHT*100).toInt()}%=Light, >${(THRESHOLD_TO_DEEP*100).toInt()}%=Deep")

        val confidence = if (predictedClass == "Deep") emaDeepProb else (1f - emaDeepProb)

        // Track prediction history for consecutive count (still useful for display)
        predictionHistory.add(predictedClass)
        if (predictionHistory.size > SMOOTHING_WINDOW) {
            predictionHistory.removeAt(0)
        }

        // Check if prediction is stable (3+ consecutive same predictions)
        val consecutiveCount = countConsecutiveSame()
        val isStable = consecutiveCount >= SMOOTHING_WINDOW

        // Create probability map (show both raw and smoothed)
        val probMap = mapOf(
            "Deep" to rawDeepProb,
            "Light" to (1f - rawDeepProb),
            "Deep (EMA)" to emaDeepProb,
            "Light (EMA)" to (1f - emaDeepProb)
        )

        // Determine if should wake (smart alarm logic)
        // Only wake if: Light sleep + stable + EMA confident
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val isWakeWindow = currentHour in 5..10
        val shouldWake = predictedClass == "Light" && isStable && emaDeepProb < 0.3f && isWakeWindow

        // Generate message with EMA info
        val emaPercent = (emaDeepProb * 100).toInt()
        val rawPercent = (rawDeepProb * 100).toInt()
        val stabilityInfo = if (isStable) "stable" else "$consecutiveCount/$SMOOTHING_WINDOW"
        val message = when (predictedClass) {
            "Deep" -> "Deep sleep (EMA: $emaPercent%, raw: $rawPercent%) [$stabilityInfo]"
            "Light" -> if (shouldWake) "Light sleep - optimal wake! (EMA: $emaPercent%) [$stabilityInfo]"
                      else "Light sleep (EMA: $emaPercent%, raw: $rawPercent%) [$stabilityInfo]"
            else -> "Unknown sleep stage."
        }

        return SleepPrediction(
            sleepStage = predictedClass,
            confidence = confidence,
            probabilities = probMap,
            shouldWake = shouldWake,
            message = message,
            isStable = isStable,
            consecutiveCount = consecutiveCount,
            emaDeepProb = emaDeepProb,
            rawDeepProb = rawDeepProb
        )
    }

    /**
     * Count consecutive same predictions from the end of history
     */
    private fun countConsecutiveSame(): Int {
        if (predictionHistory.isEmpty()) return 0
        val latest = predictionHistory.last()
        var count = 0
        for (i in predictionHistory.indices.reversed()) {
            if (predictionHistory[i] == latest) {
                count++
            } else {
                break
            }
        }
        return count
    }

    /**
     * Create mock epoch features based on ACTUAL DREAMT training data distributions:
     * - Deep: hr_mean=67.1±4, temp=35.0
     * - Light: hr_mean=70.9±10.8, temp=34.1
     *
     * FIXED: Made ranges NON-OVERLAPPING for clearer distinction
     * Only HR and Temperature features (no HRV - matches Galaxy Watch 5 available data)
     */
    fun createMockEpoch(scenario: String, epochIndex: Int, totalEpochs: Int): EpochFeatures {
        val random = java.util.Random()

        return when (scenario.lowercase()) {
            "deep" -> EpochFeatures(
                // HR features - Deep: lower HR (~62-66), very stable
                hrMean = 60f + random.nextFloat() * 6,      // 60-66 (NO OVERLAP with Light)
                hrStd = 0.5f + random.nextFloat() * 1.0f,   // very low variability
                hrMin = 58f + random.nextFloat() * 4,
                hrMax = 64f + random.nextFloat() * 4,
                hrRange = 3f + random.nextFloat() * 3,       // small range
                hrCv = 1.0f + random.nextFloat() * 0.5f,     // low CV
                hrMedian = 60f + random.nextFloat() * 6,
                hrIqr = 1f + random.nextFloat() * 2,
                hrSkew = -0.1f + random.nextFloat() * 0.2f,
                // Temperature - higher in deep sleep (~35.0-36.0)
                tempMean = 35.0f + random.nextFloat() * 1f,  // 35.0-36.0
                tempStd = 0.01f + random.nextFloat() * 0.02f,
                tempTrend = -0.001f + random.nextFloat() * 0.001f  // slight decrease
            )

            "light" -> EpochFeatures(
                // HR features - Light: higher HR (~72-80), more variable
                hrMean = 72f + random.nextFloat() * 8,      // 72-80 (NO OVERLAP with Deep)
                hrStd = 3.0f + random.nextFloat() * 3.0f,   // high variability
                hrMin = 68f + random.nextFloat() * 6,
                hrMax = 78f + random.nextFloat() * 10,
                hrRange = 10f + random.nextFloat() * 8,      // large range
                hrCv = 4.0f + random.nextFloat() * 2.0f,     // high CV
                hrMedian = 72f + random.nextFloat() * 8,
                hrIqr = 5f + random.nextFloat() * 5,
                hrSkew = 0.1f + random.nextFloat() * 0.3f,
                // Temperature - lower in light sleep (~33.0-34.0)
                tempMean = 33.0f + random.nextFloat() * 1f,  // 33.0-34.0
                tempStd = 0.04f + random.nextFloat() * 0.04f,
                tempTrend = 0.001f + random.nextFloat() * 0.002f  // slight increase
            )

            "rem" -> EpochFeatures(
                // REM counts as Deep - similar to deep but slightly more HR variability
                hrMean = 62f + random.nextFloat() * 6,      // 62-68 (similar to deep)
                hrStd = 1.0f + random.nextFloat() * 1.5f,
                hrMin = 60f + random.nextFloat() * 4,
                hrMax = 66f + random.nextFloat() * 6,
                hrRange = 4f + random.nextFloat() * 4,
                hrCv = 1.5f + random.nextFloat() * 1f,
                hrMedian = 62f + random.nextFloat() * 6,
                hrIqr = 2f + random.nextFloat() * 3,
                hrSkew = 0f + random.nextFloat() * 0.2f,
                // Temperature - similar to deep
                tempMean = 34.8f + random.nextFloat() * 1f,
                tempStd = 0.02f + random.nextFloat() * 0.02f,
                tempTrend = -0.0005f + random.nextFloat() * 0.001f
            )

            "wake" -> EpochFeatures(
                // Wake counts as Light - highest HR, most variable
                hrMean = 75f + random.nextFloat() * 10,     // 75-85 (clearly Light)
                hrStd = 4.0f + random.nextFloat() * 4.0f,
                hrMin = 70f + random.nextFloat() * 8,
                hrMax = 82f + random.nextFloat() * 12,
                hrRange = 12f + random.nextFloat() * 10,
                hrCv = 5.0f + random.nextFloat() * 2f,
                hrMedian = 75f + random.nextFloat() * 10,
                hrIqr = 6f + random.nextFloat() * 6,
                hrSkew = 0.2f + random.nextFloat() * 0.4f,
                // Temperature - lowest
                tempMean = 32.5f + random.nextFloat() * 1f,   // 32.5-33.5
                tempStd = 0.05f + random.nextFloat() * 0.05f,
                tempTrend = 0.002f + random.nextFloat() * 0.002f
            )

            else -> createMockEpoch("light", epochIndex, totalEpochs)
        }
    }

    /**
     * Release resources
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
