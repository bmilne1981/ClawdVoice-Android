# ClawdVoice for Android

Voice interface for Clawd on Android. Hold the button, speak, and Clawd responds with voice.

## Architecture

```
┌─────────────────┐      HTTP POST       ┌─────────────────┐
│  Android Phone  │ ──────────────────►  │    Mac mini     │
│  ClawdVoice     │                      │  Voice Bridge   │
│  (STT + TTS)    │ ◄────────────────────│  (port 8770)    │
└─────────────────┘      Audio/Text      └─────────────────┘
```

## Building

### Prerequisites
- Android Studio or Android SDK command line tools
- Java 17

### Build from command line

```bash
cd /Users/brianmilne/clawd/apps/ClawdVoice-Android

# Make sure JAVA_HOME is set
export JAVA_HOME=/usr/local/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home

# Build debug APK
./gradlew assembleDebug

# APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

### Install on phone

1. Enable Developer Options on your phone
2. Enable USB Debugging
3. Connect via USB
4. Run: `adb install app/build/outputs/apk/debug/app-debug.apk`

Or transfer the APK file and install manually.

## Configuration

On first launch, go to Settings (gear icon) and enter your Mac mini's IP address:

```
http://192.168.1.197:8770
```

Use "Test Connection" to verify it works.

## Voice Bridge Server

The app connects to the voice bridge server running on your Mac mini. Make sure it's running:

```bash
# On Mac mini
cd ~/clawd/extensions/voice-bridge
node server.js
```

## Features

- **Push-to-talk**: Hold the mic button to speak
- **2-second trailing delay**: Captures your full sentence even if you release early
- **Android STT**: Uses Google's speech recognition
- **ElevenLabs TTS**: Voice bridge returns audio (base64 MP3), played back in Clawd's voice
- **Dark theme**: Matches the macOS app aesthetic

## Permissions

- **RECORD_AUDIO**: For speech recognition
- **INTERNET**: To communicate with voice bridge server

## Troubleshooting

### "Network error" or timeout
- Check that your phone is on the same WiFi network as your Mac mini
- Verify the voice bridge server is running
- Make sure the IP address is correct in Settings

### Speech not recognized
- Check microphone permissions in Android Settings
- Speak clearly and close to the phone
- Try in a quieter environment

### No audio playback
- Check phone volume
- Some responses may be text-only if TTS fails

## TODO

- [ ] Wake word detection ("Hey Clawd")
- [ ] Widget for home screen
- [ ] Notification quick action
- [ ] Wear OS companion app
