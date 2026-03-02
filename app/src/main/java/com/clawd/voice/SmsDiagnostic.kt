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
 * Diagnostic tool to discover where Google Messages stores
 * RCS/SMS messages on this device. Queries multiple content
 * providers and reports what's found.
 */
class SmsDiagnostic(private val context: Context) {

    companion object {
        private const val TAG = "SmsDiagnostic"
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Run full diagnostic and return markdown report.
     * Checks all known content URIs for sent messages in the last hour.
     */
    suspend fun runDiagnostic(): String = withContext(Dispatchers.IO) {
        val report = StringBuilder()
        report.appendLine("# SMS/RCS Diagnostic Report")
        report.appendLine("*Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}*")
        report.appendLine("*Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})*")
        report.appendLine("*Time: ${dateFormat.format(Date())}*")
        report.appendLine()

        val oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000
        
        // 1. Standard SMS sent box
        report.appendLine("## 1. Telephony.Sms.Sent (content://sms/sent)")
        report.append(querySmsUri(Telephony.Sms.Sent.CONTENT_URI, oneHourAgo))
        report.appendLine()

        // 2. General SMS content URI with type=2 filter
        report.appendLine("## 2. Telephony.Sms (content://sms) - type=2 (sent)")
        report.append(querySmsUri(Telephony.Sms.CONTENT_URI, oneHourAgo, sentOnly = true))
        report.appendLine()

        // 3. Raw content://sms
        report.appendLine("## 3. Raw content://sms - type=2 (sent)")
        report.append(querySmsUri(Uri.parse("content://sms"), oneHourAgo, sentOnly = true))
        report.appendLine()

        // 4. ALL messages (sent + received) from content://sms
        report.appendLine("## 4. All SMS (sent + received, last hour)")
        report.append(querySmsUri(Telephony.Sms.CONTENT_URI, oneHourAgo, sentOnly = false))
        report.appendLine()

        // 5. MMS content provider
        report.appendLine("## 5. MMS (content://mms) - sent")
        report.append(queryMmsUri(oneHourAgo))
        report.appendLine()

        // 6. Combined MMS-SMS
        report.appendLine("## 6. MMS-SMS conversations (content://mms-sms/conversations)")
        report.append(queryMmsSmsConversations())
        report.appendLine()

        // 7. Google Messages content provider (may not be accessible)
        report.appendLine("## 7. Google Messages provider")
        val gmUris = listOf(
            "content://com.google.android.apps.messaging/conversations",
            "content://com.google.android.apps.messaging/messages",
            "content://com.google.android.apps.messaging",
        )
        for (gmUri in gmUris) {
            report.appendLine("### $gmUri")
            report.append(queryGenericUri(Uri.parse(gmUri)))
            report.appendLine()
        }

        // 8. Samsung Messages (in case it's the default)
        report.appendLine("## 8. Samsung Messages provider")
        val samsungUris = listOf(
            "content://com.samsung.android.messaging/message",
            "content://com.samsung.android.messaging",
        )
        for (sUri in samsungUris) {
            report.appendLine("### $sUri")
            report.append(queryGenericUri(Uri.parse(sUri)))
            report.appendLine()
        }

        report.toString()
    }

    private fun querySmsUri(uri: Uri, sinceTimestamp: Long, sentOnly: Boolean = true): String {
        val sb = StringBuilder()
        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.THREAD_ID
            )
            
            val selection = if (sentOnly) {
                "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.TYPE} = ?"
            } else {
                "${Telephony.Sms.DATE} > ?"
            }
            val selectionArgs = if (sentOnly) {
                arrayOf(sinceTimestamp.toString(), "2")
            } else {
                arrayOf(sinceTimestamp.toString())
            }
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            context.contentResolver.query(
                uri, projection, selection, selectionArgs, sortOrder
            )?.use { cursor ->
                sb.appendLine("Found **${cursor.count}** messages")
                if (cursor.count > 0) {
                    val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                    var count = 0
                    while (cursor.moveToNext() && count < 10) {
                        val address = cursor.getString(addressIdx) ?: "null"
                        val body = (cursor.getString(bodyIdx) ?: "null").take(80)
                        val date = cursor.getLong(dateIdx)
                        val type = cursor.getInt(typeIdx)
                        val typeLabel = when(type) { 1 -> "RECV"; 2 -> "SENT"; else -> "TYPE=$type" }
                        val contactName = getContactName(address)
                        val displayName = if (contactName != address) contactName else address
                        
                        sb.appendLine("- [$typeLabel] ${dateFormat.format(Date(date))} **$displayName**: $body")
                        count++
                    }
                    if (cursor.count > 10) sb.appendLine("- ... and ${cursor.count - 10} more")
                }
            } ?: sb.appendLine("Query returned null cursor")
        } catch (e: SecurityException) {
            sb.appendLine("❌ SecurityException: ${e.message}")
        } catch (e: Exception) {
            sb.appendLine("❌ Error: ${e.message}")
        }
        return sb.toString()
    }

    private fun queryMmsUri(sinceTimestamp: Long): String {
        val sb = StringBuilder()
        try {
            val uri = Telephony.Mms.CONTENT_URI
            val projection = arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.DATE,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.SUBJECT
            )
            // MMS date is in seconds, not milliseconds
            val sinceSeconds = sinceTimestamp / 1000
            val selection = "${Telephony.Mms.DATE} > ? AND ${Telephony.Mms.MESSAGE_BOX} = ?"
            val selectionArgs = arrayOf(sinceSeconds.toString(), "2") // 2 = sent
            
            context.contentResolver.query(
                uri, projection, selection, selectionArgs, "${Telephony.Mms.DATE} DESC"
            )?.use { cursor ->
                sb.appendLine("Found **${cursor.count}** sent MMS messages")
                if (cursor.count > 0) {
                    val idIdx = cursor.getColumnIndexOrThrow(Telephony.Mms._ID)
                    val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Mms.DATE)
                    
                    var count = 0
                    while (cursor.moveToNext() && count < 5) {
                        val id = cursor.getLong(idIdx)
                        val date = cursor.getLong(dateIdx) * 1000 // Convert to ms
                        sb.appendLine("- MMS ID=$id at ${dateFormat.format(Date(date))}")
                        count++
                    }
                }
            } ?: sb.appendLine("Query returned null cursor")
        } catch (e: Exception) {
            sb.appendLine("❌ Error: ${e.message}")
        }
        return sb.toString()
    }

    private fun queryMmsSmsConversations(): String {
        val sb = StringBuilder()
        try {
            val uri = Uri.parse("content://mms-sms/conversations")
            context.contentResolver.query(
                uri, null, null, null, null
            )?.use { cursor ->
                sb.appendLine("Found **${cursor.count}** conversations")
                sb.appendLine("Columns: ${cursor.columnNames.joinToString(", ")}")
            } ?: sb.appendLine("Query returned null cursor")
        } catch (e: Exception) {
            sb.appendLine("❌ Error: ${e.message}")
        }
        return sb.toString()
    }

    private fun queryGenericUri(uri: Uri): String {
        val sb = StringBuilder()
        try {
            context.contentResolver.query(
                uri, null, null, null, null
            )?.use { cursor ->
                sb.appendLine("✅ Accessible! Found **${cursor.count}** rows")
                sb.appendLine("Columns: ${cursor.columnNames.joinToString(", ")}")
            } ?: sb.appendLine("Query returned null cursor")
        } catch (e: SecurityException) {
            sb.appendLine("🔒 Access denied: ${e.message?.take(100)}")
        } catch (e: Exception) {
            sb.appendLine("❌ Error: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
        }
        return sb.toString()
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
        } catch (e: Exception) {}
        return phoneNumber
    }

    /**
     * Run diagnostic and push results to OpenClaw webhook.
     */
    suspend fun runAndPush(): String {
        val report = runDiagnostic()
        
        val settings = SettingsManager(context)
        val webhookUrl = settings.getWebhookUrl()
        val webhookToken = settings.getWebhookToken()
        
        if (webhookUrl.isNotBlank() && webhookToken.isNotBlank()) {
            try {
                val payload = JSONObject().apply {
                    put("severity", "info")
                    put("title", "SMS/RCS Diagnostic Report")
                    put("source", "ClawdVoice-Android")
                    put("message", report)
                }

                val request = Request.Builder()
                    .url("$webhookUrl/hooks/alert")
                    .addHeader("Authorization", "Bearer $webhookToken")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push diagnostic: ${e.message}")
            }
        }
        
        return report
    }
}
