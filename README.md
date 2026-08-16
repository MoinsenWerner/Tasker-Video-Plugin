# Tasker Camera Plugin

Android/Tasker plugin scaffold based on João Dias' TaskerPluginSample. It exposes Tasker actions for starting, pausing, resuming and stopping background video recordings, taking photos, extracting frames from videos and documenting the Android limitation around selectively blocking audio from legacy Tasker scenes.

The plugin uses the Tasker plugin library pattern from `TaskerPluginSample` and contains direct Camera2 integration points (`CameraManager`/lens selection) rather than delegating to installed camera apps with camera intents. Background capture is hosted by a foreground service so Tasker can invoke it while the screen is off, subject to Android camera/microphone permission and OEM background-execution restrictions.


## Debian build

This repository intentionally does not include binary Gradle wrapper or image files. On a Debian server run `./build-debian.sh`; the script installs/downloads OpenJDK, Gradle, Android command-line tools, required SDK packages, then runs `clean test lintDebug assembleDebug`.
