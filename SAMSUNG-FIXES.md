# Samsung Battery Optimization Fixes

## Problem Statement

Samsung's One UI implements **extremely aggressive battery optimization** that kills background services using the microphone when the screen turns off. This is more aggressive than stock Android's Doze mode and can completely prevent wake word detection apps from functioning.

### Specific Samsung Issues

1. **Deep Sleep Mode**: Samsung's "Put apps to sleep" and "Deep sleeping apps" features kill background services even when they're marked as foreground services
2. **Microphone Access Termination**: One UI specifically targets apps using the microphone in the background, considering them battery drains
3. **Service Killer**: Even with `START_STICKY` and proper foreground service implementation, Samsung's battery optimization will kill the service within seconds of screen-off
4. **Multiple Optimization Layers**: Samsung has at least 3 layers of battery optimization that all need to be configured

## Solutions Implemented

### 1. Foreground Service with FOREGROUND_SERVICE_TYPE_MICROPHONE ✅

**File: `WakeWordService.kt`**

```kotlin
// Tell Android this is a microphone-using foreground service (Android 14+)
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    startForeground(NOTIFICATION_ID, buildNotification(), 
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
}
```

**What it does:**
- Shows persistent "Clawd is listening..." notification
- Declares service type as `microphone` (Android 14+)
- Makes the service high priority for the system

**AndroidManifest.xml:**
```xml
<service
    android:name=".WakeWordService"
    android:foregroundServiceType="microphone" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 2. Partial Wake Lock ✅

**File: `WakeWordService.kt`**

```kotlin
private fun acquireWakeLock() {
    val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "ClawdVoice::WakeWordWakeLock"
    ).apply {
        acquire(24 * 60 * 60 * 1000L) // Max 24 hours
    }
}
```

**What it does:**
- Keeps CPU awake at minimum power while screen is off
- Allows screen to turn off (not a full wake lock)
- Prevents deep sleep that would kill microphone access
- Released automatically when service is destroyed

**AndroidManifest.xml:**
```xml
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### 3. Battery Optimization Exemption ✅

**File: `SamsungBatteryHelper.kt`**

```kotlin
fun requestBatteryOptimizationExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent)
}
```

**What it does:**
- Exempts app from Android's Doze mode
- Prevents system from restricting background activity
- Shows system dialog to request user permission

**AndroidManifest.xml:**
```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

### 4. Samsung-Specific Setup Guide ✅

**File: `SamsungBatteryHelper.kt`**

Shows a comprehensive setup dialog when wake word is first enabled on Samsung devices:

```kotlin
fun showSamsungSetupGuide(context: Context, onComplete: () -> Unit) {
    AlertDialog.Builder(context)
        .setTitle("Samsung Setup Required")
        .setMessage("""
            1️⃣ BATTERY OPTIMIZATION - Disable for ClawdVoice
            2️⃣ NEVER SLEEPING APPS - Add ClawdVoice
            3️⃣ APP BATTERY SETTINGS - Set to Unrestricted
        """)
        // ... buttons to open each setting
}
```

**What it does:**
- Detects Samsung devices (`Build.MANUFACTURER == "samsung"`)
- Shows once on first wake word enable
- Provides direct links to 3 critical Samsung settings:
  1. Battery optimization exemption
  2. "Never sleeping apps" list
  3. Per-app battery setting (Unrestricted)

**Samsung settings paths:**
- Settings → Battery → Background usage limits → Never sleeping apps
- Settings → Apps → ClawdVoice → Battery → Unrestricted

### 5. Service Restart Mechanisms ✅

**File: `WakeWordService.kt`**

#### A. START_STICKY
```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // ...
    return START_STICKY // Android will restart if killed
}
```

#### B. AlarmManager Backup
```kotlin
private fun scheduleRestartAlarm() {
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    // Check/restart every 15 minutes
    alarmManager.setExactAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        triggerAtMillis,
        pendingIntent
    )
}
```

#### C. Task Removal Handler
```kotlin
override fun onTaskRemoved(rootIntent: Intent?) {
    // Restart service when user swipes app away
    val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.set(AlarmManager.RTC_WAKEUP, 
        System.currentTimeMillis() + 1000, 
        restartPendingIntent)
}
```

**What it does:**
- **START_STICKY**: Android will restart the service if it's killed by low memory
- **AlarmManager**: Even if Samsung kills the service, AlarmManager will check every 15 minutes and restart if needed
- **onTaskRemoved**: Handles user swiping app away from recent apps

**AndroidManifest.xml:**
```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

### 6. Boot Receiver ✅

