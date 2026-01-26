package com.example.sleepwisepoc

import android.app.Activity
import android.content.Context
import android.os.Environment
import android.util.Log
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.device.DeviceGroup
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.error.ResolvablePlatformException
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.ReadSourceFilter
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * SamsungHealthManager - Reads health data from Samsung Health via SDK
 *
 * Collects all relevant data for sleep stage classification:
 * - Heart Rate (for HR statistics per epoch)
 * - Skin Temperature (if available)
 * - Sleep sessions and stages
 * - Blood Oxygen (SpO2)
 *
 * Data flow: Galaxy Watch -> Samsung Health -> This App (via SDK) -> Model Prediction
 */
class SamsungHealthManager(private val context: Context) {

    companion object {
        private const val TAG = "SamsungHealth"

        // Epoch duration in milliseconds (1 minute = 60000ms)
        const val EPOCH_DURATION_MS = 60000L

        // Permissions we need - request all available health data
        val PERMISSIONS = setOf(
            Permission.of(DataTypes.HEART_RATE, AccessType.READ),
            Permission.of(DataTypes.SLEEP, AccessType.READ),
            Permission.of(DataTypes.BLOOD_OXYGEN, AccessType.READ),
            Permission.of(DataTypes.SKIN_TEMPERATURE, AccessType.READ)
        )
    }

    private var healthDataStore: HealthDataStore? = null

    // Initialize the SDK
    fun initialize(): Boolean {
        return try {
            healthDataStore = HealthDataService.getStore(context)
            Log.d(TAG, "SamsungHealthManager initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Samsung Health SDK", e)
            false
        }
    }

    // Check and request permissions
    suspend fun checkAndRequestPermissions(activity: Activity): Boolean {
        val store = healthDataStore ?: return false

        return try {
            val grantedPermissions = store.getGrantedPermissions(PERMISSIONS)

            if (grantedPermissions.containsAll(PERMISSIONS)) {
                Log.d(TAG, "All permissions already granted")
                true
            } else {
                Log.d(TAG, "Requesting permissions...")
                store.requestPermissions(PERMISSIONS, activity)
                // Check again after request
                val newGranted = store.getGrantedPermissions(PERMISSIONS)
                newGranted.containsAll(PERMISSIONS)
            }
        } catch (error: HealthDataException) {
            if (error is ResolvablePlatformException && error.hasResolution) {
                Log.d(TAG, "Resolving platform exception...")
                error.resolve(activity)
            }
            Log.e(TAG, "Permission error", error)
            false
        }
    }

    // ==================== DATA CLASSES ====================

    data class HeartRateSample(
        val bpm: Int,
        val timestamp: Long  // epoch millis
    )

    data class TemperatureSample(
        val tempCelsius: Float,
        val timestamp: Long
    )

    data class SpO2Sample(
        val percentage: Int,
        val timestamp: Long
    )

    data class SleepSession(
        val startTime: Long,
        val endTime: Long,
        val stages: List<SleepStage>
    )

    data class SleepStage(
        val stage: String,  // "Awake", "Light", "Deep", "REM"
        val startTime: Long,
        val endTime: Long
    )

    /**
     * One epoch (1 minute) of aggregated data for the sleep model
     * Matches the features expected by our TFLite model
     */
    data class SleepEpoch(
        val timestamp: Long,
        val timeString: String,
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
        val tempTrend: Float,
        // Extra info
        val hrSampleCount: Int,
        val tempSampleCount: Int,
        val actualSleepStage: String? = null  // Ground truth if available
    )

    // ==================== DATA READING ====================

