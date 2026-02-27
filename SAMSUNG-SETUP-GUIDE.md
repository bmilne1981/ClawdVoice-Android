# Samsung Battery Optimization - User Setup Guide

## Why This Matters

Samsung phones are **very aggressive** about killing apps that use the microphone in the background. Without completing ALL 3 steps below, ClawdVoice will stop listening when your screen turns off.

**Expected time**: 2-3 minutes  
**Frequency**: One-time setup

---

## ✅ Step 1: Disable Battery Optimization

This tells Android to not put ClawdVoice to sleep.

### Using the App
1. Open **ClawdVoice**
2. Go to **Settings**
3. Enable **"Always Listening"**
4. Tap **"Disable Optimization"** button
5. Find **ClawdVoice** in the list
6. Select **"Don't optimize"**
7. Tap **Done**

### Manual Path
```
Settings
└── Apps
    └── (⋮ menu) Special access
        └── Optimize battery usage
            └── All apps (dropdown at top)
                └── ClawdVoice → Don't optimize
```

**✓ Verification**: ClawdVoice should NOT appear in "Apps optimizing battery usage"

---

## ✅ Step 2: Add to "Never Sleeping Apps"

This is Samsung's additional layer of battery optimization.

### Using the App
1. Open **ClawdVoice Settings**
2. Tap **"Samsung Battery Settings"** button
3. Tap **"Background usage limits"**
4. Tap **"Never sleeping apps"**
5. Tap **"+ Add apps"**
6. Select **ClawdVoice**
7. Tap **Add**

### Manual Path (One UI 4+)
```
Settings
└── Battery and device care
    └── Battery
        └── Background usage limits
            └── Never sleeping apps
                └── + Add apps
                    └── ClawdVoice
```

### Manual Path (One UI 3)
```
Settings
└── Device care
    └── Battery
        └── App power management
            └── Apps that won't be put to sleep
                └── + Add apps
                    └── ClawdVoice
```

**✓ Verification**: ClawdVoice appears in "Never sleeping apps" list

---

## ✅ Step 3: Set App Battery to "Unrestricted"

This is per-app battery control.

### Using the App
1. Open **ClawdVoice Settings**
2. Tap **"App Settings"** button
3. Tap **"Battery"**
4. Select **"Unrestricted"**

### Manual Path
```
Settings
└── Apps
    └── ClawdVoice
        └── Battery
            └── Unrestricted (select this option)
```

**Options explained:**
- 🔴 **Optimized**: Samsung will kill the app (DON'T USE)
- 🟡 **Restricted**: App won't run in background at all (DON'T USE)
- 🟢 **Unrestricted**: App can run freely in background (USE THIS)

**✓ Verification**: Battery shows "Unrestricted" in ClawdVoice app info

---

## 🔍 How to Verify Everything Works

### Quick Test (30 seconds)
1. Say **"Hey Clawed"** with screen on → Should work ✓
2. Turn screen off
3. Wait 5 seconds
4. Say **"Hey Clawed"** → Should work ✓

### Overnight Test
1. Enable wake word before bed
2. Leave phone overnight
3. Morning: Say **"Hey Clawed"** → Should work ✓

### Check Status in App
1. Open **ClawdVoice**
2. Go to **Settings**
3. Tap **"Battery Optimization Status"**
4. Should show: **"DISABLED ✓"**
5. All indicators should be green

---

## ⚠️ Troubleshooting

### "Hey Clawed" stops working after screen turns off

**Problem**: Samsung's battery optimization is still active.

**Solution**: Double-check all 3 steps above. You must complete ALL of them.

**Quick check**:
```
Settings → Apps → ClawdVoice → Battery
Should show: "Unrestricted"
```

### Service stops after a few minutes

**Problem**: ClawdVoice is in "Sleeping apps" list.

**Solution**: 
```
Settings → Battery → Background usage limits → Sleeping apps
If ClawdVoice is listed here, remove it
Then add it to "Never sleeping apps"
```

### App stops after swiping away

**Problem**: Samsung is killing the foreground service.

**Solution**: Complete all 3 steps above. The app should continue running even after swiping away.

**Verify**:
- You should see a persistent notification: "Clawd is listening..."
- This notification should NOT disappear when you swipe the app away

### Works for 15 minutes then stops

**Problem**: Battery optimization is re-enabling itself (rare).

**Solution**: 
1. Restart phone
2. Repeat all 3 setup steps
3. Don't use third-party battery optimization apps

### Still not working?

**Last resort checks**:

1. **Maximum Power Saving Mode**: Turn it OFF
   ```
   Settings → Battery → Power mode → Normal
   ```

2. **Adaptive Battery**: Try turning it OFF
   ```
   Settings → Battery → More battery settings → Adaptive battery → OFF
   ```

3. **Third-party battery apps**: Uninstall any "battery saver" apps

4. **Samsung Game Launcher**: If installed, don't add ClawdVoice to it

5. **Check app permissions**:
   ```
   Settings → Apps → ClawdVoice → Permissions
   Microphone: Allowed
   ```

---

## 📊 Expected Battery Usage

With wake word enabled 24/7:

| Usage Pattern | Battery Drain |
|---------------|---------------|
| Screen mostly on (normal use) | +2-5% per day |
| Screen mostly off (sitting idle) | +10-15% per day |
| Overnight (8 hours) | +5-8% |

**Note**: This is the cost of continuous voice detection. It's normal and expected.

---

## 🎯 One UI Version Differences

### One UI 5+ (Android 13+)
- Most restrictive
- All 3 steps are CRITICAL
- "Never sleeping apps" is under "Background usage limits"

### One UI 4 (Android 12)
- Very restrictive
- All 3 steps required
- Settings paths similar to One UI 5

### One UI 3 (Android 11)
- Moderate restrictions
- All 3 steps recommended
- "Never sleeping apps" is under "App power management"

### One UI 2 or older (Android 10-)
- Less restrictive
- Steps 1 and 3 may be sufficient
- "Never sleeping apps" may not exist (skip step 2)

---

## 💡 Pro Tips

1. **Complete setup BEFORE going to bed**: This ensures wake word works overnight.

2. **Test immediately**: Don't wait to find out it's not working—test with screen off right away.

3. **Keep notification visible**: The "Clawd is listening..." notification is your friend. If it disappears, the service was killed.

4. **Restart after updates**: Samsung OS updates may reset battery settings. Re-check after major updates.

5. **Use the status button**: The "Battery Optimization Status" button in Settings is your quick health check.

---

## 🆘 Still Having Issues?

### Check the logs
If you're tech-savvy or working with a developer:

```bash
adb logcat | grep WakeWordService
```

Look for messages like:
- "Wake lock acquired" ✓
- "Wake word detection started" ✓
- "Service destroyed" ✗ (bad if unexpected)

### Contact Support
Provide this info:
- Samsung model: (e.g., Galaxy S21)
- One UI version: Settings → About phone → Software information
- Screenshot of: Settings → Apps → ClawdVoice → Battery

---

## ✅ Setup Complete!

If you've completed all 3 steps and tested with screen off, you're done! 

**"Hey Clawed"** should now work 24/7, even with your screen off.

---

**Last Updated**: February 18, 2026  
**Tested On**: Galaxy S21, S22, S23 (One UI 5.1)  
**App Version**: ClawdVoice v1.0+
