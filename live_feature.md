# WORKING.md — Screen Share / Screen Context Feature
## Saathi Android App — com.sohanreddy.sevak

---

## WHAT THIS IS

A screen-aware voice query feature for Saathi. The user taps a dedicated button,
the app captures a screenshot of whatever is currently on screen, encodes it,
sends it alongside the user's voice query to a vision-capable LLM, and speaks
the response back. The user can point Saathi at any screen — a government form,
a banking app, a hospital bill, a WhatsApp message — and ask "yeh kya hai?" or
"mujhe kya karna chahiye?" and get a spoken answer in their language.

This is NOT continuous screen streaming like Gemini Live. It is snapshot-on-tap.
One screenshot per query. Simpler, cheaper, faster, and sufficient for the use case.

---

## HOW GEMINI LIVE SCREEN SHARE WORKS (reference)

Gemini Live uses:
- Android MediaProjection API to create a virtual display mirror of the screen
- ImageReader attached to the virtual display surface to capture frames as bitmaps
- Frames encoded to base64 and streamed continuously via WebSocket to Gemini Live API
- Gemini Live API maintains a persistent bidirectional WebSocket session
- Audio and image frames are multiplexed on the same connection

For Saathi, continuous streaming is unnecessary and expensive. A single snapshot
per voice query gives the model full visual context at a fraction of the complexity.

---

## ARCHITECTURE

```
User taps screen share button
  → Request MediaProjection permission (one-time system dialog)
  → Capture single screenshot via ImageReader → Bitmap
  → Compress Bitmap to JPEG at 60% quality → ByteArray
  → Encode ByteArray to Base64 string
  → User taps orb → speaks query → Sarvam STT → transcript
  → Build Groq vision request: text = transcript, image = base64 JPEG
  → Send to Groq vision endpoint
  → Get text response → Sarvam TTS → speak response
  → Store full turn (query + response + screenshot flag) in RAG pipeline
```

---

## MODEL TO USE

Groq Vision endpoint: https://api.groq.com/openai/v1/chat/completions
Model: meta-llama/llama-4-scout-17b-16e-instruct

This model accepts image_url with base64 data URIs in the message content array.
Same endpoint as your existing Groq text calls, just with an additional image
content block alongside the text block. No new API key needed.

Request body structure for vision:
- messages array contains one user message
- content is an array with two blocks:
  Block 1: type=text, text=transcript + system prompt
  Block 2: type=image_url, image_url.url = "data:image/jpeg;base64,BASE64_STRING"

---

## NEW FILES TO CREATE

### ScreenCaptureManager.kt
Single responsibility: capture one screenshot and return a Bitmap.

- Holds reference to MediaProjection instance
- Creates ImageReader with screen dimensions and PixelFormat.RGBA_8888
- Creates VirtualDisplay via mediaProjection.createVirtualDisplay() pointed at ImageReader surface
- On captureScreen() call: acquires latest image from ImageReader, converts to Bitmap,
  releases image, stops VirtualDisplay
- Returns Bitmap nullable — returns null if capture fails
- Must run on Dispatchers.IO
- Singleton with init(mediaProjection: MediaProjection) method

### ScreenCaptureService.kt (Foreground Service)
Android 10+ requires MediaProjection to run in a foreground service.

- Extends Service
- Shows a minimal persistent notification while screen capture is active
- Receives MediaProjection token via Intent extras
- Initializes ScreenCaptureManager with the token
- Exposes a bound service interface so ViewModel can call captureScreen()
- Stops itself when user exits screen mode

### ImageEncoder.kt
Converts Bitmap to base64 string for the API.

- fun encode(bitmap: Bitmap, quality: Int = 60): String
- Compress bitmap to JPEG at 60% quality
- Encode ByteArray to Base64.NO_WRAP string
- Return "data:image/jpeg;base64," prepended to encoded string
- Target output size: under 500KB per screenshot
- If bitmap dimensions exceed 1280px on either side: scale down maintaining aspect ratio

### ScreenQueryRepository.kt
Handles the Groq vision API call.

- suspend fun queryWithScreenContext(transcript, base64Image, language, ragContext): String
- Builds the vision request body with both text and image content blocks
- System prompt same as existing GroqRepository but with addition:
  "The user has shared their screen. Analyze what is visible and answer their question.
  Describe what you see in simple terms first, then answer the question.
  Focus on any text, numbers, or important information visible on screen."
