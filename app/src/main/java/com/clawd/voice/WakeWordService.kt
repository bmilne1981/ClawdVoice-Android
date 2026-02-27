package com.clawd.voice

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback

class WakeWordService : Service() {

    private var porcupineManager: PorcupineManager? = null
    private lateinit var settings: SettingsManager
    
    // Samsung workaround: Partial wake lock to prevent CPU sleep during mic access
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "WakeWordService"
        private const val NOTIFICATION_CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PAUSE = "com.clawd.voice.PAUSE_WAKE_WORD"
        private const val ACTION_RESUME = "com.clawd.voice.RESUME_WAKE_WORD"
        private const val ACTION_RESTART = "com.clawd.voice.RESTART_SERVICE"
        
        private const val RESTART_ALARM_REQUEST_CODE = 2001

        // Static reference so MainActivity can pause/resume without rebinding
        private var instance: WakeWordService? = null

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

        fun pause(context: Context) {
            instance?.pauseListening() ?: run {
                Log.w(TAG, "pause() called but no service instance")
            }
        }

        fun resume(context: Context) {
            instance?.resumeListening() ?: run {
                Log.w(TAG, "resume() called but no service instance — restarting")
                start(context)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsManager(this)
        createNotificationChannel()
        
        // Samsung workaround: Acquire partial wake lock
        // This prevents CPU from going into deep sleep while listening
        acquireWakeLock()
        
        Log.d(TAG, "WakeWordService created (device: ${Build.MANUFACTURER})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // SAMSUNG WORKAROUND: Show foreground notification immediately
        // FOREGROUND_SERVICE_TYPE_MICROPHONE tells Android this service needs mic access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        when (intent?.action) {
            ACTION_PAUSE -> pauseListening()
            ACTION_RESUME -> resumeListening()
            ACTION_RESTART -> {
                Log.d(TAG, "Service restart triggered by AlarmManager")
                if (porcupineManager == null) {
                    startWakeWordDetection()
                }
            }
            else -> {
                // Fresh start or restart — only init if not already running
                if (porcupineManager == null) {
                    startWakeWordDetection()
                } else {
                    resumeListening()
                }
            }
        }

        // SAMSUNG WORKAROUND: START_STICKY ensures Android will restart the service
        // if it gets killed. Combined with AlarmManager backup for extra resilience.
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

            porcupineManager = PorcupineManager.Builder()
                .setAccessKey(accessKey)
                .setKeywordPath("hey-clawed.ppn")
                .setSensitivity(sensitivity)
                .build(this, PorcupineManagerCallback { keywordIndex ->
                    Log.d(TAG, "Wake word detected: Hey Clawed!")
                    onWakeWordDetected()
                })

            porcupineManager?.start()
            
            // SAMSUNG WORKAROUND: Schedule backup restart alarm
            // If Samsung kills the service, AlarmManager will restart it
            scheduleRestartAlarm()
            
            Log.d(TAG, "Wake word detection started (keyword: Hey Clawed, sensitivity: $sensitivity)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Porcupine: ${e.message}", e)
            stopSelf()
        }
    }

    private fun onWakeWordDetected() {
        // Pause listening while we handle the wake word (don't destroy)
        pauseListening()

        // Launch MainActivity with wake word trigger
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("wake_word_triggered", true)
        }
        startActivity(intent)
    }

    fun pauseListening() {
        try {
            porcupineManager?.stop()
            Log.d(TAG, "Paused wake word listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause listening: ${e.message}", e)
        }
    }

    fun resumeListening() {
        try {
            porcupineManager?.start()
            Log.d(TAG, "Resumed wake word listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume listening: ${e.message}", e)
        }
    }

    /**
     * SAMSUNG WORKAROUND: Acquire partial wake lock
     * 
     * Samsung's aggressive battery optimization can put CPU to sleep even during
     * active microphone use. A partial wake lock keeps the CPU running at minimum
     * power while allowing screen off, ensuring continuous mic access.
     * 
     * This is released automatically when the service is destroyed.
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "ClawdVoice::WakeWordWakeLock"
            ).apply {
                // Don't hold wake lock permanently if something goes wrong
                acquire(24 * 60 * 60 * 1000L) // Max 24 hours
            }
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}", e)
        }
    }

    /**
     * SAMSUNG WORKAROUND: Schedule AlarmManager-based restart
     * 
     * Samsung can still kill foreground services under extreme battery optimization.
     * This schedules a backup restart using AlarmManager (which is more resilient)
     * to check every 15 minutes if the service is still running, and restart it if not.
     * 
     * This is canceled when the service stops normally.
     */
    private fun scheduleRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, WakeWordService::class.java).apply {
                action = ACTION_RESTART
            }
            val pendingIntent = PendingIntent.getService(
                this,
                RESTART_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Check/restart every 15 minutes
            val intervalMillis = 15 * 60 * 1000L
            val triggerAtMillis = System.currentTimeMillis() + intervalMillis

            // Use setExactAndAllowWhileIdle for better reliability on Doze mode
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }

            Log.d(TAG, "Restart alarm scheduled for +15 minutes")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule restart alarm: ${e.message}", e)
        }
    }

    /**
     * Cancel the backup restart alarm when service stops normally
     */
    private fun cancelRestartAlarm() {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, WakeWordService::class.java).apply {
                action = ACTION_RESTART
            }
            val pendingIntent = PendingIntent.getService(
                this,
                RESTART_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Restart alarm canceled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel restart alarm: ${e.message}", e)
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

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Clawd is listening...")
                .setContentText("Say 'Hey Clawed' to activate")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Clawd is listening...")
                .setContentText("Say 'Hey Clawed' to activate")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        
        // Cancel backup restart alarm
        cancelRestartAlarm()
        
        // Release wake lock
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
        
        // Clean up Porcupine
        try {
            porcupineManager?.stop()
            porcupineManager?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Porcupine: ${e.message}", e)
        }
        porcupineManager = null
        
        Log.d(TAG, "Wake word service destroyed")
    }

    /**
     * SAMSUNG WORKAROUND: Handle task removal
     * 
     * When user swipes app away from recent apps, Samsung may call onTaskRemoved.
     * We restart the service to keep wake word detection running.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed - restarting service")
        
        // Restart the service
        val restartIntent = Intent(applicationContext, WakeWordService::class.java)
        val pendingIntent = PendingIntent.getService(
            this,
            1,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 1000,
            pendingIntent
        )
    }
}
