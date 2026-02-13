# Audio Bookmark Player

Android audio player with bookmark export functionality for MIUI.

## Features
- Open audio files from file manager using "Open with"
- Variable playback speed (1.0x, 1.25x, 1.5x, 1.75x, 2.0x)
- Add bookmarks while playing
- Export bookmarks to `/storage/emulated/0/_Edit-times/`
- Output format: filename + timestamps in hh:mm:ss

## Build APK

### Using GitHub Actions (Recommended)
1. Push code to GitHub
2. GitHub Actions automatically builds the APK (no local setup needed)
3. Go to Actions tab → latest workflow run → Artifacts
4. Download `app-debug` (signed and ready to install)

**The debug APK is already signed by Android and works immediately on your device.**

### Local Build (Not Recommended)
Local building requires installing Android SDK and Gradle. It's much easier to use GitHub Actions.

If you really want to build locally:
1. Install Android SDK
2. Install Gradle 7.5+
3. Run: `gradle assembleDebug`

But seriously, just use GitHub Actions - it's automatic and free.

### Signing Release APK (Optional)

GitHub Actions builds an unsigned release APK. To sign it:

**Option 1: Use Debug Build**
- Debug builds are already signed and work perfectly
- Download from GitHub Actions artifacts

**Option 2: Sign Release APK Manually**
1. Create keystore:
```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

2. Sign APK:
```bash
jarsigner -verbose -sigalg SHA256withRSA -digestalg SHA-256 -keystore my-release-key.jks app-release-unsigned.apk my-key-alias
zipalign -v 4 app-release-unsigned.apk app-release-signed.apk
```

**Option 3: Auto-sign via GitHub Actions**
1. Add secrets in GitHub repo settings:
   - `KEYSTORE_BASE64`: Base64 encoded keystore file
   - `KEYSTORE_PASSWORD`: Keystore password
   - `KEY_ALIAS`: Key alias
   - `KEY_PASSWORD`: Key password
2. Run the "Build Signed Release" workflow manually

## Installation
1. Download APK from GitHub Actions artifacts
2. Install on Android device (allow installation from unknown sources)
3. Grant storage permissions when prompted
4. Select audio file → "Open with" → "Audio Bookmark Player"

## Usage
1. Open mp3 file with the app
2. Use Play/Pause and Speed buttons
3. Tap "ADD BOOKMARK" at desired times
4. Tap "EXPORT BOOKMARKS" to save to file
5. File saved to: `/storage/emulated/0/_Edit-times/[filename].txt`

## Requirements
- Android 6.0 (API 23) or higher
- MIUI Global 14.0.1 tested

## Note on Signing
- **Debug builds**: Automatically signed, ready to use
- **Release builds**: Unsigned by default, but still installable on most devices
- For Google Play: You need a signed release APK
