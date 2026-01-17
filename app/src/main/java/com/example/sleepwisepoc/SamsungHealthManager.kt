package com.example.sleepwisepoc

import android.app.Activity
import android.content.Context
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
import java.time.LocalDateTime

/**
 * SamsungHealthManager - Reads health data from Samsung Health via SDK
 *
 * Data flow: Galaxy Watch -> Samsung Health -> This App (via SDK) -> Backend -> Prediction
 */
class SamsungHealthManager(private val context: Context) {

    companion object {
        private const val TAG = "SamsungHealth"

        // Permissions we need
        val PERMISSIONS = setOf(
            Permission.of(DataTypes.HEART_RATE, AccessType.READ),
            Permission.of(DataTypes.SLEEP, AccessType.READ),
            Permission.of(DataTypes.BLOOD_OXYGEN, AccessType.READ)
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

    // Check if permissions are granted
    suspend fun hasPermissions(): Boolean {
        val store = healthDataStore ?: return false
        return try {
            val granted = store.getGrantedPermissions(PERMISSIONS)
            granted.containsAll(PERMISSIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            false
        }
    }

    // Data classes
    data class HeartRateSample(
        val bpm: Int,
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

    data class HealthSnapshot(
        val heartRate: Double,
        val hrvRmssd: Double,
        val movement: Double,
        val hour: Int,
        val sleepStage: String?
    )

    // Read heart rate data from watch
    suspend fun readHeartRate(hoursBack: Int = 24): List<HeartRateSample> {
        val store = healthDataStore ?: return emptyList()

        return try {
            val endTime = LocalDateTime.now()
            val startTime = endTime.minusHours(hoursBack.toLong())
            val localTimeFilter = LocalTimeFilter.of(startTime, endTime)

            val readRequest = DataTypes.HEART_RATE.readDataRequestBuilder
                .setLocalTimeFilter(localTimeFilter)
                // Removed WATCH filter to include all sources (manual entries, phone, watch)
                .setOrdering(Ordering.DESC)
                .build()

            val response = store.readData(readRequest)
            val samples = mutableListOf<HeartRateSample>()

            response.dataList.forEach { dataPoint ->
                try {
                    // HEART_RATE field returns Float
                    val bpm = dataPoint.getValue(DataType.HeartRateType.HEART_RATE)
                    val timestamp = dataPoint.startTime.toEpochMilli()

                    if (bpm != null) {
                        samples.add(HeartRateSample(bpm.toInt(), timestamp))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing heart rate data point", e)
                }
            }

            Log.d(TAG, "Read ${samples.size} heart rate samples from watch")
            samples
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read heart rate", e)
            emptyList()
        }
    }

    // Read sleep data
    suspend fun readSleep(daysBack: Int = 7): List<SleepSession> {
        val store = healthDataStore ?: return emptyList()

        return try {
            val endTime = LocalDateTime.now()
            val startTime = endTime.minusDays(daysBack.toLong())
            val localTimeFilter = LocalTimeFilter.of(startTime, endTime)

            val readRequest = DataTypes.SLEEP.readDataRequestBuilder
                .setLocalTimeFilter(localTimeFilter)
                // Removed WATCH filter to include all sources (manual entries, phone, watch)
                .setOrdering(Ordering.DESC)
                .build()

            val response = store.readData(readRequest)
            val sessions = mutableListOf<SleepSession>()

            response.dataList.forEach { dataPoint ->
                try {
                    val sessionStart = dataPoint.startTime.toEpochMilli()
                    val sessionEnd = dataPoint.endTime?.toEpochMilli() ?: sessionStart

                    // TODO: Parse sleep stages from data point if available
                    sessions.add(SleepSession(
                        startTime = sessionStart,
                        endTime = sessionEnd,
                        stages = emptyList()
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing sleep data point", e)
                }
            }

            Log.d(TAG, "Read ${sessions.size} sleep sessions from watch")
            sessions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read sleep data", e)
            emptyList()
        }
    }

    // Get current health snapshot for prediction
    suspend fun getLatestSnapshot(): HealthSnapshot {
        val heartRates = readHeartRate(hoursBack = 1)

        val avgHR = if (heartRates.isNotEmpty()) {
            heartRates.map { it.bpm }.average()
        } else {
            65.0 // Default if no data
        }

        val currentHour = LocalDateTime.now().hour

        // Determine if likely sleeping based on hour
        val isSleepTime = currentHour in 22..23 || currentHour in 0..7
        val currentStage = if (isSleepTime && heartRates.isNotEmpty()) {
            // Simple heuristic: lower HR suggests deeper sleep
            when {
                avgHR < 55 -> "Deep"
                avgHR < 65 -> "Light"
                else -> "REM"
            }
        } else {
            null
        }

        // Estimate HRV from HR (simplified - real HRV needs raw RR intervals)
        val hrvRmssd = 30.0 + (70.0 - avgHR) * 0.5

        // Estimate movement (simplified)
        val movement = when (currentStage) {
            "Deep" -> 0.1
            "Light" -> 0.3
            "REM" -> 0.2
            else -> 0.5
        }

        return HealthSnapshot(
            heartRate = avgHR,
            hrvRmssd = hrvRmssd,
            movement = movement,
            hour = currentHour,
            sleepStage = currentStage
        )
    }

    // Format health data for display
    suspend fun getFormattedHealthData(): String {
        val output = StringBuilder()

        output.append("=== SAMSUNG HEALTH DATA ===\n\n")

        // Heart Rate - look back 2 years (17520 hours)
        output.append("=== HEART RATE (all time) ===\n")
        val heartRates = readHeartRate(hoursBack = 17520)
        if (heartRates.isEmpty()) {
            output.append("No heart rate data from watch\n")
        } else {
            val avg = heartRates.map { it.bpm }.average()
            val min = heartRates.minOf { it.bpm }
            val max = heartRates.maxOf { it.bpm }
            output.append("Samples: ${heartRates.size}\n")
            output.append("Avg: ${avg.toInt()} bpm | Min: $min | Max: $max\n")
        }

        // Sleep
        output.append("\n=== SLEEP (7 days) ===\n")
        val sleepSessions = readSleep(daysBack = 7)
        if (sleepSessions.isEmpty()) {
            output.append("No sleep data from watch\n")
        } else {
            output.append("Sessions: ${sleepSessions.size}\n")
            sleepSessions.take(3).forEach { session ->
                val durationHours = (session.endTime - session.startTime) / 3600000.0
                output.append("Duration: ${String.format("%.1f", durationHours)} hours\n")
            }
        }

        return output.toString()
    }
}
