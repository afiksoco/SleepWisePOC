package com.example.sleepwisepoc

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * HealthConnectManager - Reads health data from Samsung Health via Health Connect API
 *
 * Data flow: Galaxy Watch -> Samsung Health -> Health Connect -> This App
 */
class HealthConnectManager(private val context: Context) {

    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    companion object {
        // Permissions we need
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        )

        // Check if Health Connect is available on this device
        fun isAvailable(context: Context): Boolean {
            return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }
    }

    // Check if we have all required permissions
    suspend fun hasAllPermissions(): Boolean {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    // ========== HEART RATE ==========
    data class HeartRateSample(
        val bpm: Long,
        val timestamp: Instant
    )

    suspend fun readHeartRate(hoursBack: Int = 24): List<HeartRateSample> {
        val now = Instant.now()
        val startTime = now.minus(hoursBack.toLong(), ChronoUnit.HOURS)

        val request = ReadRecordsRequest(
            recordType = HeartRateRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, now)
        )

        val response = healthConnectClient.readRecords(request)

        return response.records.flatMap { record ->
            record.samples.map { sample ->
                HeartRateSample(
                    bpm = sample.beatsPerMinute,
                    timestamp = sample.time
                )
            }
        }.sortedBy { it.timestamp }
    }

    // ========== HRV (Heart Rate Variability) ==========
    data class HrvSample(
        val rmssd: Double,  // RMSSD in milliseconds
        val timestamp: Instant
    )

    suspend fun readHRV(hoursBack: Int = 24): List<HrvSample> {
        val now = Instant.now()
        val startTime = now.minus(hoursBack.toLong(), ChronoUnit.HOURS)

        val request = ReadRecordsRequest(
            recordType = HeartRateVariabilityRmssdRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, now)
        )

        val response = healthConnectClient.readRecords(request)

        return response.records.map { record ->
            HrvSample(
                rmssd = record.heartRateVariabilityMillis,
                timestamp = record.time
            )
        }.sortedBy { it.timestamp }
    }

    // ========== SLEEP SESSIONS ==========
    data class SleepStage(
        val stage: String,  // "Awake", "Light", "Deep", "REM", etc.
        val startTime: Instant,
        val endTime: Instant
    )

    data class SleepSession(
        val startTime: Instant,
        val endTime: Instant,
        val stages: List<SleepStage>
    ) {
        fun durationMinutes(): Long {
            return ChronoUnit.MINUTES.between(startTime, endTime)
        }

        fun getStageSummary(): Map<String, Long> {
            return stages.groupBy { it.stage }
                .mapValues { (_, stages) ->
                    stages.sumOf { ChronoUnit.MINUTES.between(it.startTime, it.endTime) }
                }
        }
    }

    suspend fun readSleepSessions(daysBack: Int = 7): List<SleepSession> {
        val now = Instant.now()
        val startTime = now.minus(daysBack.toLong(), ChronoUnit.DAYS)

        val request = ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, now)
        )

        val response = healthConnectClient.readRecords(request)

        return response.records.map { record ->
            val stages = record.stages.map { stage ->
                SleepStage(
                    stage = mapSleepStage(stage.stage),
                    startTime = stage.startTime,
                    endTime = stage.endTime
                )
            }

            SleepSession(
                startTime = record.startTime,
                endTime = record.endTime,
                stages = stages
            )
        }.sortedByDescending { it.startTime }
    }

    private fun mapSleepStage(stageType: Int): String {
        return when (stageType) {
            SleepSessionRecord.STAGE_TYPE_AWAKE -> "Awake"
            SleepSessionRecord.STAGE_TYPE_SLEEPING -> "Sleeping"
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "Out of Bed"
            SleepSessionRecord.STAGE_TYPE_LIGHT -> "Light"
            SleepSessionRecord.STAGE_TYPE_DEEP -> "Deep"
            SleepSessionRecord.STAGE_TYPE_REM -> "REM"
            else -> "Unknown"
        }
    }

    // ========== SpO2 (Oxygen Saturation) ==========
    data class SpO2Sample(
        val percentage: Double,
        val timestamp: Instant
    )

    suspend fun readOxygenSaturation(hoursBack: Int = 24): List<SpO2Sample> {
        val now = Instant.now()
        val startTime = now.minus(hoursBack.toLong(), ChronoUnit.HOURS)

        val request = ReadRecordsRequest(
            recordType = OxygenSaturationRecord::class,
            timeRangeFilter = TimeRangeFilter.between(startTime, now)
        )

        val response = healthConnectClient.readRecords(request)

        return response.records.map { record ->
            SpO2Sample(
                percentage = record.percentage.value,
                timestamp = record.time
            )
        }.sortedBy { it.timestamp }
    }

    // ========== AGGREGATED DATA FOR ML MODEL ==========
    data class HealthSnapshot(
        val heartRate: Double,      // Average HR
        val hrvRmssd: Double,       // Average HRV
        val movement: Double,       // Estimated from sleep stage transitions (0-1)
        val hour: Int,              // Current hour (0-23)
        val sleepStage: String?     // Current sleep stage if sleeping
    )

    suspend fun getLatestHealthSnapshot(): HealthSnapshot? {
        val heartRates = readHeartRate(hoursBack = 1)
        val hrvSamples = readHRV(hoursBack = 1)
        val sleepSessions = readSleepSessions(daysBack = 1)

        if (heartRates.isEmpty()) return null

        val avgHR = heartRates.map { it.bpm }.average()
        val avgHRV = if (hrvSamples.isNotEmpty()) hrvSamples.map { it.rmssd }.average() else 30.0
        val currentHour = Instant.now().atZone(ZoneId.systemDefault()).hour

        // Check if currently in a sleep session
        val now = Instant.now()
        val currentSleepSession = sleepSessions.firstOrNull { session ->
            now.isAfter(session.startTime) && now.isBefore(session.endTime)
        }

        val currentStage = currentSleepSession?.stages?.lastOrNull { stage ->
            now.isAfter(stage.startTime) && now.isBefore(stage.endTime)
        }?.stage

        // Estimate movement from sleep stage (lower movement = deeper sleep)
        val movement = when (currentStage) {
            "Deep" -> 0.1
            "Light" -> 0.3
            "REM" -> 0.2
            "Awake" -> 0.8
            else -> 0.5
        }

        return HealthSnapshot(
            heartRate = avgHR,
            hrvRmssd = avgHRV,
            movement = movement,
            hour = currentHour,
            sleepStage = currentStage
        )
    }
}
