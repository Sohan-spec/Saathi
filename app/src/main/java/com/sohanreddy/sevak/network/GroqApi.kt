package com.sohanreddy.sevak.network

import com.sohanreddy.sevak.Constants
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

data class GroqMessage(val role: String, val content: String)
data class GroqRequest(
    val model: String = "llama-3.3-70b-versatile",
    val messages: List<GroqMessage>,
    val temperature: Double = 0.4,
    val max_tokens: Int = 300
)
data class GroqChoice(val message: GroqMessage)
data class GroqResponse(val choices: List<GroqChoice>)

interface GroqApiService {
    @POST("openai/v1/chat/completions")
    suspend fun chat(
        @Header("Authorization") auth: String,
        @Body request: GroqRequest
    ): GroqResponse
}

object GroqApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .build()

    val service: GroqApiService = Retrofit.Builder()
        .baseUrl("https://api.groq.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GroqApiService::class.java)

    suspend fun chat(request: GroqRequest): GroqResponse {
        return service.chat("Bearer ${Constants.GROQ_API_KEY}", request)
    }
}
