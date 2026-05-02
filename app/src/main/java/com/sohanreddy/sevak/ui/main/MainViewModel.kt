package com.sohanreddy.sevak.ui.main

import android.app.Application
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.sohanreddy.sevak.Constants
import com.sohanreddy.sevak.audio.AudioHelper
import com.sohanreddy.sevak.data.PrefsManager
import com.sohanreddy.sevak.data.getLanguageByCode
import com.sohanreddy.sevak.data.getLanguageBySarvamCode
import com.sohanreddy.sevak.data.rag.ContextBuilder
import com.sohanreddy.sevak.data.rag.EmbeddingManager
import com.sohanreddy.sevak.data.rag.VectorStoreManager
import com.sohanreddy.sevak.network.*
import com.sohanreddy.sevak.screenshare.ScreenShareSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.UUID

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

    // RAG session ID — unique per app session
    private val sessionId: String = UUID.randomUUID().toString()

    // Silence detection
    private val silenceThreshold = 0.015f
    // Voice activity + turn-end detection tuned for noisy mobile mic input.
    private val silenceTimeoutMs = 1400L
    private val noSpeechTimeoutMs = 5000L
    private val maxListeningDurationMs = 12000L
    private var silenceJob: Job? = null
    private var restartListeningJob: Job? = null
    private var processingJob: Job? = null
    private var listeningStartedAt = 0L
    private var lastSpeechTimestamp = 0L
    private var hasDetectedSpeech = false
    private var ambientNoiseFloor = 0.008f
    private var liveModeEnabled = false

    init {
        androidTts = TextToSpeech(application) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        // Wire up amplitude callback
        audioHelper.onAmplitudeUpdate = { rawAmplitude ->
            val amplitude = rawAmplitude.coerceIn(0f, 1f)

            // Boost visual motion so low-volume speech still animates clearly.
            _state.update { it.copy(audioAmplitude = (amplitude * 3.2f).coerceIn(0f, 1f)) }

            // Adaptive VAD to avoid treating ambient hiss as speech forever.
            if (_state.value.assistantState == AssistantState.LISTENING) {
                val now = System.currentTimeMillis()

                if (!hasDetectedSpeech) {
                    ambientNoiseFloor = (ambientNoiseFloor * 0.9f) + (amplitude * 0.1f)
                }

                val speechThreshold = maxOf(0.03f, ambientNoiseFloor * 2.8f)
                val holdThreshold = maxOf(0.018f, ambientNoiseFloor * 1.8f)

                if (amplitude >= speechThreshold) {
                    if (!hasDetectedSpeech) {
                        Log.d("MainVM", "Speech detected. floor=$ambientNoiseFloor amp=$amplitude")
                    }
                    hasDetectedSpeech = true
                    lastSpeechTimestamp = now
                } else {
                    if (hasDetectedSpeech && amplitude >= holdThreshold) {
                        lastSpeechTimestamp = now
                    } else if (amplitude < holdThreshold) {
                        ambientNoiseFloor = (ambientNoiseFloor * 0.96f) + (amplitude * 0.04f)
                    }
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
        if (!liveModeEnabled) {
            enableLiveModeIfNeeded()
        } else {
            disableLiveModeIfEnabled()
        }
    }

    fun enableLiveModeIfNeeded() {
        if (liveModeEnabled) return
        liveModeEnabled = true
        Log.d("MainVM", "Live mode enabled")
        if (_state.value.assistantState == AssistantState.IDLE) {
            startListening()
        }
    }

    fun disableLiveModeIfEnabled() {
        if (!liveModeEnabled && _state.value.assistantState == AssistantState.IDLE) return
        Log.d("MainVM", "Live mode disabled")
        stopLiveMode()
    }

    private fun stopLiveMode() {
        liveModeEnabled = false
        silenceJob?.cancel()
        restartListeningJob?.cancel()
        processingJob?.cancel()

        when (_state.value.assistantState) {
            AssistantState.LISTENING -> audioHelper.cancelRecording()
            AssistantState.SPEAKING -> stopSpeaking()
            else -> {}
        }

        _state.value = _state.value.copy(
            assistantState = AssistantState.IDLE,
            audioAmplitude = 0f,
            error = null
        )
    }

    private fun scheduleListeningRestart(delayMs: Long = 350L) {
        if (!liveModeEnabled) return
        restartListeningJob?.cancel()
        restartListeningJob = viewModelScope.launch {
            delay(delayMs)
            if (liveModeEnabled && _state.value.assistantState == AssistantState.IDLE) {
                startListening()
            }
        }
    }

    private fun startListening() {
        if (_state.value.assistantState != AssistantState.IDLE) return
        if (!audioHelper.hasPermission()) {
            Log.w("MainVM", "No audio permission")
            return
        }
        restartListeningJob?.cancel()
        Log.d("MainVM", "Starting recording...")
        _state.value = _state.value.copy(assistantState = AssistantState.LISTENING, error = null, audioAmplitude = 0f)
        val now = System.currentTimeMillis()
        listeningStartedAt = now
        lastSpeechTimestamp = now
        hasDetectedSpeech = false
        ambientNoiseFloor = 0.008f
        audioHelper.startRecording()

        // Start silence monitoring
        silenceJob?.cancel()
        silenceJob = viewModelScope.launch {
            // Small warm-up period to collect a stable noise floor.
            delay(500)
            while (_state.value.assistantState == AssistantState.LISTENING) {
                val nowTick = System.currentTimeMillis()
                val listeningFor = nowTick - listeningStartedAt

                if (hasDetectedSpeech) {
                    val silenceFor = nowTick - lastSpeechTimestamp
                    if (silenceFor >= silenceTimeoutMs) {
                        Log.d("MainVM", "Turn ended by silence (${silenceFor}ms)")
                        stopListeningAndProcess()
                        break
                    }
                } else if (listeningFor >= noSpeechTimeoutMs) {
                    Log.d("MainVM", "No speech detected in ${listeningFor}ms, restarting listen loop")
                    stopListeningWithoutProcessing("no-speech-timeout")
                    break
                }

                if (listeningFor >= maxListeningDurationMs) {
                    Log.d("MainVM", "Max listen duration reached (${listeningFor}ms), processing current audio")
                    stopListeningAndProcess()
                    break
                }
                delay(100)
            }
        }
    }

    private fun stopListeningWithoutProcessing(reason: String) {
        silenceJob?.cancel()
        Log.d("MainVM", "Stopping recording without processing: $reason")
        audioHelper.cancelRecording()
        _state.value = _state.value.copy(assistantState = AssistantState.IDLE, audioAmplitude = 0f)
        scheduleListeningRestart(250)
    }

    private fun stopListeningAndProcess() {
        silenceJob?.cancel()
        Log.d("MainVM", "Stopping recording...")
        _state.value = _state.value.copy(assistantState = AssistantState.PROCESSING, audioAmplitude = 0f)
        val wavFile = audioHelper.stopRecording()
        if (wavFile == null || !wavFile.exists()) {
            Log.e("MainVM", "Recording failed — no wav file")
            _state.value = _state.value.copy(assistantState = AssistantState.IDLE, lastResponse = "Recording failed. Please try again.")
            scheduleListeningRestart()
            return
        }
        Log.d("MainVM", "WAV file: ${wavFile.absolutePath}, size: ${wavFile.length()} bytes")

        processingJob?.cancel()
        processingJob = viewModelScope.launch(Dispatchers.IO) {
            var transcript: String? = null
            var detectedSarvamCode: String? = null
            var sttErrorMessage: String? = null

            if (Constants.SARVAM_API_KEY.isBlank()) {
                Log.e("MainVM", "Sarvam API key missing. Set SARVAM_API_KEY in .env or local.properties")
                _state.value = _state.value.copy(
                    assistantState = AssistantState.IDLE,
                    lastResponse = "Voice service is not configured. Please set SARVAM_API_KEY."
                )
                scheduleListeningRestart()
                return@launch
            }

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
            } catch (e: retrofit2.HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                Log.e("MainVM", "Sarvam STT HTTP ${e.code()}: $errorBody", e)
                sttErrorMessage = if (e.code() == 403) {
                    "Voice service denied access. Verify your Sarvam API key and credits."
                } else {
                    "Could not understand. Please try again."
                }
            } catch (e: Exception) {
                Log.e("MainVM", "Sarvam STT failed: ${e.message}", e)
                sttErrorMessage = "Could not understand. Please try again."
            }

            if (transcript.isNullOrBlank()) {
                Log.w("MainVM", "No transcript, showing error")
                _state.value = _state.value.copy(
                    assistantState = AssistantState.IDLE,
                    lastResponse = sttErrorMessage ?: "Could not understand. Please try again."
                )
                scheduleListeningRestart()
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

            // --- RAG PIPELINE: Retrieve context ---
            val userId = FirebaseAuth.getInstance().currentUser?.uid ?: "anonymous"
            var ragContext = ""
            if (EmbeddingManager.isReady.value) {
                try {
                    Log.d("MainVM", "RAG: Retrieving context for query...")
                    val relevantChunks = VectorStoreManager.retrieveAndRerank(userId, transcript)
                    ragContext = ContextBuilder.buildContext(relevantChunks)
                    Log.d("MainVM", "RAG: Got ${relevantChunks.size} chunks, context length: ${ragContext.length}")
                } catch (e: Exception) {
                    Log.e("MainVM", "RAG retrieval failed, proceeding without context: ${e.message}")
                }
            } else {
                Log.d("MainVM", "RAG: EmbeddingManager not ready, skipping context retrieval")
            }

            // Call Groq LLM with RAG context
            val langName = resolvedLang.englishName
            val screenshotDataUrl = if (shouldAttachScreenSnapshot(transcript)) {
                ScreenShareSessionManager.latestScreenshotDataUrl()
            } else {
                null
            }
            if (ScreenShareSessionManager.isActive.value) {
                Log.d(
                    "MainVM",
                    "Live screen mode: screenshotAttached=${!screenshotDataUrl.isNullOrBlank()} transcript=$transcript"
                )
            }
            Log.d("MainVM", "Calling Groq with transcript: $transcript, lang: $langName")
            val responseText = callGroq(transcript, langName, ragContext, screenshotDataUrl)
            Log.d("MainVM", "Groq response: $responseText")

            _state.value = _state.value.copy(lastResponse = responseText)

            // --- RAG PIPELINE: Store conversation turn ---
            if (EmbeddingManager.isReady.value) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        VectorStoreManager.storeConversationTurn(userId, sessionId, transcript, responseText)
                        Log.d("MainVM", "RAG: Stored conversation turn")
                    } catch (e: Exception) {
                        Log.e("MainVM", "RAG: Failed to store turn: ${e.message}")
                    }
                }
            }

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
                        scheduleListeningRestart()
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

    private suspend fun callGroq(
        transcript: String,
        langName: String,
        ragContext: String = "",
        screenshotDataUrl: String? = null
    ): String {
        val liveScreenMode = ScreenShareSessionManager.isActive.value
        val hasScreenshot = !screenshotDataUrl.isNullOrBlank()

        val systemPrompt = buildString {
            append("You are Saathi, a helpful AI assistant for rural and semi-urban Indian users.\n")
            append("The user speaks $langName. Always respond in $langName only.\n")
            append("Use extremely simple words. Speak like a helpful neighbor, not a government form.\n")
            append("Keep responses under 3 sentences. Be direct and actionable.\n")
            append("If asked about government schemes, give eligibility in one line and the single most important next step. Never use English if the selected language is not English.")

            if (liveScreenMode) {
                append("\n\nYou are currently in LIVE SCREEN ASSIST mode.")
                append("\nIf a screenshot is attached, treat it as the user's current phone screen.")
                append("\nNever claim you cannot see the screen when a screenshot is attached.")
                append("\nWhen user asks how to do something on the phone, give clear step-by-step tap instructions using visible UI labels.")
                append("\nIf an exact button is not visible in screenshot, clearly say what is missing and ask for one focused follow-up screenshot.")

                if (!hasScreenshot) {
                    append("\nNo screenshot is attached for this turn. Mention this briefly and ask the user to keep the relevant screen open and repeat once.")
                }
            }

            if (ragContext.isNotBlank()) {
                append("\n\nPrevious conversation context (use this to remember what the user told you):\n")
                append(ragContext)
            }
        }

        val baseMessages = mutableListOf(groqTextMessage("system", systemPrompt))

        if (!screenshotDataUrl.isNullOrBlank()) {
            val visionRequest = GroqRequest(
                model = "meta-llama/llama-4-scout-17b-16e-instruct",
                messages = baseMessages + groqVisionMessage("user", transcript, screenshotDataUrl)
            )
            try {
                val response = GroqApi.chat(request = visionRequest)
                val content = response.choices.firstOrNull()?.message?.content
                if (!content.isNullOrBlank()) {
                    return content
                }
            } catch (e: Exception) {
                Log.w("MainVM", "Vision request failed, retrying text-only: ${e.message}")
            }
        }

        return try {
            val textRequest = GroqRequest(
                model = "llama-3.3-70b-versatile",
                messages = baseMessages + groqTextMessage("user", transcript)
            )
            val response = GroqApi.chat(request = textRequest)
            response.choices.firstOrNull()?.message?.content ?: "Something went wrong, please try again"
        } catch (e: Exception) {
            Log.e("MainVM", "Groq failed: ${e.message}", e)
            "Something went wrong, please try again"
        }
    }

    private fun shouldAttachScreenSnapshot(transcript: String): Boolean {
        if (!ScreenShareSessionManager.isActive.value) return false

        // Language-specific keyword gating caused missed screenshot attachment for non-English speech.
        // In live screen mode, attach screenshot for every non-empty user turn so screen guidance works
        // across all supported languages and transliterations.
        return transcript.isNotBlank()
    }

    private fun fallbackTts(text: String, langCode: String) {
        if (!ttsReady) {
            Log.w("MainVM", "Android TTS not ready")
            _state.value = _state.value.copy(assistantState = AssistantState.IDLE)
            scheduleListeningRestart()
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
                scheduleListeningRestart()
            }
            @Deprecated("Deprecated") override fun onError(utteranceId: String?) {
                _state.value = _state.value.copy(assistantState = AssistantState.IDLE, audioAmplitude = 0f)
                scheduleListeningRestart()
            }
        })
        androidTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "saathi_tts")
    }

    override fun onCleared() {
        silenceJob?.cancel()
        restartListeningJob?.cancel()
        processingJob?.cancel()
        audioHelper.cancelRecording()
        audioHelper.stopPlayback()
        androidTts?.shutdown()
        super.onCleared()
    }
}
