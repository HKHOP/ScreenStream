# ScreenStream — Android Screen Casting App

Stream your Android screen over Wi-Fi to any device using VLC, a browser,
or any MJPEG-compatible media player.

## How it works

1. App uses Android's **MediaProjection API** to capture your screen
2. Frames are encoded as JPEG and pushed via a built-in **HTTP MJPEG server**
3. Any client on the same Wi-Fi can open the stream URL in VLC or a browser

## Setup in Android Studio

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34 (API level 34)
- A physical Android device (emulators don't support MediaProjection well)

### Steps

1. **Open the project**
   - Launch Android Studio
   - File → Open → select the `ScreenStream` folder

2. **Sync Gradle**
   - Android Studio will prompt to sync — click "Sync Now"
   - It will download Gradle 8.4 and all dependencies automatically

3. **Enable Developer Options on your phone**
   - Settings → About Phone → tap "Build Number" 7 times
   - Settings → Developer Options → enable "USB Debugging"

4. **Run the app**
   - Connect your phone via USB
   - Click the ▶ Run button in Android Studio
   - Select your device

## Using the app

1. Open **ScreenStream** on your Android phone
2. Tap **Start Streaming**
3. Accept the screen capture permission dialog
4. The app shows a URL like `http://192.168.1.100:8080`

### Watch on VLC (PC/Mac/another phone)
- Open VLC → Media → Open Network Stream
- Paste the URL → Play

### Watch in a browser
- Open Chrome or Firefox
- Navigate to the URL shown in the app

### Watch on a Smart TV
- Open VLC on the TV (or any IPTV app that supports MJPEG)
- Add the stream URL

## Architecture

```
MainActivity.kt         — UI, permissions, service control
ScreenStreamService.kt  — Foreground service, MediaProjection + VirtualDisplay
HttpMjpegServer.kt      — Lightweight HTTP server, MJPEG multipart streaming
```

## Customization

In `ScreenStreamService.kt`:
- `SCALE = 0.5f`     → change stream resolution (0.25–1.0)
- `JPEG_QUALITY = 65` → change quality/bandwidth tradeoff (1–100)
- `PORT = 8080`       → change streaming port

## Notes

- Both devices **must be on the same Wi-Fi network**
- The stream uses MJPEG (Motion JPEG) — it works everywhere but is not as
  bandwidth-efficient as H.264. Typical usage: ~2–5 Mbps at 50% scale.
- The app requires Android 6.0 (API 23) or higher
- Screen capture runs in a foreground service so it keeps working when
  you switch to other apps
