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
        private val WEBHOOK_URL_KEY = stringPreferencesKey("webhook_url")
        private val WEBHOOK_TOKEN_KEY = stringPreferencesKey("webhook_token")
        private val NOTIFICATION_BRIDGE_ENABLED_KEY = booleanPreferencesKey("notification_bridge_enabled")
        private val SMS_SYNC_ENABLED_KEY = booleanPreferencesKey("sms_sync_enabled")
        
        // Webhook source filter keys (default all enabled)
        private val WEBHOOK_GMAIL_ENABLED_KEY = booleanPreferencesKey("webhook_gmail_enabled")
        private val WEBHOOK_OUTLOOK_ENABLED_KEY = booleanPreferencesKey("webhook_outlook_enabled")
        private val WEBHOOK_SMS_ENABLED_KEY = booleanPreferencesKey("webhook_sms_enabled")
        private val WEBHOOK_PHONE_ENABLED_KEY = booleanPreferencesKey("webhook_phone_enabled")
        private val WEBHOOK_SLACK_ENABLED_KEY = booleanPreferencesKey("webhook_slack_enabled")
        
        const val DEFAULT_SERVER_URL = "http://192.168.1.197:8770"
        const val DEFAULT_SENSITIVITY = 0.7f
        const val DEFAULT_ACCESS_KEY = "CsJhQLyWP8B6qkqFQ7It7H7JMVrffEQmnC6cBrPwNdxMNfkR4uNeTQ=="
        const val DEFAULT_WEBHOOK_URL = "http://192.168.1.162:8770/notify"
        const val DEFAULT_WEBHOOK_TOKEN = "Q75MsIFj_9m551dG3qpxdfd9zwjIL2hA"
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
                preferences[PORCUPINE_ACCESS_KEY] ?: DEFAULT_ACCESS_KEY
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

    // Webhook settings (for notification bridge + SMS sync)
    
    fun getWebhookUrl(): String {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[WEBHOOK_URL_KEY] ?: DEFAULT_WEBHOOK_URL
            }.first()
        }
    }
    
    suspend fun setWebhookUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[WEBHOOK_URL_KEY] = url
        }
    }
    
    fun getWebhookToken(): String {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[WEBHOOK_TOKEN_KEY] ?: DEFAULT_WEBHOOK_TOKEN
            }.first()
        }
    }
    
    suspend fun setWebhookToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[WEBHOOK_TOKEN_KEY] = token
        }
    }
    
    fun isNotificationBridgeEnabled(): Boolean {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[NOTIFICATION_BRIDGE_ENABLED_KEY] ?: false
            }.first()
        }
    }
    
    suspend fun setNotificationBridgeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[NOTIFICATION_BRIDGE_ENABLED_KEY] = enabled
        }
    }
    
    fun isSmsSyncEnabled(): Boolean {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[SMS_SYNC_ENABLED_KEY] ?: true
            }.first()
        }
    }
    
    suspend fun setSmsSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SMS_SYNC_ENABLED_KEY] = enabled
        }
    }
    
    // Webhook source filter methods
    
    fun isWebhookGmailEnabled(): Boolean {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[WEBHOOK_GMAIL_ENABLED_KEY] ?: true
            }.first()
        }
    }
    
    suspend fun setWebhookGmailEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WEBHOOK_GMAIL_ENABLED_KEY] = enabled
        }
    }
    
    fun isWebhookOutlookEnabled(): Boolean {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[WEBHOOK_OUTLOOK_ENABLED_KEY] ?: true
            }.first()
        }
    }
    
    suspend fun setWebhookOutlookEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WEBHOOK_OUTLOOK_ENABLED_KEY] = enabled
        }
    }
    
    fun isWebhookSmsEnabled(): Boolean {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[WEBHOOK_SMS_ENABLED_KEY] ?: true
            }.first()
        }
    }
    
    suspend fun setWebhookSmsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WEBHOOK_SMS_ENABLED_KEY] = enabled
        }
    }
    
    fun isWebhookPhoneEnabled(): Boolean {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[WEBHOOK_PHONE_ENABLED_KEY] ?: true
            }.first()
        }
    }
    
    suspend fun setWebhookPhoneEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WEBHOOK_PHONE_ENABLED_KEY] = enabled
        }
    }
    
    fun isWebhookSlackEnabled(): Boolean {
        return runBlocking {
            context.dataStore.data.map { preferences ->
                preferences[WEBHOOK_SLACK_ENABLED_KEY] ?: true
            }.first()
        }
    }
    
    suspend fun setWebhookSlackEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[WEBHOOK_SLACK_ENABLED_KEY] = enabled
        }
    }
}
