# Native Video Player Module

This module implements a fully native, premium, in-app video playback experience within SkyFiles, replacing external player intent delegations. It is built using the modern **AndroidX Media3 ExoPlayer** stack and is optimized to support local and network filesystems securely and efficiently.

---

## Architecture Overview

The video player module is located under `me.zhanghai.android.files.video` and comprises the following core components:

```
video/
├── VideoPlayerActivity.kt     # Fullscreen Activity inheriting from AppActivity, managing UI & gestures
├── VideoPlayerViewModel.kt    # Main-thread bound state management & player lifecycle coordinator
├── VideoDataSourceFactory.kt  # Custom Media3 DataSources bridging Java NIO2 Paths to ExoPlayer
├── VideoGestureController.kt  # Custom touch controller (brightness, volume, seek, pinch-to-zoom)
├── VideoCrashLogger.kt       # Dedicated video crash logging writing to player-debug.log
└── PlayerDiagnostics.kt       # Diagnostic framework tracking media decoder setups and status
```

---

## Core Components & Responsibilities

### 1. `VideoPlayerActivity`
* **Theme Integration:** Inherits directly from `AppActivity` to ensure consistent theme initialization and prevent layout inflation or style crash issues.
* **UI Controls & Interactivity:** Integrates custom gesture listeners, status indicators, lock overlay, track selectors, and orientation overrides.
* **Diagnostics hook:** Direct lifecycle event tracking (`VIDEO_ACTIVITY_CREATED`, `VIDEO_ACTIVITY_THEME_INITIALIZED`, etc.) written to the dedicated log.

### 2. `VideoPlayerViewModel`
* **Player State:** Initializes and releases the `ExoPlayer` instance on the `Dispatchers.Main.immediate` thread to guarantee strict thread safety.
* **Progress Logging:** Periodically polls playback status and updates without blocking the main event loop.
* **Codec Reporting:** Checks and logs device-specific media capabilities, hardware acceleration support, and decoders.

### 3. `VideoDataSourceFactory`
Bridges SkyFiles' custom NIO virtual filesystem providers (SMB, FTP, SFTP, WebDAV) and standard local files to the Media3 engine:
* **`NioPathDataSource` (Mode A):** Used for seekable, local/Linux system paths via direct `SeekableByteChannel` bindings.
* **`StreamingPathDataSource` (Mode B):** Used for non-seekable streams and network protocols (FTP, WebDAV, SMB, etc.). Bypasses direct filesystem root access restrictions by calling the application's `ContentResolver` to open secure `fileProviderUri` streams.
* **Auto-Subtitle Discovery:** Scans parent folders automatically for matching subtitle formats (`.srt`, `.ass`, `.ssa`, `.vtt`) with the same base name and attaches them to the media item configurations.

### 4. `VideoGestureController`
* Handles swipe-based controls:
  * Left side vertical scroll: Brightness adjustments.
  * Right side vertical scroll: Audio volume adjustments.
  * Horizontal scroll: Linear seeking across playback timeline.
* Interacts directly with the activity to display smooth overlays and feedback indicators.

---

## Diagnostics & Troubleshooting

To troubleshoot video failures without using Android ADB, a dedicated logging system writes detailed tracing and exception traces to a local diagnostic log.

* **Log Location:** `Download/SkyFiles/player-debug.log` (falling back to `/Android/data/me.zhanghai.android.files/files/SkyFiles/player-debug.log` if external storage permissions are unavailable).
* **Crash Interceptor:** A global crash handler hook is set in `SkyFilesApplication` to catch any unhandled exceptions occurring inside classes from `me.zhanghai.android.files.video` and dump them to `player-debug.log`.
* **Suppressing Noise:** Routine SMB debug/DNS/TCP handshake logs are silenced (only printing `ERROR` logs) to keep the primary logs focused.

---

## Thread Safety Design

ExoPlayer is strict about thread access. To prevent `IllegalStateException: Player is accessed on the wrong thread` crashes:
1. All queries to `player` settings, position, state, and properties are executed on `Dispatchers.Main` / `Dispatchers.Main.immediate`.
2. Asynchronous operations (like writing logging profiles to file storage) copy the required values from the main thread first, then dispatch to `Dispatchers.IO`.
