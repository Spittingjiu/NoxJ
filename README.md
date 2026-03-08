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
- First real data-plane step is implemented:
  - Real TUN read loop from the VPN interface file descriptor
  - Packet classification: non-IPv4, IPv4 non-TCP, IPv4 TCP, malformed
  - Minimal IPv4/TCP parsing foundation (ports, flags, seq/ack, payload length)
  - Internal TCP session mapping groundwork keyed by flow tuple

## Important current limit (honest status)
- No packet forwarding between TUN and Nox transport is implemented yet.
- Current VPN mode is capture/classification groundwork only, not full forwarding.

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
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/NoxVpnService.kt`: foreground `VpnService` + TUN packet loop wiring
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/NoxVpnState.kt`: VPN runtime state model
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/PacketParser.kt`: minimal IPv4/TCP parser foundation
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TcpSessionTracker.kt`: TCP flow/session mapping groundwork
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TunPacketLoop.kt`: packet capture loop and stats
- `ROADMAP.md`: next iterations
