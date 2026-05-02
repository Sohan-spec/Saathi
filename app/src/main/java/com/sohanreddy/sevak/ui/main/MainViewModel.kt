package com.sohanreddy.sevak.ui.main

import android.app.Application
import android.content.Intent
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
import com.sohanreddy.sevak.screen.ImageEncoder
import com.sohanreddy.sevak.screen.ScreenCaptureManager
import com.sohanreddy.sevak.screen.ScreenCaptureService
import com.sohanreddy.sevak.screen.ScreenQueryRepository
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
import java.util.concurrent.atomic.AtomicBoolean

enum class AssistantState { IDLE, LISTENING, PROCESSING, SPEAKING }

data class MainScreenState(
    val assistantState: AssistantState = AssistantState.IDLE,
    val lastResponse: String = "",
    val error: String? = null,
    val detectedLangCode: String? = null,
    val audioAmplitude: Float = 0f,
    val screenModeActive: Boolean = false
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

    // Voice activity + turn-end detection tuned for noisy mobile mic input
    private val silenceTimeoutMs = 1500L
    private val noSpeechTimeoutMs = 5000L
    private val maxListeningDurationMs = 12000L
    private var restartListeningJob: Job? = null
    private var processingJob: Job? = null
    private var listeningStartedAt = 0L
    @Volatile private var lastSpeechTimestamp = 0L
    @Volatile private var hasDetectedSpeech = false
    @Volatile private var ambientNoiseFloor = 0.008f
    @Volatile private var liveModeEnabled = false

    // Atomic flag to prevent multiple simultaneous stopListeningAndProcess calls
    // from the recording thread (amplitude callback fires frequently)
    private val processingTriggered = AtomicBoolean(false)

    init {
        androidTts = TextToSpeech(application) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }

        // Wire up amplitude callback — THIS RUNS ON THE RECORDING THREAD
        // Silence detection is done directly here so it works even when the
        // app is backgrounded (main dispatcher gets throttled in background).
        audioHelper.onAmplitudeUpdate = { rawAmplitude ->
            val amplitude = rawAmplitude.coerceIn(0f, 1f)

            // Boost visual motion so low-volume speech still animates clearly.
            _state.update { it.copy(audioAmplitude = (amplitude * 3.2f).coerceIn(0f, 1f)) }

            // Only run VAD when actively listening
            if (_state.value.assistantState == AssistantState.LISTENING) {
                val now = System.currentTimeMillis()
                val listeningFor = now - listeningStartedAt

                // Noise floor adaptation (only before speech detected)
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

                // ── SILENCE DETECTION — runs on recording thread, works in background ──
                // Skip during warm-up period (first 500ms)
                if (listeningFor > 500) {
                    if (hasDetectedSpeech) {
                        val silenceFor = now - lastSpeechTimestamp
                        if (silenceFor >= silenceTimeoutMs) {
                            // Silence after speech → process audio
                            if (processingTriggered.compareAndSet(false, true)) {
                                Log.d("MainVM", "BG: Turn ended by silence (${silenceFor}ms)")
                                viewModelScope.launch(Dispatchers.Main.immediate) {
                                    stopListeningAndProcess()
                                }
                            }
                        }
                    } else if (listeningFor >= noSpeechTimeoutMs) {
                        // No speech detected at all → restart listening
                        if (processingTriggered.compareAndSet(false, true)) {
                            Log.d("MainVM", "BG: No speech detected in ${listeningFor}ms")
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                stopListeningWithoutProcessing("no-speech-timeout")
                            }
                        }
                    }

                    // Hard max duration
                    if (listeningFor >= maxListeningDurationMs) {
                        if (processingTriggered.compareAndSet(false, true)) {
                            Log.d("MainVM", "BG: Max listen duration reached (${listeningFor}ms)")
                            viewModelScope.launch(Dispatchers.Main.immediate) {
                                stopListeningAndProcess()
                            }
                        }
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

    // ── Screen Mode ─────────────────────────────────────────────────

    /**
     * Activate screen mode: start the foreground service and enable screen capture.
     * Called from MainScreen after the user grants MediaProjection permission.
     * Waits 2 seconds so user can switch to the target app, then starts listening.
     */
    fun activateScreenMode(resultCode: Int, data: Intent) {
        Log.d("MainVM", "activateScreenMode called, resultCode=$resultCode")
        val context = getApplication<Application>()
        try {
            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(serviceIntent)
            Log.d("MainVM", "✓ Foreground service start requested")
        } catch (e: Exception) {
            Log.e("MainVM", "✗ Failed to start foreground service: ${e.message}", e)
            return
        }

        _state.update { it.copy(screenModeActive = true) }
        Log.d("MainVM", "Screen mode activated — waiting 2s for user to switch apps")

        // Auto-start live listening
        if (!liveModeEnabled) {
            liveModeEnabled = true
            Log.d("MainVM", "Auto-enabling live mode for screen capture")
        }

        // Wait 2 seconds so user can switch to target app, then start listening
        viewModelScope.launch {
            delay(2000)
            Log.d("MainVM", "2s delay done. CaptureReady=${ScreenCaptureManager.isReady()}, state=${_state.value.assistantState}")
            if (_state.value.assistantState == AssistantState.IDLE && audioHelper.hasPermission()) {
                Log.d("MainVM", "Starting to listen...")
                startListening()
            } else {
                Log.w("MainVM", "Cannot start listening: state=${_state.value.assistantState}, audioPerm=${audioHelper.hasPermission()}")
            }
        }
    }

    /** Deactivate screen mode and stop the foreground service. */
    fun deactivateScreenMode() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, ScreenCaptureService::class.java))
        _state.update { it.copy(screenModeActive = false) }
        Log.d("MainVM", "Screen mode deactivated")
    }

    // ── Mic / Live Mode ─────────────────────────────────────────────

    fun onMicTap() {
        if (!liveModeEnabled) {
            liveModeEnabled = true
            Log.d("MainVM", "Live mode enabled")
            if (_state.value.assistantState == AssistantState.IDLE) {
                startListening()
            }
            return
        }

        Log.d("MainVM", "Live mode disabled")
        stopLiveMode()
    }

    private fun stopLiveMode() {
        liveModeEnabled = false
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
        processingTriggered.set(false) // Reset so amplitude callback can trigger processing
        audioHelper.startRecording()
        // Silence detection is handled in the amplitude callback — no coroutine needed.
        // This works even when the app is in the background.
    }

    private fun stopListeningWithoutProcessing(reason: String) {
        Log.d("MainVM", "Stopping recording without processing: $reason")
        audioHelper.cancelRecording()
        _state.value = _state.value.copy(assistantState = AssistantState.IDLE, audioAmplitude = 0f)
        scheduleListeningRestart(250)
    }

    private fun stopListeningAndProcess() {
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
            val resolvedSarvamCode: String
            val resolvedLang: com.sohanreddy.sevak.data.Language

            if (savedLang != null) {
                resolvedSarvamCode = savedLang.sarvamCode
                resolvedLang = savedLang
            } else if (!detectedSarvamCode.isNullOrBlank()) {
                val detected = getLanguageBySarvamCode(detectedSarvamCode)
                if (detected != null) {
                    resolvedSarvamCode = detected.sarvamCode
                    resolvedLang = detected
                    prefs.saveLanguage(detected.code, detected.englishName)
                    Log.d("MainVM", "Auto-detected and saved language: ${detected.code} (${detected.englishName})")
                } else {
                    resolvedSarvamCode = "hi-IN"
                    resolvedLang = getLanguageBySarvamCode("hi-IN")!!
                    prefs.saveLanguage("hi", "Hindi")
                    Log.d("MainVM", "Unsupported detected language $detectedSarvamCode, defaulting to hi-IN")
                }
            } else {
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

            // --- Call LLM: screen mode vs normal mode ---
            val langName = resolvedLang.englishName
            val isScreenMode = _state.value.screenModeActive && ScreenCaptureManager.isReady()
            val responseText: String

            if (isScreenMode) {
                // Screen mode: capture screenshot, encode, send to vision model
                Log.d("MainVM", "══════ SCREEN MODE PIPELINE ══════")
                Log.d("MainVM", "ScreenCaptureManager.isReady() = ${ScreenCaptureManager.isReady()}")
                Log.d("MainVM", "Capturing screenshot now...")

                val bitmap = ScreenCaptureManager.captureScreen()

                if (bitmap != null) {
                    Log.d("MainVM", "✓ Screenshot captured: ${bitmap.width}x${bitmap.height}")
                    val base64Image = ImageEncoder.encode(bitmap)
                    Log.d("MainVM", "✓ Screenshot encoded to base64, length: ${base64Image.length} chars")
                    Log.d("MainVM", "Sending vision query to Groq (model: llama-4-scout)...")
                    responseText = ScreenQueryRepository.queryWithScreenContext(
                        transcript = transcript,
                        base64Image = base64Image,
                        langName = langName,
                        ragContext = ragContext
                    )
                    Log.d("MainVM", "✓ Vision response received: ${responseText.take(100)}...")
                    bitmap.recycle()
                } else {
                    Log.w("MainVM", "✗ Screenshot capture FAILED, falling back to text-only")
                    responseText = callGroq(transcript, langName, ragContext)
                }
                Log.d("MainVM", "══════ END SCREEN MODE ══════")
            } else {
                // Normal mode: text-only Groq call
                Log.d("MainVM", "Normal mode: Calling Groq with transcript: $transcript, lang: $langName")
                responseText = callGroq(transcript, langName, ragContext)
            }

            Log.d("MainVM", "LLM response: $responseText")
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

    private suspend fun callGroq(transcript: String, langName: String, ragContext: String = ""): String {
        return try {
            val systemPrompt = buildString {
                append("You are Saathi, a helpful AI assistant for rural and semi-urban Indian users.\n")
                append("The user speaks $langName. Always respond in $langName only.\n")
                append("Use extremely simple words. Speak like a helpful neighbor, not a government form.\n")
                append("Keep responses under 3 sentences. Be direct and actionable.\n")
                append("If asked about government schemes, give eligibility in one line and the single most important next step. Never use English if the selected language is not English.")
                if (ragContext.isNotBlank()) {
                    append("\n\nPrevious conversation context (use this to remember what the user told you):\n")
                    append(ragContext)
                }
            }

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
        restartListeningJob?.cancel()
        processingJob?.cancel()
        audioHelper.cancelRecording()
        audioHelper.stopPlayback()
        androidTts?.shutdown()
        // Clean up screen mode if active
        if (_state.value.screenModeActive) {
            deactivateScreenMode()
        }
        super.onCleared()
    }
}