**File: `BootReceiver.kt`**

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
        if (settings.isWakeWordEnabled()) {
            WakeWordService.start(context)
        }
    }
}
```

**What it does:**
- Automatically starts wake word service after device reboot
- Only starts if wake word was previously enabled by user
- Ensures "Hey Clawed" works immediately after phone restart

**AndroidManifest.xml:**
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<receiver
    android:name=".BootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

## Updated Files

### Modified Files
1. ✅ `app/src/main/java/com/clawd/voice/WakeWordService.kt` - Added wake lock, restart mechanisms, Samsung workarounds
2. ✅ `app/src/main/java/com/clawd/voice/MainActivity.kt` - Added battery optimization check on startup
3. ✅ `app/src/main/java/com/clawd/voice/SettingsActivity.kt` - Integrated Samsung setup guide
4. ✅ `app/src/main/java/com/clawd/voice/BootReceiver.kt` - Enhanced with logging
5. ✅ `app/src/main/AndroidManifest.xml` - Added all required permissions

### New Files
1. ✅ `app/src/main/java/com/clawd/voice/SamsungBatteryHelper.kt` - Samsung-specific battery optimization helper

## How It Works

### First-Time Setup (Samsung Devices)

1. User enables wake word in Settings
2. App detects it's a Samsung device
3. Shows comprehensive setup dialog with 3 steps:
   - Battery optimization exemption
   - Add to "Never sleeping apps"
   - Set app battery to "Unrestricted"
4. Each button opens the relevant Samsung setting
5. User completes all 3 steps
6. Wake word service starts

### Runtime Behavior

1. **Service starts** as foreground service with microphone type
2. **Wake lock acquired** to prevent CPU deep sleep
3. **Porcupine initialized** for "Hey Clawed" detection
4. **AlarmManager scheduled** as backup restart every 15 minutes
5. **Service runs continuously** with persistent notification
6. **On wake word detected**: MainActivity launches, starts recording
7. **After recording**: Service resumes listening for wake word
8. **If killed by Samsung**: AlarmManager restarts within 15 minutes
9. **If device reboots**: BootReceiver auto-starts service

### User Experience

- ✅ Wake word works even when screen is off
- ✅ Service survives app being swiped away
- ✅ Service auto-restarts after reboot
- ✅ Service survives overnight (with proper Samsung settings)
- ✅ Clear notification shows listening status
- ✅ One-time setup guide (Samsung only)

## Testing Checklist

### Before Testing
- [ ] Compile and install app on Samsung device
- [ ] Enable wake word in Settings
- [ ] Complete Samsung setup guide (all 3 steps)

### Basic Tests
- [ ] Say "Hey Clawed" with screen on → Should trigger
- [ ] Say "Hey Clawed" with screen off → Should trigger
- [ ] Swipe app away from recent apps → Service should continue
- [ ] Wait 30 seconds → Service should still be running
- [ ] Reboot device → Service should auto-start

### Samsung-Specific Tests
- [ ] Screen off for 5 minutes → Wake word should still work
- [ ] Screen off overnight → Wake word should still work
- [ ] Battery saver mode enabled → Wake word should still work
- [ ] Check battery usage → ClawdVoice should show as "Unrestricted"
- [ ] Check "Never sleeping apps" → ClawdVoice should be listed

### Failure Scenarios
- [ ] If killed manually → AlarmManager should restart within 15 min
- [ ] If user denies battery exemption → Toast warning shown
- [ ] If permissions denied → App requests them again

## Known Limitations

1. **User Must Complete Setup**: Samsung's battery optimization is so aggressive that all 3 setup steps must be completed. The app cannot bypass this programmatically.

2. **AlarmManager Restart Delay**: If Samsung kills the service, it may take up to 15 minutes for AlarmManager to restart it. This is a tradeoff between battery life and responsiveness.

3. **One UI Variations**: Different Samsung devices and One UI versions may have slightly different settings paths. The app tries multiple intents to find the right settings page.

4. **Battery Drain**: Wake lock and continuous microphone access will increase battery usage. This is unavoidable for continuous wake word detection.

## Future Improvements

1. **Configurable Restart Interval**: Let power users choose between 5/15/30 minute AlarmManager intervals
2. **Battery Usage Stats**: Show estimated battery impact in Settings
3. **Smart Pause**: Auto-pause wake word detection during known sleep hours
4. **Better Samsung Detection**: Detect One UI version and provide version-specific guidance

## References

- [Android Foreground Services](https://developer.android.com/guide/components/foreground-services)
- [Wake Locks](https://developer.android.com/training/scheduling/wakelock)
- [Battery Optimization](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Samsung One UI Battery Optimization](https://dontkillmyapp.com/samsung)

## Support

If wake word detection still doesn't work after following all steps:

1. Check `adb logcat | grep WakeWordService` for errors
2. Verify all 3 Samsung battery settings are configured
3. Check if any third-party battery apps are installed
4. Try disabling Samsung's "Adaptive Battery" feature
5. Ensure device isn't in "Maximum Power Saving" mode
