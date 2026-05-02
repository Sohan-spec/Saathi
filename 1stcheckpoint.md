# Saathi — 1st Checkpoint

**Date:** 2026-05-02
**Status:** ✅ BUILD SUCCESSFUL — APK generated

---

## What Was Built

### Project Setup
- Converted from Java/XML project to **Kotlin + Jetpack Compose**
- AGP 9.1.1 with bundled Kotlin 2.1.20
- Compose BOM 2025.04.01, Material3
- Firebase BOM 33.13.0 (auth-ktx)
- Retrofit 2.11.0 + OkHttp 4.12.0
- Navigation Compose 2.9.0, Lifecycle 2.9.0
- minSdk 26, targetSdk 36

### Files Created (14 files)

| File | Purpose |
|------|---------|
| `Constants.kt` | API keys (GROQ, SARVAM), SharedPrefs keys |
| `data/Language.kt` | 8 languages with display names, codes, Sarvam codes, localized status text |
| `data/PrefsManager.kt` | SharedPreferences wrapper for language persistence |
| `network/GroqApi.kt` | Retrofit service for Groq LLM (llama-3.3-70b-versatile) |
| `network/SarvamApi.kt` | Retrofit service for Sarvam STT (saaras:v3) and TTS (bulbul:v3) |
| `audio/AudioHelper.kt` | AudioRecord-based WAV recording + MediaPlayer base64 audio playback |
| `ui/auth/PhoneAuthViewModel.kt` | Firebase Phone Auth — OTP sending |
| `ui/auth/OtpViewModel.kt` | OTP verification with 30s countdown + resend |
| `ui/auth/PhoneEntryScreen.kt` | Screen 1 — Phone entry with +91 prefix |
| `ui/auth/OtpScreen.kt` | Screen 2 — OTP verification |
| `ui/language/LanguagePickerScreen.kt` | Screen 3 — 8 language buttons |
| `ui/main/MainViewModel.kt` | Full voice pipeline: Record → STT → LLM → TTS |
| `ui/main/MainScreen.kt` | Screen 4 — Mic button, status text, response area, settings sheet |
| `navigation/NavGraph.kt` | Navigation with auth check, language check, routing |

### 4 Screens
1. **Phone Entry** — +91 prefix, Send OTP button, loading state, error display
2. **OTP Verification** — 6-digit input, Verify button, 30s countdown, resend link
3. **Language Picker** — 8 languages in native script (हिंदी, ಕನ್ನಡ, தமிழ், etc.)
4. **Main Screen** — Dark background (#0A0A0F), colored mic circle, status text, response area, settings bottom sheet

### Full Voice Pipeline
```
Tap mic → AudioRecord (WAV) → Sarvam STT (saaras:v3) → transcript
→ Groq LLM (llama-3.3-70b) → response → Sarvam TTS (bulbul:v3)
→ MediaPlayer playback → idle
```

### Error Handling
- No internet → Toast on launch
- STT API fails → fallback to Android SpeechRecognizer (stub)
- TTS API fails → fallback to Android TextToSpeech
- Groq fails → "Something went wrong" message
- Firebase errors → error text below input fields

### Auth Flow
- App open → check `FirebaseAuth.currentUser`
  - Signed in + language saved → Main Screen
  - Signed in + no language → Language Picker
  - Not signed in → Phone Entry
- Sign out → clear Firebase + SharedPrefs → Phone Entry

### Settings (Bottom Sheet)
- Change Language → Language Picker
- Sign Out → Firebase sign out + clear prefs

---

## Build Config Changes
- `libs.versions.toml` — Added Kotlin, Compose, Firebase, Retrofit, Lifecycle, Navigation
- `build.gradle.kts` (root) — Added compose-compiler, google-services plugins
- `build.gradle.kts` (app) — Compose enabled, all dependencies, Kotlin compiler options
- `AndroidManifest.xml` — INTERNET, RECORD_AUDIO, ACCESS_NETWORK_STATE permissions
- `strings.xml` — App name changed to "Saathi"
- Deleted `activity_main.xml` (not needed with Compose)

---

## To Run
1. Replace `YOUR_GROQ_KEY` and `YOUR_SARVAM_KEY` in `Constants.kt`
2. Enable Phone Auth in Firebase Console
3. `./gradlew assembleDebug` or run from Android Studio
4. Grant microphone permission when prompted
