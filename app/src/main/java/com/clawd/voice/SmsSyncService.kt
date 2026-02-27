package com.clawd.voice

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Periodic SMS sync — reads recent text message threads and pushes
 * them to OpenClaw as markdown for context and action item extraction.
 * 
 * Called periodically from MainActivity or via WorkManager.
 * Only syncs messages newer than the last sync timestamp.
 */
class SmsSyncService(private val context: Context) {

    companion object {
        private const val TAG = "SmsSyncService"
        private const val PREFS_NAME = "sms_sync_prefs"
        private const val LAST_SYNC_KEY = "last_sync_timestamp"
        private const val MAX_MESSAGES_PER_SYNC = 100
        private const val MAX_CONVERSATIONS = 20
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    data class SmsMessage(
        val address: String,
        val body: String,
        val date: Long,
        val type: Int, // 1 = received, 2 = sent
        val threadId: Long
    )

    /**
     * Sync recent SMS messages since last sync.
     * Returns number of new messages found.
     */
    suspend fun syncRecent(): Int = withContext(Dispatchers.IO) {
        val settings = SettingsManager(context)
        val webhookUrl = settings.getWebhookUrl()
        val webhookToken = settings.getWebhookToken()
        
        if (webhookUrl.isBlank() || webhookToken.isBlank()) {
            Log.w(TAG, "Webhook not configured")
            return@withContext 0
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(LAST_SYNC_KEY, System.currentTimeMillis() - 24 * 60 * 60 * 1000) // Default: last 24h
        
        val messages = readSmsAfter(lastSync)
        if (messages.isEmpty()) {
            Log.d(TAG, "No new messages since last sync")
            return@withContext 0
        }

        Log.d(TAG, "Found ${messages.size} new messages")

        // Group by thread/contact
        val threads = messages.groupBy { it.address }
        
        // Build markdown digest
        val markdown = buildMarkdown(threads)
        
        // Push to OpenClaw
        val success = pushDigest(webhookUrl, webhookToken, markdown, messages.size, threads.size)
        
        if (success) {
            // Update last sync timestamp
            prefs.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
        }
        
        return@withContext messages.size
    }

    /**
     * Full sync of recent conversations (last 48 hours).
     * Used for initial setup or manual refresh.
     */
    suspend fun syncFull(): Int = withContext(Dispatchers.IO) {
        val settings = SettingsManager(context)
        val webhookUrl = settings.getWebhookUrl()
        val webhookToken = settings.getWebhookToken()
        
        if (webhookUrl.isBlank() || webhookToken.isBlank()) {
            Log.w(TAG, "Webhook not configured")
            return@withContext 0
        }

        val since = System.currentTimeMillis() - 48 * 60 * 60 * 1000 // 48 hours
        val messages = readSmsAfter(since)
        
        if (messages.isEmpty()) return@withContext 0

        val threads = messages.groupBy { it.address }
        val markdown = buildMarkdown(threads)
        
        val success = pushDigest(webhookUrl, webhookToken, markdown, messages.size, threads.size)
        
        if (success) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(LAST_SYNC_KEY, System.currentTimeMillis()).apply()
        }
        
        return@withContext messages.size
    }

    private fun readSmsAfter(sinceTimestamp: Long): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        
        try {
            val uri = Telephony.Sms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.THREAD_ID
            )
            val selection = "${Telephony.Sms.DATE} > ?"
            val selectionArgs = arrayOf(sinceTimestamp.toString())
            val sortOrder = "${Telephony.Sms.DATE} ASC"

            context.contentResolver.query(
                uri, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val threadIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)

                var count = 0
                while (cursor.moveToNext() && count < MAX_MESSAGES_PER_SYNC) {
                    val address = cursor.getString(addressIdx) ?: continue
                    val body = cursor.getString(bodyIdx) ?: continue
                    
                    messages.add(SmsMessage(
                        address = address,
                        body = body,
                        date = cursor.getLong(dateIdx),
                        type = cursor.getInt(typeIdx),
                        threadId = cursor.getLong(threadIdx)
                    ))
                    count++
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SMS permission not granted: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading SMS: ${e.message}")
        }
        
        return messages
    }

    /**
     * Look up contact name from phone number.
     * Returns the number if no contact found.
     */
    private fun getContactName(phoneNumber: String): String {
        try {
            val uri = Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for $phoneNumber: ${e.message}")
        }
        return phoneNumber
    }

    private fun buildMarkdown(threads: Map<String, List<SmsMessage>>): String {
        return buildString {
            appendLine("# SMS Digest")
            appendLine("*Generated: ${dateFormat.format(Date())}*")
            appendLine()
            
            // Sort threads by most recent message
            val sortedThreads = threads.entries
                .sortedByDescending { it.value.maxOf { msg -> msg.date } }
                .take(MAX_CONVERSATIONS)
            
            for ((address, messages) in sortedThreads) {
                val contactName = getContactName(address)
                val displayName = if (contactName != address) "$contactName ($address)" else address
                
                appendLine("## $displayName")
                appendLine()
                
                for (msg in messages) {
                    val time = dateFormat.format(Date(msg.date))
                    val direction = if (msg.type == 2) "Brian" else contactName.split(" ").first()
                    appendLine("**$direction** [$time]: ${msg.body}")
                }
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }

    private suspend fun pushDigest(
        webhookUrl: String,
        webhookToken: String,
        markdown: String,
        messageCount: Int,
        threadCount: Int
    ): Boolean {
        return try {
            val payload = JSONObject().apply {
                put("severity", "info")
                put("title", "SMS Sync: $messageCount messages in $threadCount conversations")
                put("source", "ClawdVoice-Android")
                put("message", buildString {
                    appendLine("📱 SMS SYNC DIGEST")
                    appendLine()
                    appendLine("$messageCount new messages across $threadCount conversations.")
                    appendLine()
                    appendLine("Review for context and action items. If any messages contain tasks or requests for Brian, add them to memory/tasks.md.")
                    appendLine()
                    appendLine("---")
                    appendLine()
                    append(markdown)
                })
            }

            val request = Request.Builder()
                .url("$webhookUrl/hooks/alert")
                .addHeader("Authorization", "Bearer $webhookToken")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            if (success) {
                Log.d(TAG, "SMS digest pushed: $messageCount messages")
            } else {
                Log.w(TAG, "Webhook returned ${response.code}")
            }
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push SMS digest: ${e.message}")
            false
        }
    }
}
