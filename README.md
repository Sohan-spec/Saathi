# Saathi - AI Voice Assistant
 
**Voice in. Voice out. No reading. No typing. No barriers.**
 
---
 
## What is Saathi?
 
Saathi (meaning companion in Hindi) is an AI-first voice assistant built for the 300+ million Indians who own a smartphone but struggle with text-heavy, English-heavy, cognitively demanding apps.
 
The entire experience is one screen and one orb. Tap it, speak in your language, hear the answer. No menus. No typing. No reading. Everything happens through voice, and when a visual helps more than words, the app generates and shows one.
 
Built for low-literacy and semi-digital users across rural and semi-urban India. Supports 8 Indian languages natively.
 
---
 
## Key Features
 
- One-tap voice interaction - tap the orb, speak, hear the answer
- 8 Indian languages with auto-detection, no manual selection needed
- Persistent memory via on-device RAG pipeline that remembers past conversations
- Intelligent image generation - Llama decides when a visual helps and generates one silently
- **Live Screen Assist** - Share your screen with Saathi via a floating orb overlay to ask contextual questions about what you are seeing. Powered by `meta-llama/llama-4-scout-17b-16e-instruct` vision model.
- Phone authentication via Firebase OTP, no passwords
- TTS offline fallback - falls back to on-device Android APIs if network fails
- Zero text interface - the user never needs to read or type anything
 
---
 
## Supported Languages
 
| Language | STT Code | TTS Code |
|----------|----------|----------|
| Hindi    | hi-IN    | hi-IN    |
| Kannada  | kn-IN    | kn-IN    |
| Tamil    | ta-IN    | ta-IN    |
| Telugu   | te-IN    | te-IN    |
| English  | en-IN    | en-IN    |
| Marathi  | mr-IN    | mr-IN    |
| Bengali  | bn-IN    | bn-IN    |
| Gujarati | gu-IN    | gu-IN    |
 
---
 
## AI Stack
 
| Layer | Technology |
|-------|-----------|
| Speech to Text | Sarvam Saaras v3 |
| Text LLM | Groq - Llama 3.3 70B Versatile |
| Vision LLM | Groq - Llama 4 Scout 17B 16E Instruct |
| Text to Speech | Sarvam Bulbul v3 |
| Image Generation | Hugging Face - FLUX.1-schnell |
| On-device RAG | ObjectBox + ONNX Runtime (all-MiniLM-L6-v2) |
| Authentication | Firebase Phone Auth |
| TTS Fallback | Android TextToSpeech |
 
---
 
## Tech Stack
 
- Language: Kotlin
- UI: Jetpack Compose + Material3
- Architecture: Single Activity, MVVM, StateFlow
- Networking: Retrofit + OkHttp + Gson
- Local Storage: ObjectBox (vector DB), SharedPreferences
- On-device ML: ONNX Runtime Android
- Screen Capture: Android MediaProjection API
- Auth: Firebase Authentication (Phone)
- Audio: Android AudioRecord + MediaPlayer
 
---
 
## Project Structure
 
```text
com.sohanreddy.sevak/
├── audio/            AudioRecord handling & TTS playback
├── data/             Prefs, Language enum, ObjectBox RAG logic
├── navigation/       NavHost for Phone -> OTP -> Main
├── network/          Retrofit APIs for Groq, Sarvam, Hugging Face
├── screenshare/      MediaProjection, Foreground Service, Floating Voice Bubble
├── ui/
│   ├── auth/         PhoneAuthScreen, OtpScreen, ViewModels
│   ├── language/     LanguagePickerScreen
│   ├── main/         MainScreen, MainViewModel, WaveformCanvas
│   └── theme/        Material3 Theme setup
├── MainActivity.kt
├── SaathiApplication.kt
└── Constants.kt
```
 
---
 
## Getting Started
 
### Prerequisites
 
- Android Studio (latest stable)
- JDK 17+ (Android Studio bundled JDK is fine)
- Android device or emulator running API 26+
- Git
 
