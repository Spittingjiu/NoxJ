# NoxDroid (NoxCore WSS Handshake Tester)

Native Kotlin Android client for real NoxCore-style secure WebSocket handshake validation.

## What this app does (real)
- Native Kotlin Android app (Gradle / Android Studio structure)
- Form fields for:
  - Server WebSocket URL (`wss://host/path`)
  - Shared secret
  - Client ID
- Runs a real secure WebSocket (`wss`) handshake test
- Sends a JSON `hello` frame with:
  - `client_id`
  - `timestamp_ms`
  - `nonce`
  - HMAC-SHA256 signature (`signature_b64`) over `client_id:timestamp_ms:nonce` using the provided shared secret
- Waits for and validates a server JSON `hello_ack` frame
- Reports pass/fail in UI status text with handshake-specific errors

## What is still missing / not production yet
- No persistent tunnel, packet forwarding, or VPNService integration yet
- No full binary protocol/frame layer beyond hello/hello_ack subset
- If server responds only with binary frames, app reports this as unsupported for now
- No reconnect manager, heartbeat loop, or background foreground-service lifecycle yet

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
- `app/src/main/java/com/noxcore/noxdroid/core/connection/SocketConnectionService.kt`: real `wss` hello/hello_ack handshake test
- `ROADMAP.md`: next-phase plan to full mobile client
