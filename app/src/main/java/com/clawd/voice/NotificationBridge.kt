package com.clawd.voice

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
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
            "com.google.android.gm",              // Gmail
            "com.microsoft.office.outlook",        // Outlook
            "com.Slack",                           // Slack
        )

        // Packages to always ignore
        private val IGNORED_PACKAGES = setOf(
            "com.clawd.voice",                     // Don't loop on our own notifications
            "com.android.systemui",
            "com.samsung.android.incallui",
            "com.android.vending",                 // Play Store
        )

        // Debounce: don't send duplicate notifications within this window
        // Extended to 1 hour to prevent notification shade replays after sending messages
        private const val DEBOUNCE_MS = 3600000L // 1 hour
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Track recent notifications to debounce
    private val recentNotifications = mutableMapOf<String, Long>()

    // Outbound SMS observer
    private var smsOutboxObserver: SmsOutboxObserver? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationBridge connected")

        // Outbound SMS observer DISABLED - causes duplicate notification spam
        // when Brian sends a message, Google Messages refreshes notification shade
        // and re-fires all old notifications through the bridge.
        // Re-enable when RCS observation actually works.
        if (false) {
        try {
            val settings = SettingsManager(applicationContext)
            if (settings.isSmsSyncEnabled()) {
                smsOutboxObserver = SmsOutboxObserver(applicationContext).also { observer ->
                    // Standard telephony SMS content URI
                    contentResolver.registerContentObserver(
                        Telephony.Sms.CONTENT_URI,
                        true, // notifyForDescendants - catches sent, inbox, etc.
                        observer
                    )
                    // Also observe raw content://sms in case RCS writes there differently
                    try {
                        contentResolver.registerContentObserver(
                            android.net.Uri.parse("content://sms"),
                            true,
                            observer
                        )
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not register raw sms observer: ${e.message}")
                    }
                    // Try Google Messages' internal provider (may not be accessible)
                    try {
                        contentResolver.registerContentObserver(
                            android.net.Uri.parse("content://com.google.android.apps.messaging/conversations"),
                            true,
                            observer
                        )
                        Log.d(TAG, "Google Messages content observer registered")
                    } catch (e: Exception) {
                        Log.d(TAG, "Google Messages provider not accessible (expected): ${e.message}")
                    }
                    Log.d(TAG, "Outbound SMS observer registered")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register SMS observer: ${e.message}")
        }
        } // end if (false) - outbound SMS observer disabled
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val packageName = sbn.packageName ?: return

        // Check if notification bridge is enabled (user toggle)
        try {
            val settings = SettingsManager(applicationContext)
            if (!settings.isNotificationBridgeEnabled()) {
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check notification bridge setting: ${e.message}")
        }

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

        // Skip notifications with no sender (empty title = system/background noise)
        if (title.isBlank()) return

        // Skip ongoing/service notifications (e.g. "Messages is doing work in the background")
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        // Skip known system/background notification patterns
        val systemPatterns = listOf(
            "doing work in the background",
            "syncing",
            "waiting for network",
        )
        if (systemPatterns.any { messageBody.lowercase().contains(it) }) return

        // Skip messages from Clawd (avoid self-loop from Telegram/etc)
        val senderLower = title.lowercase()
        if (senderLower == "clawd" || senderLower.startsWith("clawd ")) return

        // Debounce: skip if we just sent this same notification
        val dedupeKey = "$packageName:$title:${messageBody.take(50)}"
        val now = System.currentTimeMillis()
        val lastSent = recentNotifications[dedupeKey]
        if (lastSent != null && (now - lastSent) < DEBOUNCE_MS) return
        recentNotifications[dedupeKey] = now

        // Clean up old debounce entries (older than 2 hours)
        recentNotifications.entries.removeIf { now - it.value > 7200000 }

        // Determine message type
        val messageType = when (packageName) {
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.mms" -> "sms"
            "com.google.android.gm" -> "gmail"
            "com.microsoft.office.outlook" -> "outlook"
            "com.Slack" -> "slack"
            else -> "notification"
        }

        // Check webhook source filters — skip if this source type is disabled
        try {
            val filterSettings = SettingsManager(applicationContext)
            val sourceEnabled = when (messageType) {
                "gmail" -> filterSettings.isWebhookGmailEnabled()
                "outlook" -> filterSettings.isWebhookOutlookEnabled()
                "sms" -> filterSettings.isWebhookSmsEnabled()
                "slack" -> filterSettings.isWebhookSlackEnabled()
                else -> true // Unknown types pass through
            }
            if (!sourceEnabled) {
                Log.d(TAG, "[$messageType] webhook filtered out by user preference")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check webhook filter setting: ${e.message}")
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
        "com.google.android.gm" -> "Gmail"
        "com.microsoft.office.outlook" -> "Outlook"
        "com.Slack" -> "Slack"
        else -> packageName
    }

    override fun onDestroy() {
        // Unregister SMS observer
        smsOutboxObserver?.let { observer ->
            try {
                contentResolver.unregisterContentObserver(observer)
                observer.destroy()
                Log.d(TAG, "Outbound SMS observer unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering SMS observer: ${e.message}")
            }
        }
        smsOutboxObserver = null

        scope.cancel()
        super.onDestroy()
    }
}
