package com.sohanreddy.sevak.ui.main

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sohanreddy.sevak.audio.AudioHelper
import com.sohanreddy.sevak.data.PrefsManager
import com.sohanreddy.sevak.data.getLanguageByCode
import com.sohanreddy.sevak.data.getLanguageBySarvamCode
import com.sohanreddy.sevak.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val error: String? = null,
    val detectedLangCode: String? = null,
    val audioAmplitude: Float = 0f
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(MainScreenState())
    val state = _state.asStateFlow()

    private val audioHelper = AudioHelper(application)
    private val prefs = PrefsManager(application)
    private var androidTts: TextToSpeech? = null
    private var ttsReady = false

    // Silence detection
    private val silenceThreshold = 0.015f
    private val silenceTimeoutMs = 1500L
    private var silenceJob: Job? = null
    private var lastLoudTimestamp = 0L

    init {
        androidTts = TextToSpeech(application) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        // Wire up amplitude callback
        audioHelper.onAmplitudeUpdate = { amplitude ->
            _state.value = _state.value.copy(audioAmplitude = amplitude)

            // Silence detection while listening
            if (_state.value.assistantState == AssistantState.LISTENING) {
                if (amplitude > silenceThreshold) {
                    lastLoudTimestamp = System.currentTimeMillis()
                }
            }
        }
    }

    fun hasAudioPermission(): Boolean = audioHelper.hasPermission()

    /** Called from settings when user manually picks a language */
    fun setLanguageManually(code: String, name: String) {
        prefs.saveLanguage(code, name)
        Log.d("MainVM", "Language manually set to $code ($name)")
    }

    fun onMicTap() {
        when (_state.value.assistantState) {
            AssistantState.IDLE -> startListening()
            AssistantState.LISTENING -> stopListeningAndProcess()
            AssistantState.SPEAKING -> stopSpeaking()
            else -> {} // ignore taps during processing
        }
    }

    private fun startListening() {
        if (!audioHelper.hasPermission()) {
            Log.w("MainVM", "No audio permission")
            return
        }
        Log.d("MainVM", "Starting recording...")
        _state.value = _state.value.copy(assistantState = AssistantState.LISTENING, error = null, audioAmplitude = 0f)
        lastLoudTimestamp = System.currentTimeMillis()
        audioHelper.startRecording()

        // Start silence monitoring
        silenceJob?.cancel()
        silenceJob = viewModelScope.launch {
            // Wait a short initial period to allow user to start speaking
            delay(800)
            while (_state.value.assistantState == AssistantState.LISTENING) {
                val elapsed = System.currentTimeMillis() - lastLoudTimestamp
                if (elapsed >= silenceTimeoutMs) {
                    Log.d("MainVM", "Silence detected, auto-stopping...")
                    stopListeningAndProcess()
                    break
                }
                delay(100)
            }
        }
    }

    private fun stopListeningAndProcess() {
        silenceJob?.cancel()
        Log.d("MainVM", "Stopping recording...")
        _state.value = _state.value.copy(assistantState = AssistantState.PROCESSING, audioAmplitude = 0f)
        val wavFile = audioHelper.stopRecording()
        if (wavFile == null || !wavFile.exists()) {
            Log.e("MainVM", "Recording failed — no wav file")
            _state.value = _state.value.copy(assistantState = AssistantState.IDLE, lastResponse = "Recording failed. Please try again.")
            return
        }
        Log.d("MainVM", "WAV file: ${wavFile.absolutePath}, size: ${wavFile.length()} bytes")

        viewModelScope.launch(Dispatchers.IO) {
            var transcript: String? = null
            var detectedSarvamCode: String? = null

            // Determine language code to send to STT
            // If user has a saved language, use it; otherwise send "unknown" for auto-detect
            val savedLangCode = prefs.getLanguageCode()
            val savedLang = savedLangCode?.let { getLanguageByCode(it) }
            val sttLangCode = savedLang?.sarvamCode ?: "unknown"

            // Try Sarvam STT
            try {
                Log.d("MainVM", "Calling Sarvam STT with lang=$sttLangCode")
                val filePart = MultipartBody.Part.createFormData(
                    "file", wavFile.name,
                    wavFile.asRequestBody("audio/wav".toMediaTypeOrNull())
                )
                val modelPart = "saaras:v3".toRequestBody("text/plain".toMediaTypeOrNull())
                val langPart = sttLangCode.toRequestBody("text/plain".toMediaTypeOrNull())
                val response = SarvamApi.speechToText(file = filePart, model = modelPart, languageCode = langPart)
                transcript = response.transcript
                detectedSarvamCode = response.language_code
                Log.d("MainVM", "STT transcript: $transcript, detected_lang: $detectedSarvamCode")
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

            // Resolve the language to use for LLM + TTS
            // Priority: detected language from STT > saved language > default hi-IN
            val resolvedSarvamCode: String
            val resolvedLang: com.sohanreddy.sevak.data.Language

            if (savedLang != null) {
                // User has explicitly chosen a language, use it
                resolvedSarvamCode = savedLang.sarvamCode
                resolvedLang = savedLang
            } else if (!detectedSarvamCode.isNullOrBlank()) {
                // Auto-detected from STT — save it for future use
                val detected = getLanguageBySarvamCode(detectedSarvamCode)
                if (detected != null) {
                    resolvedSarvamCode = detected.sarvamCode
                    resolvedLang = detected
                    // Persist detected language
                    prefs.saveLanguage(detected.code, detected.englishName)
                    Log.d("MainVM", "Auto-detected and saved language: ${detected.code} (${detected.englishName})")
                } else {
                    // Detected a language we don't support — default to Hindi
                    resolvedSarvamCode = "hi-IN"
                    resolvedLang = getLanguageBySarvamCode("hi-IN")!!
                    prefs.saveLanguage("hi", "Hindi")
                    Log.d("MainVM", "Unsupported detected language $detectedSarvamCode, defaulting to hi-IN")
                }
            } else {
                // Nothing detected, default to Hindi
                resolvedSarvamCode = "hi-IN"
                resolvedLang = getLanguageBySarvamCode("hi-IN")!!
                prefs.saveLanguage("hi", "Hindi")
                Log.d("MainVM", "No language detected, defaulting to hi-IN")
            }

            _state.value = _state.value.copy(detectedLangCode = resolvedLang.code)

            // Call Groq LLM
            val langName = resolvedLang.englishName
            Log.d("MainVM", "Calling Groq with transcript: $transcript, lang: $langName")
            val responseText = callGroq(transcript, langName)
            Log.d("MainVM", "Groq response: $responseText")

            _state.value = _state.value.copy(lastResponse = responseText)

            // Call Sarvam TTS
            _state.value = _state.value.copy(assistantState = AssistantState.SPEAKING)
            try {
                Log.d("MainVM", "Calling Sarvam TTS with lang=$resolvedSarvamCode")
                val ttsReq = TtsRequest(
                    text = responseText,
                    target_language_code = resolvedSarvamCode
                )
                val ttsResp = SarvamApi.textToSpeech(request = ttsReq)
                if (ttsResp.audios.isNotEmpty()) {
                    Log.d("MainVM", "TTS audio received, playing...")
                    audioHelper.playBase64Audio(ttsResp.audios[0]) {
                        _state.value = _state.value.copy(assistantState = AssistantState.IDLE, audioAmplitude = 0f)
                    }
                    return@launch
                } else {
                    Log.w("MainVM", "TTS returned empty audios")
                }
            } catch (e: retrofit2.HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                Log.e("MainVM", "Sarvam TTS HTTP ${e.code()}: $errorBody", e)
            } catch (e: Exception) {
                Log.e("MainVM", "Sarvam TTS failed: ${e.message}", e)
            }

            // Fallback to Android TTS
            Log.d("MainVM", "Falling back to Android TTS")
            fallbackTts(responseText, resolvedSarvamCode)
        }
    }

    /** Stop speaking and return to idle */
    private fun stopSpeaking() {
        Log.d("MainVM", "Stopping speech...")
        audioHelper.stopPlayback()
        androidTts?.stop()
        _state.value = _state.value.copy(assistantState = AssistantState.IDLE, audioAmplitude = 0f)
    }

    private suspend fun callGroq(transcript: String, langName: String): String {
        return try {
            val systemPrompt = """You are Vince, a helpful AI assistant for rural and semi-urban Indian users.
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
                _state.value = _state.value.copy(assistantState = AssistantState.IDLE, audioAmplitude = 0f)
            }
            @Deprecated("Deprecated") override fun onError(utteranceId: String?) {
                _state.value = _state.value.copy(assistantState = AssistantState.IDLE, audioAmplitude = 0f)
            }
        })
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "saathi_tts")
    }

    override fun onCleared() {
        silenceJob?.cancel()
        audioHelper.stopPlayback()
        androidTts?.shutdown()
        super.onCleared()
    }
}
