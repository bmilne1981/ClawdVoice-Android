package com.clawd.voice

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
 * Observes the SMS content provider for outgoing (sent) messages.
 * When Brian sends a text, pushes it to OpenClaw so Clawd can see
 * both sides of conversations.
 *
 * Register via context.contentResolver.registerContentObserver()
 * on Telephony.Sms.CONTENT_URI with notifyForDescendants = true.
 */
class SmsOutboxObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    companion object {
        private const val TAG = "SmsOutboxObserver"
        private const val DEBOUNCE_MS = 3000L
        private const val PREFS_NAME = "sms_outbox_prefs"
        private const val LAST_SENT_TIMESTAMP_KEY = "last_sent_timestamp"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // Debounce: content observer fires multiple times per SMS
    private var pendingJob: Job? = null

    // Track recently pushed messages to avoid duplicates across URI checks
    private val recentPushes = mutableListOf<String>()

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)

        // Debounce rapid-fire onChange calls
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(DEBOUNCE_MS)
            checkForNewSentMessages()
        }
    }

    private suspend fun checkForNewSentMessages() {
        val settings = SettingsManager(context)
        if (!settings.isSmsSyncEnabled()) return

        val webhookUrl = settings.getWebhookUrl()
        val webhookToken = settings.getWebhookToken()
        if (webhookUrl.isBlank() || webhookToken.isBlank()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSentTimestamp = prefs.getLong(LAST_SENT_TIMESTAMP_KEY, System.currentTimeMillis() - 60_000) // Default: last 60s

        // Query ALL SMS/RCS with type=2 (sent) since last check
        // Using the general content URI + type filter catches both SMS and RCS
        // on devices where Google Messages writes RCS to the telephony provider
        val urisToCheck = listOf(
            Telephony.Sms.CONTENT_URI,                              // Standard SMS/RCS
            Uri.parse("content://sms"),                             // Fallback raw URI
        )

        var foundAny = false
        var maxTimestamp = lastSentTimestamp

        for (uri in urisToCheck) {
            try {
                val projection = arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                )
                // Type 2 = sent messages; query by timestamp to catch RCS too
                val selection = "${Telephony.Sms.TYPE} = ? AND ${Telephony.Sms.DATE} > ?"
                val selectionArgs = arrayOf("2", lastSentTimestamp.toString())
                val sortOrder = "${Telephony.Sms.DATE} ASC"

                context.contentResolver.query(
                    uri, projection, selection, selectionArgs, sortOrder
                )?.use { cursor ->
                    val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                    while (cursor.moveToNext()) {
                        val address = cursor.getString(addressIdx) ?: continue
                        val body = cursor.getString(bodyIdx) ?: continue
                        val date = cursor.getLong(dateIdx)

                        if (date > maxTimestamp) maxTimestamp = date

                        // Deduplicate: check if we already pushed this exact message
                        val dedupeKey = "$address:${body.take(50)}:$date"
                        if (dedupeKey in recentPushes) continue
                        recentPushes.add(dedupeKey)

                        val contactName = getContactName(address)
                        pushOutboundSms(webhookUrl, webhookToken, contactName, address, body, date)
                        foundAny = true
                    }
                }

                // If we found messages from the first URI, don't check fallback
                if (foundAny) break
            } catch (e: SecurityException) {
                Log.e(TAG, "SMS permission not granted for $uri: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "Could not query $uri: ${e.message}")
            }
        }

        if (maxTimestamp > lastSentTimestamp) {
            prefs.edit().putLong(LAST_SENT_TIMESTAMP_KEY, maxTimestamp).apply()
        }

        // Trim dedupe set
        if (recentPushes.size > 100) {
            val excess = recentPushes.size - 50
            repeat(excess) { recentPushes.removeAt(0) }
        }
    }

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

    private suspend fun pushOutboundSms(
        webhookUrl: String,
        webhookToken: String,
        contactName: String,
        address: String,
        body: String,
        timestamp: Long
    ) {
        try {
            val displayTo = if (contactName != address) "$contactName ($address)" else address
            val timeStr = dateFormat.format(Date(timestamp))

            val payload = JSONObject().apply {
                put("severity", "info")
                put("title", "Brian sent SMS to $contactName")
                put("source", "ClawdVoice-Android")
                put("message", buildString {
                    appendLine("📤 OUTGOING MESSAGE [sms]")
                    appendLine()
                    appendLine("**From:** Brian")
                    appendLine("**To:** $displayTo")
                    appendLine("**Via:** Google Messages")
                    appendLine("**Time:** $timeStr")
                    appendLine()
                    appendLine("\"$body\"")
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
                Log.d(TAG, "Pushed outbound SMS to $contactName")
            } else {
                Log.w(TAG, "Webhook returned ${response.code}")
            }
            response.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push outbound SMS: ${e.message}")
        }
    }

    fun destroy() {
        pendingJob?.cancel()
        scope.cancel()
    }
}
