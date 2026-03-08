# NoxDroid (NoxJ Android Client Foundation)

Native Kotlin Android app progressing from a handshake probe toward a real VPN client.

## What is implemented now
- Existing NoxCore handshake tester is preserved:
  - Input fields for `wss://` server URL, shared secret, and client ID
  - Real TLS socket + WebSocket upgrade
  - Real Nox `hello` -> `hello_ack` HMAC validation
  - Handshake probe now uses correct HTTP CRLF framing for more reliable success/failure reporting
- Android VPN foundation is implemented:
  - `VpnService` subclass (`NoxVpnService`)
  - System VPN permission flow (`VpnService.prepare(...)`)
  - Foreground service notification + stop action
  - Real `VpnService.Builder` session/interface setup (`establish()`)
  - Start/stop control from UI
- First real forwarding path is implemented:
  - Real TUN read + write loop
  - IPv4/TCP parse and per-flow session tracking
  - Constrained user-space TCP forwarder over Nox transport:
    - accepts outbound client SYN packets from TUN
    - opens Nox stream (`open`) for destination `ip:port`
    - forwards uplink payload with Nox `data` frames
    - consumes Nox downlink `data` frames and injects TCP payload back into TUN
    - handles stream close via Nox `close` frames
    - synthesizes TCP responses back into TUN (SYN-ACK/ACK/PSH/FIN)
    - basic TCP half-close behavior to avoid immediate teardown on first FIN
    - tracks uplink/downlink bytes and connect failures
  - VPN service now uses saved Nox credentials from UI fields (server URL, shared secret, client ID)

## Important current limits (honest status)
- This is not full VPN usability yet.
- Forwarding is limited to a constrained IPv4/TCP subset.
- UDP is not forwarded.
- TCP handling is minimal and does not implement full RFC-grade behavior (retransmission/window management/selective ACK/etc.).
- TCP close sequencing is improved but still simplified (no full FIN_WAIT/TIME_WAIT state machine).
- Nox data-plane support is currently the first honest subset only:
  - one long-lived WSS transport connection per VPN session
  - per-flow Nox `open/open_resp/data/close` stream usage
  - no reconnect/failover yet inside VPN runtime
  - no H2/H3 transport path in Android client yet

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
- `app/src/main/java/com/noxcore/noxdroid/core/connection/NoxClientConfigStore.kt`: persistent server/secret/client ID store used by handshake + VPN
- `app/src/main/java/com/noxcore/noxdroid/core/connection/NoxTransportClient.kt`: long-lived WSS Nox transport client (`hello`, `open/open_resp`, `data`, `close`, `ping/pong`)
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/NoxVpnService.kt`: foreground `VpnService` + TUN packet loop wiring
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/NoxVpnState.kt`: VPN runtime state model
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/PacketParser.kt`: minimal IPv4/TCP parser foundation
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TcpSessionTracker.kt`: TCP flow/session mapping groundwork
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TcpTunForwarder.kt`: constrained TCP forwarding between TUN and Nox transport streams
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TunPacketLoop.kt`: TUN loop + forwarding stats and lifecycle
- `ROADMAP.md`: next iterations
