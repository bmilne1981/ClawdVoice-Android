package com.clawd.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    
    companion object {
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val WAKE_WORD_ENABLED_KEY = booleanPreferencesKey("wake_word_enabled")
        private val PORCUPINE_ACCESS_KEY = stringPreferencesKey("porcupine_access_key")
        private val WAKE_WORD_SENSITIVITY_KEY = floatPreferencesKey("wake_word_sensitivity")
        
        const val DEFAULT_SERVER_URL = "http://192.168.1.197:8770"
        const val DEFAULT_SENSITIVITY = 0.7f
    }
    
    fun getServerUrl(): String {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[SERVER_URL_KEY] ?: DEFAULT_SERVER_URL
            }.first()
        }
    }
    
    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = url
        }
    }
    
    fun getServerUrlFlow() = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL_KEY] ?: DEFAULT_SERVER_URL
    }
    
    fun isWakeWordEnabled(): Boolean {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[WAKE_WORD_ENABLED_KEY] ?: false
            }.first()
        }
    }
    
    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WAKE_WORD_ENABLED_KEY] = enabled
        }
    }
    
    fun getPorcupineAccessKey(): String {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[PORCUPINE_ACCESS_KEY] ?: ""
            }.first()
        }
    }
    
    suspend fun setPorcupineAccessKey(key: String) {
        context.dataStore.edit { preferences ->
            preferences[PORCUPINE_ACCESS_KEY] = key
        }
    }
    
    fun getWakeWordSensitivity(): Float {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[WAKE_WORD_SENSITIVITY_KEY] ?: DEFAULT_SENSITIVITY
            }.first()
        }
    }
    
    suspend fun setWakeWordSensitivity(sensitivity: Float) {
        context.dataStore.edit { preferences ->
            preferences[WAKE_WORD_SENSITIVITY_KEY] = sensitivity
        }
    }
}
