package com.sohanreddy.sevak.data

import android.content.Context
import com.sohanreddy.sevak.Constants

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLanguage(code: String, name: String) {
        prefs.edit()
            .putString(Constants.KEY_LANGUAGE_CODE, code)
            .putString(Constants.KEY_LANGUAGE_NAME, name)
            .apply()
    }

    fun getLanguageCode(): String? = prefs.getString(Constants.KEY_LANGUAGE_CODE, null)

    fun getLanguageName(): String? = prefs.getString(Constants.KEY_LANGUAGE_NAME, null)

    fun clear() {
        prefs.edit().clear().apply()
    }
}