### Clone the Repository
 
```bash
git clone https://github.com/Sohan-spec/Saathi---Everyday-Helper.git
cd Saathi---Everyday-Helper
git checkout assisted-workflow
```
 
### Configuration
 
This app requires API keys to function. Create a `local.properties` file in the root of the project and add the following:
 
```properties
GROQ_API_KEY=your_groq_api_key_here
SARVAM_API_KEY=your_sarvam_api_key_here
HF_TOKEN=your_huggingface_api_key_here
```
 
> local.properties is gitignored by default. Never commit your API keys to version control.
 
#### Where to get your keys
 
| Key | Source | Cost |
|-----|--------|------|
| GROQ_API_KEY | [console.groq.com](https://console.groq.com) | Free, no credit card |
| SARVAM_API_KEY | [dashboard.sarvam.ai](https://dashboard.sarvam.ai) | Free, Rs 1000 credits on signup |
| HF_TOKEN | [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens) | Free, enable "Make calls to Inference Providers" |
 
#### Firebase Setup
 
1. Go to [Firebase Console](https://console.firebase.google.com)
2. Select your project or create one
3. Enable Phone Authentication under Authentication - Sign-in methods
4. Download `google-services.json` and place it in `app/`
5. Add your debug SHA-1 and SHA-256 fingerprints under Project Settings - Your Android App
```bash
./gradlew signingReport
```
6. Enable App Check under your Firebase project and register your app with Play Integrity
 
#### ONNX Model Setup
 
The on-device embedding model must be added manually:
 
1. Download `model.onnx` and `tokenizer.json` from [all-MiniLM-L6-v2 on HuggingFace](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/main/onnx)
2. Place both files in `app/src/main/assets/`
 
### Run the App
 
1. Open the project in Android Studio
2. Let Gradle sync complete
3. Select your device or emulator
4. Press Run
 
For testing Firebase Phone Auth on an emulator, add a test phone number in Firebase Console - Authentication - Sign-in methods - Phone - Phone numbers for testing. Use `+919999999999` with OTP `123456`.
 
---
 
## Build
 
```bash
# Debug build
./gradlew assembleDebug
 
# Release build (requires signing config)
./gradlew assembleRelease
```
 
APKs are output to `app/build/outputs/apk/`.
 
---
 
## Testing
 
```bash
# Unit tests
./gradlew test
 
# Instrumentation tests (requires connected device or emulator)
./gradlew connectedAndroidTest
```
 
---
 
## How It Works
 
```text
User taps orb (or floating bubble in Screen Share)
  -> AudioRecord captures mic input
  -> Sarvam Saaras v3 transcribes audio and auto-detects language
  -> Detected language saved to SharedPreferences
  -> RAG pipeline retrieves relevant memory chunks
  -> If Screen Share is active, the current phone screenshot is captured
  -> Groq processes query (Llama 3.3 for text, Llama 4 Scout for vision)
  -> Sarvam Bulbul v3 speaks the answer in detected language
  -> If needsImage is true: FLUX.1-schnell generates image silently in background
  -> Conversation turn stored in ObjectBox vector DB for future context
  -> Orb returns to idle
```
 
---
 
## Privacy
 
- All conversation data is stored locally on device in ObjectBox. Nothing is sent to any server except the AI API calls.
- Images generated are never stored on device or on any server.
- Firebase stores only your phone number for authentication.
- No user data is sold or shared with third parties.
 
---
 
## Roadmap
 
- Session summary cards
- Proactive reminders from past conversation context
- Personalized scheme eligibility profile built from RAG memory
- Emergency mode with hardcoded fast-path for ambulance and police keywords
- WhatsApp share of AI response
- Offline fallback with pre-loaded common queries
- Migrate local vector DB to Firestore for cross-device sync
 
---
 
## Contributing
 
Contributions are welcome.
 
1. Fork the repository
2. Create a branch from `assisted-workflow`
3. Keep PRs small and focused
4. Include screen recordings for any UI changes
5. Add or update tests where applicable
