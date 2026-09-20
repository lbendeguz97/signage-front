package com.example.signage_front.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide UI language for hardcoded labels and page HTML selection (HU/EN).
 * Persisted in SharedPreferences and exposed as a StateFlow so Compose recomposes.
 */
object LanguageManager {
    private const val PREFS = "ui_prefs"
    private const val KEY_LANGUAGE = "ui_language"

    const val HU = "hu"
    const val EN = "en"

    private val _language = MutableStateFlow(HU)
    val language: StateFlow<String> = _language.asStateFlow()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _language.value = prefs.getString(KEY_LANGUAGE, HU) ?: HU
    }

    fun set(context: Context, language: String) {
        _language.value = language
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANGUAGE, language).apply()
    }

    fun toggle(context: Context) {
        set(context, if (_language.value == EN) HU else EN)
    }
}
