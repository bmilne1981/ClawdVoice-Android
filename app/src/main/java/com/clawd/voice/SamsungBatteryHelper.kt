package com.clawd.voice

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * SAMSUNG WORKAROUND: Battery Optimization Helper
 * 
 * Samsung's One UI has multiple layers of battery optimization that can kill
 * background services using the microphone:
 * 1. Standard Android battery optimization (Doze)
 * 2. Samsung's "Put apps to sleep" feature
 * 3. Samsung's "Deep sleeping apps" feature
 * 
 * This helper guides users through exempting ClawdVoice from all these optimizations.
 */
object SamsungBatteryHelper {
    
    private const val TAG = "SamsungBatteryHelper"
    private const val PREFS_NAME = "samsung_battery_prefs"
    private const val KEY_SETUP_SHOWN = "samsung_setup_shown"
    
    /**
     * Check if this is a Samsung device
     */
    fun isSamsungDevice(): Boolean {
        return Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }
    
    /**
     * Check if battery optimization is disabled for this app
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true // Not applicable on older Android
    }
    
    /**
     * Request battery optimization exemption
     */
    fun requestBatteryOptimizationExemption(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open battery optimization settings: ${e.message}", e)
                    openBatteryOptimizationSettingsManually(context)
                }
            }
        }
    }
    
    /**
     * Check if Samsung setup guide has been shown
     */
    fun hasShownSetupGuide(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SETUP_SHOWN, false)
    }
    
    /**
     * Mark Samsung setup guide as shown
     */
    fun markSetupGuideShown(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SETUP_SHOWN, true).apply()
    }
    
    /**
     * Show Samsung-specific battery optimization setup guide
     * 
     * This is shown once when wake word is first enabled on Samsung devices.
     */
    fun showSamsungSetupGuide(context: Context, onComplete: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Samsung Setup Required")
            .setMessage(
                """
                Samsung devices need special battery settings for continuous wake word detection.
                
                Please complete these 3 steps:
                
                1️⃣ BATTERY OPTIMIZATION
                Tap "Disable Optimization" below
                → Find ClawdVoice
                → Select "Don't optimize"
                
                2️⃣ NEVER SLEEPING APPS
                Tap "Samsung Battery Settings" below
                → Background usage limits
                → Never sleeping apps
                → Add ClawdVoice
                
                3️⃣ APP BATTERY SETTINGS
                → Settings → Apps → ClawdVoice
                → Battery → Unrestricted
                
                Without these steps, Samsung will kill the microphone when your screen turns off.
                """.trimIndent()
            )
            .setPositiveButton("Disable Optimization") { dialog, _ ->
                requestBatteryOptimizationExemption(context)
                dialog.dismiss()
            }
            .setNeutralButton("Samsung Battery Settings") { dialog, _ ->
                openSamsungBatterySettings(context)
                dialog.dismiss()
            }
            .setNegativeButton("App Settings") { dialog, _ ->
                openAppBatterySettings(context)
                dialog.dismiss()
            }
            .setOnDismissListener {
                markSetupGuideShown(context)
                onComplete()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Show a comprehensive battery optimization status dialog
     */
    fun showBatteryOptimizationStatus(context: Context) {
        val isOptimizationDisabled = isBatteryOptimizationDisabled(context)
        val isSamsung = isSamsungDevice()
        
        val message = if (isSamsung) {
            """
            Device: Samsung ${Build.MODEL}
            
            ✓ Battery Optimization: ${if (isOptimizationDisabled) "DISABLED ✓" else "ENABLED (needs fix)"}
            
            ${if (!isOptimizationDisabled) "⚠️ " else ""}For reliable wake word detection on Samsung, you need:
            
            1. Battery optimization disabled
            2. ClawdVoice in "Never sleeping apps"
            3. App battery setting set to "Unrestricted"
            
            Tap buttons below to configure each setting.
            """.trimIndent()
        } else {
            """
            Device: ${Build.MANUFACTURER} ${Build.MODEL}
            
            Battery Optimization: ${if (isOptimizationDisabled) "DISABLED ✓" else "ENABLED (needs fix)"}
            
            ${if (!isOptimizationDisabled) {
                "⚠️ Battery optimization should be disabled for reliable wake word detection."
            } else {
                "✓ Your device is configured correctly for background wake word detection."
            }}
            """.trimIndent()
        }
        
        val builder = AlertDialog.Builder(context)
            .setTitle("Battery Optimization Status")
            .setMessage(message)
            .setPositiveButton("Close", null)
        
        if (!isOptimizationDisabled) {
            builder.setNeutralButton("Disable Optimization") { _, _ ->
                requestBatteryOptimizationExemption(context)
            }
        }
        
        if (isSamsung) {
            builder.setNegativeButton("Samsung Settings") { _, _ ->
                openSamsungBatterySettings(context)
            }
        }
        
        builder.show()
    }
    
    /**
     * Open Samsung-specific battery settings
     * 
     * Tries multiple intents to get to Samsung's battery optimization settings.
     * Falls back to general battery settings if Samsung-specific intents don't work.
     */
    private fun openSamsungBatterySettings(context: Context) {
        // Try Samsung-specific battery settings intent
        val samsungIntents = listOf(
            // One UI 4+ (Android 12+)
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            },
            // One UI 3 (Android 11)
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.sm",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            },
            // Older One UI
            Intent("com.samsung.android.sm.ACTION_BATTERY").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            // Generic battery settings as fallback
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        )
        
        var opened = false
        for (intent in samsungIntents) {
            try {
                context.startActivity(intent)
                opened = true
                Toast.makeText(
                    context,
                    "Add ClawdVoice to 'Never sleeping apps'",
                    Toast.LENGTH_LONG
                ).show()
                break
            } catch (e: Exception) {
                Log.d(TAG, "Intent failed: ${e.message}")
            }
        }
        
        if (!opened) {
            Toast.makeText(
                context,
                "Could not open Samsung battery settings. Please navigate manually:\nSettings → Battery → Background usage limits",
                Toast.LENGTH_LONG
            ).show()
            openAppSettings(context)
        }
    }
    
    /**
     * Open app-specific battery settings
     */
    private fun openAppBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                "Tap Battery → Set to Unrestricted",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings: ${e.message}", e)
            openAppSettings(context)
        }
    }
    
    /**
     * Open battery optimization settings manually (all apps list)
     */
    private fun openBatteryOptimizationSettingsManually(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
            context.startActivity(intent)
            Toast.makeText(
                context,
                "Find ClawdVoice and disable battery optimization",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings: ${e.message}", e)
        }
    }
    
    /**
     * Open general app settings page
     */
    private fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings: ${e.message}", e)
        }
    }
}
