package com.clawd.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {
    
    companion object {
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        
        // Default to local network - user should configure their Mac mini's IP
        const val DEFAULT_SERVER_URL = "http://192.168.1.197:8770"
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
}
