package com.sohanreddy.sevak.network

import com.sohanreddy.sevak.Constants
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

data class SttResponse(val transcript: String)

data class TtsRequest(
    val inputs: List<String>,
    val target_language_code: String,
    val model: String = "bulbul:v3",
    val speaker: String = "meera"
)
data class TtsAudio(val audio: String) // base64 encoded
data class TtsResponse(val audios: List<TtsAudio>)

interface SarvamApiService {
    @Multipart
    @POST("speech-to-text")
    suspend fun speechToText(
        @Header("api-subscription-key") apiKey: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language_code") languageCode: RequestBody
    ): SttResponse

    @POST("text-to-speech")
    suspend fun textToSpeech(
        @Header("api-subscription-key") apiKey: String,
        @Body request: TtsRequest
    ): TtsResponse
}

object SarvamApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .build()

    val service: SarvamApiService = Retrofit.Builder()
        .baseUrl("https://api.sarvam.ai/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SarvamApiService::class.java)

    suspend fun speechToText(file: MultipartBody.Part, model: RequestBody, languageCode: RequestBody): SttResponse {
        return service.speechToText(Constants.SARVAM_API_KEY, file, model, languageCode)
    }

    suspend fun textToSpeech(request: TtsRequest): TtsResponse {
        return service.textToSpeech(Constants.SARVAM_API_KEY, request)
    }
}
