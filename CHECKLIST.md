# Samsung Fixes Implementation Checklist

## Files Created ✅

- [x] `app/src/main/java/com/clawd/voice/SamsungBatteryHelper.kt`
- [x] `SAMSUNG-FIXES.md` (comprehensive technical documentation)
- [x] `IMPLEMENTATION-SUMMARY.md` (quick reference guide)
- [x] `CHECKLIST.md` (this file)

## Files Modified ✅

- [x] `app/src/main/java/com/clawd/voice/WakeWordService.kt`
  - Added partial wake lock acquisition/release
  - Added AlarmManager-based restart mechanism
  - Added onTaskRemoved handler
  - Added Samsung-specific comments
  - Updated notification to show "Clawd is listening..."
  
- [x] `app/src/main/java/com/clawd/voice/MainActivity.kt`
  - Added checkBatteryOptimization() method
  - Added warning toast for Samsung devices

- [x] `app/src/main/java/com/clawd/voice/SettingsActivity.kt`
  - Integrated SamsungBatteryHelper.showSamsungSetupGuide()
  - Added battery status button handler
  - Calls Samsung setup guide on first wake word enable

- [x] `app/src/main/java/com/clawd/voice/BootReceiver.kt`
  - Enhanced with debug logging
  - Added comments explaining Samsung workaround

- [x] `app/src/main/AndroidManifest.xml`
  - Added `WAKE_LOCK` permission
  - Added `SCHEDULE_EXACT_ALARM` permission
  - Added comments explaining each Samsung-specific permission
  - Verified `FOREGROUND_SERVICE_MICROPHONE` permission
  - Verified `foregroundServiceType="microphone"` on service

- [x] `app/src/main/res/layout/activity_settings.xml`
  - Added "Battery Optimization Status" button

## Features Implemented ✅

### 1. Foreground Service with FOREGROUND_SERVICE_TYPE_MICROPHONE
- [x] Service uses startForeground() with ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
- [x] Persistent notification shows "Clawd is listening..."
- [x] Manifest declares foregroundServiceType="microphone"
- [x] POST_NOTIFICATIONS permission requested

### 2. Battery Optimization Exemption
- [x] SamsungBatteryHelper.requestBatteryOptimizationExemption()
- [x] Checks if already exempted before requesting
- [x] Uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS intent
- [x] Fallback to manual settings if intent fails

### 3. Partial Wake Lock
- [x] PowerManager.PARTIAL_WAKE_LOCK acquired in onCreate()
- [x] Wake lock held with 24-hour timeout
- [x] Wake lock released in onDestroy()
- [x] Proper isHeld check before release
- [x] WAKE_LOCK permission in manifest

### 4. Samsung-Specific Workarounds
- [x] Device detection (Build.MANUFACTURER == "samsung")
- [x] One-time setup guide with 3 steps
- [x] Direct links to Samsung battery settings
- [x] Deep link intents for Samsung settings pages
- [x] Fallback to manual navigation if intents fail
- [x] Setup guide shown only once per install

### 5. Service Restart on Kill
- [x] onStartCommand returns START_STICKY
- [x] AlarmManager schedules restart every 15 minutes
- [x] setExactAndAllowWhileIdle for Doze compatibility
- [x] onTaskRemoved handler restarts service
- [x] Restart alarm canceled on normal stop
- [x] SCHEDULE_EXACT_ALARM permission in manifest

### 6. Updated AndroidManifest.xml
- [x] FOREGROUND_SERVICE permission
- [x] FOREGROUND_SERVICE_MICROPHONE permission
- [x] WAKE_LOCK permission
- [x] RECEIVE_BOOT_COMPLETED permission
- [x] REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission
- [x] SCHEDULE_EXACT_ALARM permission
- [x] Service registered with foregroundServiceType="microphone"
- [x] BootReceiver registered with BOOT_COMPLETED filter

## Code Quality ✅

- [x] All Samsung workarounds marked with `// SAMSUNG WORKAROUND:` comments
- [x] Comprehensive inline documentation
- [x] Kotlin best practices followed
- [x] Proper error handling with try-catch blocks
- [x] Null safety checks
- [x] Resource cleanup in onDestroy()
- [x] No memory leaks (wake lock released, references nulled)

## Testing Checklist ⏳

### Pre-Test Setup
- [ ] Build app: `./gradlew assembleDebug`
- [ ] Install on Samsung device: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- [ ] Install on non-Samsung device (if available)
- [ ] Clear app data to test first-time flow

