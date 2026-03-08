# NoxDroid (NoxJ Android Client Foundation)

Native Kotlin Android app progressing from a handshake probe toward a real VPN client.

## What is implemented now
- Existing NoxCore handshake tester is preserved:
  - Input fields for `wss://` server URL, shared secret, and client ID
  - Real TLS socket + WebSocket upgrade
  - Real Nox `hello` -> `hello_ack` HMAC validation
- Android VPN foundation is implemented:
  - `VpnService` subclass (`NoxVpnService`)
  - System VPN permission flow (`VpnService.prepare(...)`)
  - Foreground service notification + stop action
  - Real `VpnService.Builder` session/interface setup (`establish()`)
  - Start/stop control from UI
- First real forwarding path is implemented:
  - Real TUN read + write loop
  - IPv4/TCP parse and per-flow session tracking
  - Constrained user-space TCP forwarder:
    - accepts outbound client SYN packets from TUN
    - opens protected upstream socket (`VpnService.protect`) to destination
    - synthesizes TCP responses back into TUN (SYN-ACK/ACK/PSH/FIN)
    - forwards payload uplink/downlink bytes for established sessions

## Important current limits (honest status)
- This is not full VPN usability yet.
- Forwarding is limited to a constrained IPv4/TCP subset.
- UDP is not forwarded.
- TCP handling is minimal and does not implement full RFC-grade behavior (retransmission/window management/selective ACK/etc.).
- Nox transport integration for data plane is not implemented yet; forwarding currently uses direct protected sockets.

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
- `app/src/main/java/com/noxcore/noxdroid/ui/MainActivity.kt`: handshake UI + VPN permission/start/stop flow + forwarding stats display
- `app/src/main/java/com/noxcore/noxdroid/core/connection/SocketConnectionService.kt`: WSS + Nox `hello`/`hello_ack` handshake probe
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/NoxVpnService.kt`: foreground `VpnService` + TUN packet loop wiring
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/NoxVpnState.kt`: VPN runtime state model
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/PacketParser.kt`: minimal IPv4/TCP parser foundation
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TcpSessionTracker.kt`: TCP flow/session mapping groundwork
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TcpTunForwarder.kt`: constrained TCP forwarding between TUN and protected upstream sockets
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TunPacketLoop.kt`: TUN loop + forwarding stats and lifecycle
- `ROADMAP.md`: next iterations
