# NoxDroid (NoxCore Trial Client)

Minimal native Android client project for early NoxCore connectivity validation.

## What this trial app does (real)
- Native Kotlin Android app (Gradle / Android Studio structure)
- Form fields for:
  - Server endpoint (`host:port`)
  - Shared secret
  - Client ID
- Connect/disconnect button and status text
- Real TCP socket reachability check to the provided endpoint
- Sends a lightweight trial greeting payload after TCP connect (`trial-connect:<clientId>`)

## What is placeholder / not production yet
- No encrypted/authenticated NoxCore protocol implementation yet
- No persistent tunnel, packet forwarding, or VPNService integration yet
- Shared secret is validated for non-empty input only (not cryptographically used yet)
- Disconnect currently resets UI state (no long-lived background connection)

## Requirements
- Android Studio Ladybug+ (or equivalent)
- Android SDK with API 35 platform
- JDK 17+
- Recommended: Gradle 8.7+ (if regenerating wrapper locally)

## Build steps
1. From project root, ensure wrapper is executable:
   ```bash
   chmod +x gradlew
   ```
2. Build debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```
3. APK output path:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Environment note
- This repository includes wrapper scripts and wrapper metadata in Android Studio style.
- In this container, wrapper execution could not be fully validated due system Gradle packaging limitations and restricted network/bootstrap behavior.
- On a normal development machine, if wrapper bootstrap fails, run `gradle wrapper --gradle-version 8.7` (with a modern Gradle install) once, then re-run `./gradlew :app:assembleDebug`.

## Run notes (Xiaomi HyperOS / modern Android)
- Uses only standard Android components and INTERNET permission.
- Compatible baseline is `minSdk 29` (Android 10+) with `targetSdk 35`.
- For real always-on tunneling in future versions, HyperOS battery/background limits must be handled with foreground service and user-guided battery exception flow.

## Project structure
- `app/src/main/java/com/noxcore/noxdroid/ui/MainActivity.kt`: UI + interaction state
- `app/src/main/java/com/noxcore/noxdroid/core/connection/SocketConnectionService.kt`: trial socket connect test abstraction
- `ROADMAP.md`: next-phase plan to full mobile client
