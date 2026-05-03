package com.sohanreddy.sevak

object Constants {
    val GROQ_API_KEY: String get() = BuildConfig.GROQ_API_KEY
    val SARVAM_API_KEY: String get() = BuildConfig.SARVAM_API_KEY
    val HF_TOKEN: String get() = BuildConfig.HF_TOKEN
    const val PREFS_NAME = "saathi_prefs"
    const val KEY_LANGUAGE_CODE = "language_code"
    const val KEY_LANGUAGE_NAME = "language_name"
}
