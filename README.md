# NoxDroid (NoxJ Android Client Foundation)

Native Kotlin Android app progressing from a handshake probe toward a real VPN client.

## What is implemented now
- Existing NoxCore handshake tester is preserved:
  - Input fields for `wss://` server URL, shared secret, and client ID
  - Real TLS socket + WebSocket upgrade
  - Real Nox `hello` -> `hello_ack` HMAC validation
- New Android VPN foundation is implemented:
  - `VpnService` subclass (`NoxVpnService`)
  - System VPN permission flow (`VpnService.prepare(...)`)
  - Foreground service notification + stop action
  - Real `VpnService.Builder` session/interface setup (`establish()`)
  - Start/stop control from UI

## Important current limit (honest status)
- The VPN data plane is not implemented yet.
- No packet forwarding between TUN and Nox transport is running in this iteration.
- Current VPN mode is control-plane skeleton only: it establishes a real Android VPN interface and service lifecycle.

## HyperOS / modern Android notes
- VPN runs as a foreground service to survive aggressive background limits.
- Users may still need to disable battery restrictions for reliable long-running operation on HyperOS-class ROMs.

## Build
1. Ensure wrapper is executable:
   ```bash
   chmod +x gradlew
   ```
2. Build debug APK:
   ```bash
   ./gradlew :app:assembleDebug
   ```
3. Output:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

## Project layout
- `app/src/main/java/com/noxcore/noxdroid/ui/MainActivity.kt`: handshake UI + VPN permission/start/stop UI flow
- `app/src/main/java/com/noxcore/noxdroid/core/connection/SocketConnectionService.kt`: WSS + Nox `hello`/`hello_ack` handshake probe
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/NoxVpnService.kt`: foreground `VpnService` skeleton and session setup
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/NoxVpnState.kt`: VPN runtime state model
- `ROADMAP.md`: next iterations
