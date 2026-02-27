package com.clawd.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class ApiResponse {
    data class Success(val text: String, val audioData: ByteArray?) : ApiResponse()
    data class Error(val message: String) : ApiResponse()
}

class ApiClient {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    suspend fun sendVoiceRequest(serverUrl: String, text: String): ApiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("text", text)
                    put("platform", "android")
                }
                
                val requestBody = json.toString()
                    .toRequestBody("application/json".toMediaType())
                
                val request = Request.Builder()
                    .url("$serverUrl/voice")
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    return@withContext ApiResponse.Error("Server returned ${response.code}")
                }
                
                val contentType = response.header("Content-Type") ?: ""
                
                if (contentType.contains("audio")) {
                    // Response is audio with text in header
                    val responseText = response.header("X-Response-Text") ?: ""
                    val audioData = response.body?.bytes()
                    ApiResponse.Success(responseText, audioData)
                } else {
                    // Response is JSON
                    val body = response.body?.string() ?: ""
                    val jsonResponse = JSONObject(body)
                    val responseText = jsonResponse.optString("response", "")
                    
                    // Check for base64 audio first, then URL
                    val audioBase64 = jsonResponse.optString("audio", "")
                    val audioUrl = jsonResponse.optString("audioUrl", "")
                    
                    val audioData = when {
                        audioBase64.isNotEmpty() -> {
                            try {
                                android.util.Base64.decode(audioBase64, android.util.Base64.DEFAULT)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        audioUrl.isNotEmpty() -> fetchAudio(audioUrl)
                        else -> null
                    }
                    
                    ApiResponse.Success(responseText, audioData)
                }
            } catch (e: Exception) {
                ApiResponse.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    suspend fun fetchAck(serverUrl: String, transcript: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(transcript, "UTF-8")
                val request = Request.Builder()
                    .url("$serverUrl/voice/ack?q=$encoded")
                    .build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful && response.code == 200) {
                    response.body?.bytes()
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private fun fetchAudio(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
