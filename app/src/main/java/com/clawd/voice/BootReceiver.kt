package com.clawd.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * SAMSUNG WORKAROUND: Boot receiver for auto-start
 * 
 * Samsung devices (like all Android devices) kill all services on reboot.
 * This receiver automatically restarts the wake word service if it was
 * previously enabled by the user.
 * 
 * This ensures "Hey Clawed" detection works immediately after phone restart
 * without user intervention.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed - checking if wake word should start")
            
            val settings = SettingsManager(context)
            
            // Only auto-start if:
            // 1. Wake word is enabled
            // 2. Porcupine access key is configured
            if (settings.isWakeWordEnabled() && settings.getPorcupineAccessKey().isNotEmpty()) {
                Log.d(TAG, "Starting wake word service (device: ${android.os.Build.MANUFACTURER})")
                WakeWordService.start(context)
            } else {
                Log.d(TAG, "Wake word not enabled or no access key - skipping auto-start")
            }
        }
    }
}
