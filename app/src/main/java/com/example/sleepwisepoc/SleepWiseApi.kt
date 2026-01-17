package com.example.sleepwisepoc

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * API interface for communicating with the Python FastAPI backend
 */

// Request model - matches SimplifiedHealthData on server
data class SimplifiedHealthData(
    val heart_rate: Double,
    val hrv_rmssd: Double,
    val movement: Double,
    val hour: Int
)

// Response model - matches PredictionResponse on server
data class PredictionResponse(
    val sleep_stage: String,
    val confidence: Double,
    val should_wake: Boolean,
    val message: String
)

// Health check response
data class HealthCheckResponse(
    val service: String,
    val status: String,
    val model_loaded: Boolean
)

interface SleepWiseApi {

    @GET("/")
    suspend fun healthCheck(): HealthCheckResponse

    @POST("/predict/simple")
    suspend fun predictSleepStage(@Body data: SimplifiedHealthData): PredictionResponse

}

object ApiClient {
    // Your computer's local IP for physical device testing
    // Use 10.0.2.2 for Android emulator (localhost of host machine)
    private const val BASE_URL = "http://10.1.40.24:5000/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: SleepWiseApi = retrofit.create(SleepWiseApi::class.java)

    // For physical device, call this with your computer's local IP
    fun createApiWithBaseUrl(baseUrl: String): SleepWiseApi {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SleepWiseApi::class.java)
    }
}
