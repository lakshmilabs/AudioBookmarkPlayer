# Audio Bookmark Player

Android audio player with bookmark export functionality for MIUI.

## Features
- Open audio files from file manager using "Open with"
- Variable playback speed (1.0x, 1.25x, 1.5x, 1.75x, 2.0x)
- Add bookmarks while playing
- Export bookmarks to `/storage/emulated/0/_Edit-times/`
- Output format: filename + timestamps in hh:mm:ss

## Build APK

### Using GitHub Actions (Automatic)
1. Push code to GitHub
2. GitHub Actions will automatically build the APK
3. Download from Actions tab → latest workflow run → Artifacts

### Manual Build (if needed)
```bash
./gradlew assembleDebug
```
APK will be in: `app/build/outputs/apk/debug/app-debug.apk`

## Installation
1. Download APK from GitHub releases or Actions artifacts
2. Install on Android device
3. Grant storage permissions
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
