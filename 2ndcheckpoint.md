# Saathi — 2nd Checkpoint

**Date:** 2026-05-02
**Status:** ✅ BUILD SUCCESSFUL — APK generated

---

## What Changed

### Problem Fixed: HTTP 400 from Sarvam STT
- **Root cause**: The `SttResponse` data class didn't parse `language_code` from the API response, and the STT was always being called with a pre-set language code (e.g. `"en-IN"`) which could mismatch the actual spoken language.
- **Fix**: Updated `SttResponse` to include `language_code` and `language_probability` fields. When no language is saved in preferences, STT is now called with `language_code="unknown"`, enabling Sarvam's auto-detection.

### Language Picker Screen Removed
- **Deleted from onboarding flow**: The `LanguagePickerScreen` is no longer part of the navigation graph.
- **Route removed**: `Routes.LANGUAGE` deleted from `NavGraph.kt`.
- **Auth flow simplified**: After OTP verification → straight to Main Screen (no language picker step).

### New Auto-Detection Flow
```
User taps mic → Records audio (WAV)
→ Sarvam STT called with language_code="unknown" (if no saved lang)
    OR language_code=saved_sarvam_code (if user previously set one)
→ STT returns transcript + detected language_code (e.g. "hi-IN")
→ Detected language saved to SharedPreferences
→ Groq LLM called with system prompt in detected language
→ Sarvam TTS called with target_language_code = detected language
→ Audio playback → Idle
```

### Language Detection Priority
1. **User-selected language** (from Settings) — always takes precedence
2. **STT auto-detected language** — used on first interaction, saved to prefs
3. **Default fallback** — `hi-IN` (Hindi) if detection returns null/empty or unsupported language

### Settings Bottom Sheet (Inline Language Picker)
- Settings gear icon (top-right) → Bottom sheet now contains:
  - **2-column language grid** with 8 language tiles (native script + English name)
  - Currently active language highlighted in green
  - Tap to override → saves to SharedPreferences, applies from next query
  - **Sign Out** button (red text)

---

## Files Modified (5 files)

| File | Changes |
|------|---------|
| `network/SarvamApi.kt` | `SttResponse` now includes `language_code: String?` and `language_probability: Float?` fields |
| `data/Language.kt` | Added `getLanguageBySarvamCode()` to resolve Sarvam BCP-47 codes (e.g. "hi-IN") back to `Language` model |
| `ui/main/MainViewModel.kt` | Complete rewrite of `stopListening()`: sends `"unknown"` for auto-detect, parses detected language, saves to prefs, passes to Groq/TTS. Added `setLanguageManually()` for settings |
| `ui/main/MainScreen.kt` | Removed `langCode` and `onChangeLanguage` params. Takes `PrefsManager` directly. Settings sheet now has inline 2-column language grid with selection highlighting |
| `navigation/NavGraph.kt` | Removed `Routes.LANGUAGE`, removed `LanguagePickerScreen` composable. OTP → Main directly. `MainScreen` now receives `prefs` instead of `langCode` |

### File NOT Modified (kept for reference)
| File | Status |
|------|--------|
| `ui/language/LanguagePickerScreen.kt` | Kept but no longer referenced — can be deleted safely |

---

## Edge Cases Handled

| Scenario | Behavior |
|----------|----------|
| STT detection returns null/empty | Defaults to `hi-IN`, saves to prefs |
| STT detects unsupported language | Defaults to `hi-IN`, saves to prefs |
| User changes language in Settings | Overrides prefs, used from next query |
| First launch after auth | No language picker → Main Screen → mic → auto-detect on first speak |
| Language persists across sessions | Saved in SharedPreferences via `PrefsManager` |
| Sarvam TTS fails | Falls back to Android system TTS with correct locale |

---

## New User Flow

```
1. Phone Entry → OTP → Main Screen (no language picker!)
2. Main Screen shows mic icon centered on dark background
3. User taps mic → records → STT auto-detects language
4. Response in detected language (Groq + TTS)
5. Settings (gear icon) → change language manually if needed
6. Selected language persists across app restarts
```

---

## Build Warnings (non-blocking)
- `Icons.Filled.VolumeUp` deprecated → should use `Icons.AutoMirrored.Filled.VolumeUp`
- `Locale(String, String)` constructor deprecated in Java (8 instances)

These are cosmetic warnings and do not affect functionality.
