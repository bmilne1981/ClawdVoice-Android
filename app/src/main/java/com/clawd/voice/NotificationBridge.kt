package com.clawd.voice

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Bridges Android notifications to OpenClaw via webhook.
 * Focuses on SMS/messaging apps but can be extended to any app.
 * 
 * Requires user to grant Notification Access in:
 * Settings → Apps → Special access → Notification access
 */
class NotificationBridge : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationBridge"
        
        // Package names to monitor
        private val MONITORED_PACKAGES = setOf(
            "com.samsung.android.messaging",      // Samsung Messages
            "com.google.android.apps.messaging",   // Google Messages
            "com.android.mms",                     // Stock Android MMS
            "com.whatsapp",                        // WhatsApp
            "com.whatsapp.w4b",                    // WhatsApp Business
            "org.telegram.messenger",              // Telegram
            "com.Slack",                           // Slack
            "com.microsoft.teams",                 // Teams
        )
        
        // Packages to always ignore
        private val IGNORED_PACKAGES = setOf(
            "com.clawd.voice",                     // Don't loop on our own notifications
            "com.android.systemui",
            "com.samsung.android.incallui",
            "com.android.vending",                 // Play Store
        )
        
        // Debounce: don't send duplicate notifications within this window
        private const val DEBOUNCE_MS = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    // Track recent notifications to debounce
    private val recentNotifications = mutableMapOf<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val packageName = sbn.packageName ?: return
        
        // Skip ignored packages
        if (packageName in IGNORED_PACKAGES) return
        
        // Only process monitored packages
        if (packageName !in MONITORED_PACKAGES) return
        
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        
        // Extract notification content
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        
        // Use big text if available (full message), otherwise use text (preview)
        val messageBody = bigText ?: text
        
        if (messageBody.isBlank()) return
        
        // Debounce: skip if we just sent this same notification
        val dedupeKey = "$packageName:$title:${messageBody.take(50)}"
        val now = System.currentTimeMillis()
        val lastSent = recentNotifications[dedupeKey]
        if (lastSent != null && (now - lastSent) < DEBOUNCE_MS) return
        recentNotifications[dedupeKey] = now
        
        // Clean up old debounce entries
        recentNotifications.entries.removeIf { now - it.value > 30000 }
        
        // Determine message type
        val messageType = when (packageName) {
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.mms" -> "sms"
            "com.whatsapp", "com.whatsapp.w4b" -> "whatsapp"
            "org.telegram.messenger" -> "telegram"
            "com.Slack" -> "slack"
            "com.microsoft.teams" -> "teams"
            else -> "notification"
        }
        
        Log.d(TAG, "[$messageType] $title: ${messageBody.take(80)}...")
        
        // Push to OpenClaw webhook
        scope.launch {
            pushToOpenClaw(
                type = messageType,
                sender = title,
                message = messageBody,
                subText = subText,
                packageName = packageName,
                timestamp = sbn.postTime
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // We don't need to do anything when notifications are dismissed
    }

    private suspend fun pushToOpenClaw(
        type: String,
        sender: String,
        message: String,
        subText: String?,
        packageName: String,
        timestamp: Long
    ) {
        try {
            val settings = SettingsManager(applicationContext)
            val webhookUrl = settings.getWebhookUrl()
            val webhookToken = settings.getWebhookToken()
            
            if (webhookUrl.isBlank() || webhookToken.isBlank()) {
                Log.w(TAG, "Webhook not configured, skipping push")
                return
            }

            val payload = JSONObject().apply {
                put("severity", "info")
                put("title", "Message from $sender")
                put("source", "ClawdVoice-Android")
                put("message", buildString {
                    appendLine("📱 INCOMING MESSAGE [$type]")
                    appendLine()
                    appendLine("**From:** $sender")
                    if (!subText.isNullOrBlank()) appendLine("**Group/Thread:** $subText")
                    appendLine("**Via:** ${getAppName(packageName)}")
                    appendLine("**Time:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(timestamp))}")
                    appendLine()
                    appendLine("\"$message\"")
                    appendLine()
                    appendLine("Review for context and action items. If this contains a task or request for Brian, add it to memory/tasks.md.")
                })
            }

            val request = Request.Builder()
                .url("$webhookUrl/hooks/alert")
                .addHeader("Authorization", "Bearer $webhookToken")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "Pushed to OpenClaw: $sender ($type)")
            } else {
                Log.w(TAG, "Webhook returned ${response.code}: ${response.body?.string()?.take(200)}")
            }
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push notification: ${e.message}")
        }
    }

    private fun getAppName(packageName: String): String = when (packageName) {
        "com.samsung.android.messaging" -> "Samsung Messages"
        "com.google.android.apps.messaging" -> "Google Messages"
        "com.android.mms" -> "Messages"
        "com.whatsapp" -> "WhatsApp"
        "com.whatsapp.w4b" -> "WhatsApp Business"
        "org.telegram.messenger" -> "Telegram"
        "com.Slack" -> "Slack"
        "com.microsoft.teams" -> "Teams"
        else -> packageName
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
