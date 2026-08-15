package com.vayunmathur.translate.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "translate_settings")

/** Persists the last-used source/target languages so they survive app restarts. */
class TranslateSettings(private val context: Context) {

    private val sourceKey = stringPreferencesKey("source_lang")
    private val targetKey = stringPreferencesKey("target_lang")

    suspend fun source(): String =
        context.dataStore.data.map { it[sourceKey] }.first() ?: Languages.AUTO.code

    suspend fun target(): String =
        context.dataStore.data.map { it[targetKey] }.first() ?: DEFAULT_TARGET

    suspend fun setSource(code: String) =
        context.dataStore.edit { it[sourceKey] = code }.let { }

    suspend fun setTarget(code: String) =
        context.dataStore.edit { it[targetKey] = code }.let { }

    companion object {
        private const val DEFAULT_TARGET = "es"
    }
}