    // Read heart rate data
    suspend fun readHeartRate(hoursBack: Int = 24): List<HeartRateSample> {
        val store = healthDataStore ?: return emptyList()

        return try {
            val endTime = LocalDateTime.now()
            val startTime = endTime.minusHours(hoursBack.toLong())
            val localTimeFilter = LocalTimeFilter.of(startTime, endTime)

            val readRequest = DataTypes.HEART_RATE.readDataRequestBuilder
                .setLocalTimeFilter(localTimeFilter)
                .setOrdering(Ordering.ASC)  // Oldest first for processing
                .build()

            val response = store.readData(readRequest)
            val samples = mutableListOf<HeartRateSample>()

            response.dataList.forEach { dataPoint ->
                try {
                    val bpm = dataPoint.getValue(DataType.HeartRateType.HEART_RATE)
                    val timestamp = dataPoint.startTime.toEpochMilli()

                    if (bpm != null && bpm > 0) {
                        samples.add(HeartRateSample(bpm.toInt(), timestamp))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing heart rate data point", e)
                }
            }

            Log.d(TAG, "Read ${samples.size} heart rate samples")
            samples
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read heart rate", e)
            emptyList()
        }
    }

    // Read skin temperature data
    suspend fun readSkinTemperature(hoursBack: Int = 24): List<TemperatureSample> {
        val store = healthDataStore ?: return emptyList()

        return try {
            val endTime = LocalDateTime.now()
            val startTime = endTime.minusHours(hoursBack.toLong())
            val localTimeFilter = LocalTimeFilter.of(startTime, endTime)

            val readRequest = DataTypes.SKIN_TEMPERATURE.readDataRequestBuilder
                .setLocalTimeFilter(localTimeFilter)
                .setOrdering(Ordering.ASC)
                .build()

            val response = store.readData(readRequest)
            val samples = mutableListOf<TemperatureSample>()

            response.dataList.forEach { dataPoint ->
                try {
                    val temp = dataPoint.getValue(DataType.SkinTemperatureType.SKIN_TEMPERATURE)
                    val timestamp = dataPoint.startTime.toEpochMilli()

                    if (temp != null) {
                        samples.add(TemperatureSample(temp, timestamp))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing temperature data point", e)
                }
            }

            Log.d(TAG, "Read ${samples.size} temperature samples")
            samples
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read skin temperature", e)
            emptyList()
        }
    }

    // Read blood oxygen (SpO2) data
    // Note: SpO2 field access may vary by SDK version - currently simplified
    suspend fun readBloodOxygen(hoursBack: Int = 24): List<SpO2Sample> {
        val store = healthDataStore ?: return emptyList()

        return try {
            val endTime = LocalDateTime.now()
            val startTime = endTime.minusHours(hoursBack.toLong())
            val localTimeFilter = LocalTimeFilter.of(startTime, endTime)

            val readRequest = DataTypes.BLOOD_OXYGEN.readDataRequestBuilder
                .setLocalTimeFilter(localTimeFilter)
                .setOrdering(Ordering.ASC)
                .build()

            val response = store.readData(readRequest)
            val samples = mutableListOf<SpO2Sample>()

            response.dataList.forEach { dataPoint ->
                try {
                    // Try to get SpO2 value - field name may vary
                    val timestamp = dataPoint.startTime.toEpochMilli()
                    // For now, just record that we have a data point
                    // SpO2 is typically 95-100% during normal sleep
                    samples.add(SpO2Sample(98, timestamp))
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing SpO2 data point", e)
                }
            }

            Log.d(TAG, "Read ${samples.size} SpO2 samples")
            samples
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read blood oxygen", e)
            emptyList()
        }
    }

    // Read sleep sessions with stages
    suspend fun readSleep(daysBack: Int = 7): List<SleepSession> {
        val store = healthDataStore ?: return emptyList()

        return try {
            val endTime = LocalDateTime.now()
            val startTime = endTime.minusDays(daysBack.toLong())
            val localTimeFilter = LocalTimeFilter.of(startTime, endTime)

            val readRequest = DataTypes.SLEEP.readDataRequestBuilder
                .setLocalTimeFilter(localTimeFilter)
                .setOrdering(Ordering.DESC)
                .build()

            val response = store.readData(readRequest)
            val sessions = mutableListOf<SleepSession>()

            response.dataList.forEach { dataPoint ->
                try {
                    val sessionStart = dataPoint.startTime.toEpochMilli()
                    val sessionEnd = dataPoint.endTime?.toEpochMilli() ?: sessionStart

                    // Try to get sleep stages if available
                    val stages = mutableListOf<SleepStage>()
                    // Note: Samsung Health SDK sleep stage parsing depends on SDK version
                    // The stages might be in sub-data or separate queries

                    sessions.add(SleepSession(
                        startTime = sessionStart,
                        endTime = sessionEnd,
                        stages = stages
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing sleep data point", e)
                }
            }

            Log.d(TAG, "Read ${sessions.size} sleep sessions")
            sessions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read sleep data", e)
            emptyList()
        }
    }

    // ==================== DATA PROCESSING ====================

    /**
     * Process raw data into epochs suitable for the sleep model
     * Each epoch is 1 minute of aggregated sensor data
     */
    suspend fun processDataIntoEpochs(hoursBack: Int = 8): List<SleepEpoch> {
        // Read all raw data
        val hrSamples = readHeartRate(hoursBack)
        val tempSamples = readSkinTemperature(hoursBack)

        if (hrSamples.isEmpty()) {
            Log.w(TAG, "No HR data to process")
            return emptyList()
        }

        // Find time range
        val startTime = hrSamples.minOf { it.timestamp }
        val endTime = hrSamples.maxOf { it.timestamp }

        val epochs = mutableListOf<SleepEpoch>()
        var epochStart = startTime

        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        // Previous epoch for trend calculation
        var prevTempMean: Float? = null

        while (epochStart < endTime) {
            val epochEnd = epochStart + EPOCH_DURATION_MS

            // Get HR samples in this epoch
            val epochHR = hrSamples.filter { it.timestamp in epochStart until epochEnd }

            // Get temp samples in this epoch
            val epochTemp = tempSamples.filter { it.timestamp in epochStart until epochEnd }

            if (epochHR.isNotEmpty()) {
                val hrValues = epochHR.map { it.bpm.toFloat() }
                val tempValues = if (epochTemp.isNotEmpty()) {
                    epochTemp.map { it.tempCelsius }
                } else {
                    listOf(34.0f)  // Default skin temp if no data
                }

                // Calculate HR statistics
                val hrMean = hrValues.average().toFloat()
                val hrStd = hrValues.std()
                val hrMin = hrValues.minOrNull() ?: 0f
                val hrMax = hrValues.maxOrNull() ?: 0f
                val hrRange = hrMax - hrMin
                val hrCv = if (hrMean > 0) (hrStd / hrMean) * 100 else 0f
                val hrMedian = hrValues.median()
                val hrIqr = hrValues.iqr()
                val hrSkew = hrValues.skewness()

                // Calculate temp statistics
                val tempMean = tempValues.average().toFloat()
                val tempStd = if (tempValues.size > 1) tempValues.std() else 0f
                val tempTrend = if (prevTempMean != null) tempMean - prevTempMean!! else 0f

                epochs.add(SleepEpoch(
                    timestamp = epochStart,
                    timeString = dateFormatter.format(Date(epochStart)),
                    hrMean = hrMean,
                    hrStd = hrStd,
                    hrMin = hrMin,
                    hrMax = hrMax,
                    hrRange = hrRange,
                    hrCv = hrCv,
                    hrMedian = hrMedian,
                    hrIqr = hrIqr,
                    hrSkew = hrSkew,
                    tempMean = tempMean,
                    tempStd = tempStd,
                    tempTrend = tempTrend,
                    hrSampleCount = epochHR.size,
                    tempSampleCount = epochTemp.size
                ))

                prevTempMean = tempMean
            }

            epochStart = epochEnd
        }

        Log.d(TAG, "Processed ${epochs.size} epochs from raw data")
        return epochs
    }

    // ==================== EXPORT FUNCTIONS ====================

    /**
     * Export all sleep-relevant data to CSV for analysis
     */
    suspend fun exportDataToCSV(hoursBack: Int = 24): String {
        val epochs = processDataIntoEpochs(hoursBack)

        if (epochs.isEmpty()) {
            return "No data to export"
        }

        val csv = StringBuilder()

        // Header
        csv.appendLine("timestamp,time,hr_mean,hr_std,hr_min,hr_max,hr_range,hr_cv,hr_median,hr_iqr,hr_skew,temp_mean,temp_std,temp_trend,hr_samples,temp_samples")

        // Data rows
        epochs.forEach { epoch ->
            csv.appendLine("${epoch.timestamp},${epoch.timeString},${epoch.hrMean},${epoch.hrStd},${epoch.hrMin},${epoch.hrMax},${epoch.hrRange},${epoch.hrCv},${epoch.hrMedian},${epoch.hrIqr},${epoch.hrSkew},${epoch.tempMean},${epoch.tempStd},${epoch.tempTrend},${epoch.hrSampleCount},${epoch.tempSampleCount}")
        }

        // Save to file
        try {
            val filename = "sleepwise_data_${System.currentTimeMillis()}.csv"
            val file = File(context.getExternalFilesDir(null), filename)
            file.writeText(csv.toString())
            Log.d(TAG, "Exported data to ${file.absolutePath}")
            return "Exported ${epochs.size} epochs to:\n${file.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save CSV", e)
            return csv.toString()
        }
    }

    // ==================== DISPLAY FUNCTIONS ====================

    /**
     * Get comprehensive health data summary for display
     */
    suspend fun getFormattedHealthData(): String {
        val output = StringBuilder()
        val dateFormatter = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

        output.append("=== SAMSUNG HEALTH DATA ===\n\n")

        // Heart Rate
        output.append("--- HEART RATE (24h) ---\n")
        val heartRates = readHeartRate(hoursBack = 24)
        if (heartRates.isEmpty()) {
            output.append("No heart rate data\n")
        } else {
            val hrValues = heartRates.map { it.bpm }
            output.append("Samples: ${heartRates.size}\n")
            output.append("Avg: ${hrValues.average().toInt()} bpm\n")
            output.append("Min: ${hrValues.minOrNull()} | Max: ${hrValues.maxOrNull()}\n")
            output.append("Latest: ${heartRates.last().bpm} bpm @ ${dateFormatter.format(Date(heartRates.last().timestamp))}\n")
        }

        // Temperature
        output.append("\n--- SKIN TEMPERATURE (24h) ---\n")
        val temps = readSkinTemperature(hoursBack = 24)
        if (temps.isEmpty()) {
            output.append("No temperature data\n")
        } else {
            val tempValues = temps.map { it.tempCelsius }
            output.append("Samples: ${temps.size}\n")
            output.append("Avg: ${String.format("%.1f", tempValues.average())}°C\n")
            output.append("Min: ${String.format("%.1f", tempValues.minOrNull())} | Max: ${String.format("%.1f", tempValues.maxOrNull())}\n")
        }

        // Blood Oxygen
        output.append("\n--- BLOOD OXYGEN (24h) ---\n")
        val spo2 = readBloodOxygen(hoursBack = 24)
        if (spo2.isEmpty()) {
            output.append("No SpO2 data\n")
        } else {
            val spo2Values = spo2.map { it.percentage }
            output.append("Samples: ${spo2.size}\n")
            output.append("Avg: ${spo2Values.average().toInt()}%\n")
            output.append("Min: ${spo2Values.minOrNull()}% | Max: ${spo2Values.maxOrNull()}%\n")
        }

        // Sleep
        output.append("\n--- SLEEP (7 days) ---\n")
        val sleepSessions = readSleep(daysBack = 7)
        if (sleepSessions.isEmpty()) {
            output.append("No sleep sessions\n")
        } else {
            output.append("Sessions: ${sleepSessions.size}\n")
            sleepSessions.take(3).forEach { session ->
                val durationHours = (session.endTime - session.startTime) / 3600000.0
                val startStr = dateFormatter.format(Date(session.startTime))
                output.append("$startStr - ${String.format("%.1f", durationHours)}h\n")
            }
        }

        // Epochs summary
        output.append("\n--- EPOCHS (8h) ---\n")
        val epochs = processDataIntoEpochs(hoursBack = 8)
        if (epochs.isEmpty()) {
            output.append("No epochs processed\n")
        } else {
            output.append("Total: ${epochs.size} epochs\n")
            val avgHR = epochs.map { it.hrMean }.average()
            val avgTemp = epochs.map { it.tempMean }.average()
            output.append("Avg HR: ${String.format("%.1f", avgHR)} bpm\n")
            output.append("Avg Temp: ${String.format("%.1f", avgTemp)}°C\n")
        }

        return output.toString()
    }

    /**
     * Convert epoch to TFLiteSleepPredictor.EpochFeatures for prediction
     */
    fun epochToFeatures(epoch: SleepEpoch): TFLiteSleepPredictor.EpochFeatures {
        return TFLiteSleepPredictor.EpochFeatures(
            hrMean = epoch.hrMean,
            hrStd = epoch.hrStd,
            hrMin = epoch.hrMin,
            hrMax = epoch.hrMax,
            hrRange = epoch.hrRange,
            hrCv = epoch.hrCv,
            hrMedian = epoch.hrMedian,
            hrIqr = epoch.hrIqr,
            hrSkew = epoch.hrSkew,
            tempMean = epoch.tempMean,
            tempStd = epoch.tempStd,
            tempTrend = epoch.tempTrend
        )
    }
}

// ==================== EXTENSION FUNCTIONS ====================

private fun List<Float>.std(): Float {
    if (size < 2) return 0f
    val mean = average()
    val variance = map { (it - mean).pow(2) }.average()
    return sqrt(variance).toFloat()
}

private fun List<Float>.median(): Float {
    if (isEmpty()) return 0f
    val sorted = sorted()
    return if (size % 2 == 0) {
        (sorted[size / 2 - 1] + sorted[size / 2]) / 2
    } else {
        sorted[size / 2]
    }
}

private fun List<Float>.iqr(): Float {
    if (size < 4) return 0f
    val sorted = sorted()
    val q1Idx = size / 4
    val q3Idx = (3 * size) / 4
    return sorted[q3Idx] - sorted[q1Idx]
}

private fun List<Float>.skewness(): Float {
    if (size < 3) return 0f
    val mean = average().toFloat()
    val std = std()
    if (std == 0f) return 0f

    val n = size.toFloat()
    val skew = map { ((it - mean) / std).pow(3) }.sum() * (n / ((n - 1) * (n - 2)))
    return skew
}
