package com.clawd.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback

class WakeWordService : Service() {

    private var porcupineManager: PorcupineManager? = null
    private lateinit var settings: SettingsManager

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        startWakeWordDetection()
        return START_STICKY
    }

    private fun startWakeWordDetection() {
        val accessKey = settings.getPorcupineAccessKey()
        if (accessKey.isEmpty()) {
            Log.e(TAG, "No Porcupine access key configured")
            stopSelf()
            return
        }

        try {
            val sensitivity = settings.getWakeWordSensitivity()

            // Use built-in "Porcupine" keyword as placeholder.
            // To use a custom "Hey Clawd" keyword:
            // 1. Go to https://console.picovoice.ai/
            // 2. Train a custom keyword "Hey Clawd" for Android
            // 3. Download the .ppn file
            // 4. Place it in app/src/main/assets/hey-clawd_android.ppn
            // 5. Replace the builder below with:
            //    .setKeywordPath("hey-clawd_android.ppn")
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                .setSensitivity(sensitivity)
                .build(this, PorcupineManagerCallback { keywordIndex ->
                    Log.d(TAG, "Wake word detected!")
                    onWakeWordDetected()
                })

            porcupineManager?.start()
            Log.d(TAG, "Wake word detection started (keyword: Porcupine, sensitivity: $sensitivity)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Porcupine: ${e.message}", e)
            stopSelf()
        }
    }

    private fun onWakeWordDetected() {
        // Stop listening while we handle the wake word
        porcupineManager?.stop()

        // Launch MainActivity with wake word trigger
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("wake_word_triggered", true)
        }
        startActivity(intent)
    }

    fun resumeListening() {
        try {
            porcupineManager?.start()
            Log.d(TAG, "Resumed wake word listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume listening: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Wake Word Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when ClawdVoice is listening for the wake word"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ClawdVoice")
            .setContentText("Listening for wake word...")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Porcupine: ${e.message}", e)
        }
        porcupineManager = null
        Log.d(TAG, "Wake word service destroyed")
    }
}
