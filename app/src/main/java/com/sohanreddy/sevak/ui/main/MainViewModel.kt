package com.sohanreddy.sevak.ui.main

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sohanreddy.sevak.audio.AudioHelper
import com.sohanreddy.sevak.data.PrefsManager
import com.sohanreddy.sevak.data.getLanguageByCode
import com.sohanreddy.sevak.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale

enum class AssistantState { IDLE, LISTENING, PROCESSING, SPEAKING }

data class MainScreenState(
    val assistantState: AssistantState = AssistantState.IDLE,
    val lastResponse: String = "",
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MainScreenState())
    val state = _state.asStateFlow()

    private val audioHelper = AudioHelper(application)
    private val prefs = PrefsManager(application)
    private var androidTts: TextToSpeech? = null
    private var ttsReady = false

    init {
        androidTts = TextToSpeech(application) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    fun hasAudioPermission(): Boolean = audioHelper.hasPermission()

    fun onMicTap() {
        when (_state.value.assistantState) {
            AssistantState.IDLE -> startListening()
            AssistantState.LISTENING -> stopListening()
            else -> {} // ignore taps during processing/speaking
        }
    }

    private fun startListening() {
        if (!audioHelper.hasPermission()) {
            Log.w("MainVM", "No audio permission")
            return
        }
        Log.d("MainVM", "Starting recording...")
        _state.value = _state.value.copy(assistantState = AssistantState.LISTENING, error = null)
        audioHelper.startRecording()
    }

    private fun stopListening() {
        Log.d("MainVM", "Stopping recording...")
        _state.value = _state.value.copy(assistantState = AssistantState.PROCESSING)
        val wavFile = audioHelper.stopRecording()
        if (wavFile == null || !wavFile.exists()) {
            Log.e("MainVM", "Recording failed — no wav file")
            _state.value = _state.value.copy(assistantState = AssistantState.IDLE, lastResponse = "Recording failed. Please try again.")
            return
        }
        Log.d("MainVM", "WAV file: ${wavFile.absolutePath}, size: ${wavFile.length()} bytes")

        val langCode = prefs.getLanguageCode() ?: "en"
        val lang = getLanguageByCode(langCode)
        val sarvamCode = lang?.sarvamCode ?: "en-IN"

        viewModelScope.launch(Dispatchers.IO) {
            var transcript: String? = null

            // Try Sarvam STT
            try {
                Log.d("MainVM", "Calling Sarvam STT with lang=$sarvamCode")
                val filePart = MultipartBody.Part.createFormData(
                    "file", wavFile.name,
                    wavFile.asRequestBody("audio/wav".toMediaTypeOrNull())
                )
                val modelPart = "saaras:v3".toRequestBody("text/plain".toMediaTypeOrNull())
                val langPart = sarvamCode.toRequestBody("text/plain".toMediaTypeOrNull())
                val response = SarvamApi.speechToText(file = filePart, model = modelPart, languageCode = langPart)
                transcript = response.transcript
                Log.d("MainVM", "STT transcript: $transcript")
            } catch (e: Exception) {
                Log.e("MainVM", "Sarvam STT failed: ${e.message}", e)
            }

            if (transcript.isNullOrBlank()) {
                Log.w("MainVM", "No transcript, showing error")
                _state.value = _state.value.copy(
                    assistantState = AssistantState.IDLE,
                    lastResponse = "Could not understand. Please try again."
                )
                return@launch
            }

            // Call Groq LLM
            val langName = lang?.englishName ?: "English"
            Log.d("MainVM", "Calling Groq with transcript: $transcript, lang: $langName")
            val responseText = callGroq(transcript, langName)
            Log.d("MainVM", "Groq response: $responseText")

            _state.value = _state.value.copy(lastResponse = responseText)

            // Call Sarvam TTS
            _state.value = _state.value.copy(assistantState = AssistantState.SPEAKING)
            try {
                Log.d("MainVM", "Calling Sarvam TTS with lang=$sarvamCode")
                val ttsReq = TtsRequest(
                    inputs = listOf(responseText),
                    target_language_code = sarvamCode
                )
                val ttsResp = SarvamApi.textToSpeech(request = ttsReq)
                if (ttsResp.audios.isNotEmpty()) {
                    Log.d("MainVM", "TTS audio received, playing...")
                    audioHelper.playBase64Audio(ttsResp.audios[0].audio) {
                        _state.value = _state.value.copy(assistantState = AssistantState.IDLE)
                    }
                    return@launch
                } else {
                    Log.w("MainVM", "TTS returned empty audios")
                }
            } catch (e: Exception) {
                Log.e("MainVM", "Sarvam TTS failed: ${e.message}", e)
            }

            // Fallback to Android TTS
            Log.d("MainVM", "Falling back to Android TTS")
            fallbackTts(responseText, sarvamCode)
        }
    }

    private suspend fun callGroq(transcript: String, langName: String): String {
        return try {
            val systemPrompt = """You are Saathi, a helpful AI assistant for rural and semi-urban Indian users.
The user speaks $langName. Always respond in $langName only.
Use extremely simple words. Speak like a helpful neighbor, not a government form.
Keep responses under 3 sentences. Be direct and actionable.
If asked about government schemes, give eligibility in one line and the single most important next step. Never use English if the selected language is not English."""

            val request = GroqRequest(
                messages = listOf(
                    GroqMessage("system", systemPrompt),
                    GroqMessage("user", transcript)
                )
            )
            val response = GroqApi.chat(request = request)
            response.choices.firstOrNull()?.message?.content ?: "Something went wrong, please try again"
        } catch (e: Exception) {
            Log.e("MainVM", "Groq failed: ${e.message}", e)
            "Something went wrong, please try again"
        }
    }

    private fun fallbackTts(text: String, langCode: String) {
        if (!ttsReady) {
            Log.w("MainVM", "Android TTS not ready")
            _state.value = _state.value.copy(assistantState = AssistantState.IDLE)
            return
        }
        val locale = when {
            langCode.startsWith("hi") -> Locale("hi", "IN")
            langCode.startsWith("kn") -> Locale("kn", "IN")
            langCode.startsWith("ta") -> Locale("ta", "IN")
            langCode.startsWith("te") -> Locale("te", "IN")
            langCode.startsWith("mr") -> Locale("mr", "IN")
            langCode.startsWith("bn") -> Locale("bn", "IN")
            langCode.startsWith("gu") -> Locale("gu", "IN")
            else -> Locale("en", "IN")
        }
        androidTts?.language = locale
        androidTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                _state.value = _state.value.copy(assistantState = AssistantState.IDLE)
            }
            @Deprecated("Deprecated") override fun onError(utteranceId: String?) {
                _state.value = _state.value.copy(assistantState = AssistantState.IDLE)
            }
        })
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "saathi_tts")
    }

    override fun onCleared() {
        androidTts?.shutdown()
        super.onCleared()
    }
}