- Returns response text string
- Error handling: if vision call fails, fall back to text-only GroqRepository call

---

## PERMISSIONS REQUIRED

Add to AndroidManifest.xml:
- android.permission.FOREGROUND_SERVICE
- android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION

Add foreground service declaration with foregroundServiceType="mediaProjection"

No new dangerous permissions. MediaProjection uses its own system dialog.

---

## UI CHANGES (minimal)

One new element on MainScreen only: a small screen icon button in the bottom left corner.

States:
- DEFAULT: screen icon, tapping triggers MediaProjection permission request
- ACTIVE: screen icon with colored dot, tapping again deactivates screen mode

When screen mode is ACTIVE:
- Orb behavior identical — user taps orb, speaks, gets response
- Query goes to ScreenQueryRepository instead of GroqRepository
- Subtle dot indicator shows screen mode is on
- No other UI changes

---

## FULL FLOW STEP BY STEP

1. User taps screen icon button
2. System shows MediaProjection permission dialog
3. User taps "Start now"
4. ScreenCaptureService starts as foreground service, notification appears
5. Screen mode indicator becomes active on MainScreen
6. User navigates to whatever screen they want help with
7. User taps orb and speaks their question
8. Sarvam STT transcribes speech
9. ScreenCaptureManager captures one screenshot at the moment STT returns transcript
10. ImageEncoder compresses and base64 encodes the screenshot
11. RAG pipeline runs as normal
12. ScreenQueryRepository sends transcript + screenshot + RAG context to Groq vision model
13. Response text returned
14. Sarvam TTS speaks response
15. Turn stored in RAG with screenshotUsed=true flag
16. Orb returns to idle

---

## MODIFICATIONS TO EXISTING FILES

### MainViewModel.kt
- Add screenModeActive: StateFlow<Boolean>
- Add fun activateScreenMode(mediaProjectionIntent: Intent)
- Add fun deactivateScreenMode()
- In processVoiceQuery(): if screenModeActive is true, use ScreenQueryRepository path
  otherwise use existing GroqRepository path

### MainScreen.kt
- Add one IconButton bottom left
- On click: launch MediaProjection permission intent via ActivityResultLauncher
- Show active dot indicator when screenModeActive is true
- Nothing else changes

### GroqRepository.kt
- No changes whatsoever

---

## SCREENSHOT TIMING

Screenshot captured at the moment STT returns the transcript, not when orb is tapped.
This captures whatever the user is looking at when they finish asking the question.

---

## SIZE AND PERFORMANCE

- Raw 1080p screenshot: ~8MB uncompressed
- After JPEG 60% compression + 1280px resize: ~150-300KB
- Base64 encoded: ~200-400KB
- Groq accepts up to 4MB base64 — well within limits
- Additional latency vs text-only: under 500ms total
- Groq vision response time: 2-4 seconds vs 1-2 for text-only

---

## WHAT NOT TO BUILD

- No continuous streaming or video
- No screenshot storage on device or any server
- No screenshot visible in the UI at any point
- No WebSocket connection — standard REST same as existing Groq calls
- No image stored in RAG — only the text response is stored

---

## USE CASES THIS UNLOCKS

- User opens a government e-form they cannot read — asks "yeh form mein kya bharna hai?"
- User receives a bank statement — asks "kitna paisa gaya?"
- User sees a hospital bill — asks "yeh sahi hai kya?"
- User receives a WhatsApp message they cannot read — screenshots it — asks Saathi to explain
- User sees a government notice on a wall — photographs it — asks what action to take

---

## DEPENDENCIES

No new external dependencies.
MediaProjection, ImageReader, Base64 — all standard Android SDK.
Only AndroidManifest.xml changes needed.

---

## FOREGROUND SERVICE NOTIFICATION

Required by Android while ScreenCaptureService is running:
- Title: app name
- Text: "Screen mode is active" in selected language
- Channel importance: IMPORTANCE_LOW (no sound, no popup)
- Add Stop action button

---

## PRIVACY STATEMENT FOR DEMO

Screenshots are sent only to Groq for that single query and never stored anywhere.
RAG pipeline stores only the text of the conversation, never the screenshot.
Tell judges this explicitly — they will ask.