### Samsung Device Tests
- [ ] Enable wake word → Samsung setup guide appears
- [ ] Complete all 3 setup steps
- [ ] Verify "Never sleeping apps" shows ClawdVoice
- [ ] Verify battery optimization is disabled
- [ ] Verify app battery is "Unrestricted"
- [ ] Say "Hey Clawed" with screen on → triggers
- [ ] Say "Hey Clawed" with screen off → triggers
- [ ] Swipe app away → service continues (check notification)
- [ ] Wait 5 minutes → wake word still works
- [ ] Check Settings → Battery Status → shows "DISABLED ✓"
- [ ] Reboot device → service auto-starts
- [ ] Check logs for wake lock messages
- [ ] Check logs for AlarmManager schedule messages

### Non-Samsung Device Tests
- [ ] Enable wake word → standard battery exemption request
- [ ] Approve battery exemption
- [ ] Say "Hey Clawed" with screen on → triggers
- [ ] Say "Hey Clawed" with screen off → triggers
- [ ] Swipe app away → service continues
- [ ] Reboot device → service auto-starts

### Edge Cases
- [ ] Deny battery optimization → warning toast shown
- [ ] Disable wake word → service stops, wake lock released
- [ ] Re-enable wake word → setup guide NOT shown again (Samsung)
- [ ] Force stop app → service doesn't restart (expected)
- [ ] Kill service via adb → AlarmManager restarts within 15 min
- [ ] Low battery mode → service still works
- [ ] Airplane mode → service still works (no network needed)

### Performance Tests
- [ ] Monitor battery drain over 1 hour
- [ ] Monitor battery drain overnight
- [ ] Check CPU usage (should be minimal when idle)
- [ ] Check memory usage (should be stable)
- [ ] No ANRs or crashes
- [ ] No repeated restart loops

### Log Verification
```bash
# Watch service lifecycle
adb logcat | grep WakeWordService

# Watch for wake lock messages
adb logcat | grep "Wake lock"

# Watch for AlarmManager
adb logcat | grep "Restart alarm"

# Watch for Samsung-specific
adb logcat | grep SamsungBatteryHelper

# Watch for boot events
adb logcat | grep BootReceiver
```

## Documentation ✅

- [x] SAMSUNG-FIXES.md created (11 KB, comprehensive technical docs)
- [x] IMPLEMENTATION-SUMMARY.md created (8 KB, quick reference)
- [x] CHECKLIST.md created (this file)
- [x] Code comments explain all Samsung workarounds
- [x] README updated with Samsung notes (if applicable)

## Build Verification ⏳

- [ ] App compiles without errors: `./gradlew assembleDebug`
- [ ] No lint errors: `./gradlew lint`
- [ ] APK size reasonable (should be <20 MB)
- [ ] Min SDK version check (should be 21 or higher)
- [ ] Target SDK version check (should be 34 for Android 14)

## Known Issues / Limitations

1. **AlarmManager Delay**: If Samsung kills service, may take up to 15 min to restart
   - **Workaround**: User can manually open app to restart immediately
   
2. **Battery Drain**: Continuous wake word + wake lock = +10-20% battery per day
   - **Expected**: This is inherent to continuous voice detection
   
3. **Samsung Settings Paths**: May vary by One UI version
   - **Mitigation**: App tries multiple intents, provides manual instructions
   
4. **User Must Complete Setup**: Cannot bypass Samsung's battery settings programmatically
   - **Mitigation**: Clear step-by-step guide with direct links

## Sign-Off

### Code Review
- [ ] Reviewed by: _______________
- [ ] Date: _______________

### Testing
- [ ] Tested on Samsung device: _______________
- [ ] Tested on non-Samsung device: _______________
- [ ] Date: _______________

### Deployment
- [ ] Ready for production: Yes / No
- [ ] Approved by: _______________
- [ ] Date: _______________

## Quick Commands

```bash
# Build
./gradlew assembleDebug

# Install
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
adb shell am start -n com.clawd.voice/.MainActivity

# Watch logs
adb logcat | grep -E "WakeWordService|SamsungBatteryHelper"

# Check if service is running
adb shell dumpsys activity services | grep WakeWordService

# Check battery stats
adb shell dumpsys batterystats | grep -A 10 com.clawd.voice

# Force stop (testing)
adb shell am force-stop com.clawd.voice

# Clear data (fresh install testing)
adb shell pm clear com.clawd.voice
```

---

**Implementation Complete**: ✅  
**Testing Status**: ⏳ Pending  
**Date**: 2026-02-18
