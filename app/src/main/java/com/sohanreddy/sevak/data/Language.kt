package com.sohanreddy.sevak.data

data class Language(
    val displayName: String,
    val code: String,
    val sarvamCode: String,
    val englishName: String
)

val supportedLanguages = listOf(
    Language("हिंदी", "hi", "hi-IN", "Hindi"),
    Language("ಕನ್ನಡ", "kn", "kn-IN", "Kannada"),
    Language("தமிழ்", "ta", "ta-IN", "Tamil"),
    Language("తెలుగు", "te", "te-IN", "Telugu"),
    Language("English", "en", "en-IN", "English"),
    Language("मराठी", "mr", "mr-IN", "Marathi"),
    Language("বাংলা", "bn", "bn-IN", "Bengali"),
    Language("ગુજરાતી", "gu", "gu-IN", "Gujarati")
)

fun getLanguageByCode(code: String): Language? = supportedLanguages.find { it.code == code }

fun getStatusText(state: String, langCode: String): String {
    return when (langCode) {
        "hi" -> when (state) { "IDLE" -> "बोलने के लिए टैप करें"; "LISTENING" -> "सुन रहा हूँ..."; "PROCESSING" -> "सोच रहा हूँ..."; "SPEAKING" -> "बोल रहा हूँ..."; else -> "" }
        "kn" -> when (state) { "IDLE" -> "ಮಾತನಾಡಲು ಟ್ಯಾಪ್ ಮಾಡಿ"; "LISTENING" -> "ಕೇಳುತ್ತಿದ್ದೇನೆ..."; "PROCESSING" -> "ಯೋಚಿಸುತ್ತಿದ್ದೇನೆ..."; "SPEAKING" -> "ಮಾತನಾಡುತ್ತಿದ್ದೇನೆ..."; else -> "" }
        "ta" -> when (state) { "IDLE" -> "பேச தட்டவும்"; "LISTENING" -> "கேட்கிறேன்..."; "PROCESSING" -> "யோசிக்கிறேன்..."; "SPEAKING" -> "பேசுகிறேன்..."; else -> "" }
        "te" -> when (state) { "IDLE" -> "మాట్లాడటానికి నొక్కండి"; "LISTENING" -> "వింటున్నాను..."; "PROCESSING" -> "ఆలోచిస్తున్నాను..."; "SPEAKING" -> "మాట్లాడుతున్నాను..."; else -> "" }
        "en" -> when (state) { "IDLE" -> "Tap to speak"; "LISTENING" -> "Listening..."; "PROCESSING" -> "Thinking..."; "SPEAKING" -> "Speaking..."; else -> "" }
        "mr" -> when (state) { "IDLE" -> "बोलण्यासाठी टॅप करा"; "LISTENING" -> "ऐकतोय..."; "PROCESSING" -> "विचार करतोय..."; "SPEAKING" -> "बोलतोय..."; else -> "" }
        "bn" -> when (state) { "IDLE" -> "কথা বলতে ট্যাপ করুন"; "LISTENING" -> "শুনছি..."; "PROCESSING" -> "ভাবছি..."; "SPEAKING" -> "বলছি..."; else -> "" }
        "gu" -> when (state) { "IDLE" -> "બોલવા માટે ટેપ કરો"; "LISTENING" -> "સાંભળું છું..."; "PROCESSING" -> "વિચારું છું..."; "SPEAKING" -> "બોલું છું..."; else -> "" }
        else -> when (state) { "IDLE" -> "Tap to speak"; "LISTENING" -> "Listening..."; "PROCESSING" -> "Thinking..."; "SPEAKING" -> "Speaking..."; else -> "" }
    }
}
