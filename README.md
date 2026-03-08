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
  - Safe split-route startup mode (not global capture) to preserve baseline connectivity while forwarding remains constrained
  - Controlled public-routing mode with explicit IPv4 CIDR allowlist (incremental public internet routing without full `0.0.0.0/0`)
  - Explicit app-level VPN bypass to prevent Nox control/handshake sockets from self-capture loops
  - Start/stop control from UI
  - Persistent routing preferences (safe mode vs controlled public + CIDR list)
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
    - improved TCP half-close handling to avoid prematurely closing upstream stream on client FIN
    - downlink TCP payload segmentation (smaller injected packets instead of oversized single writes)
    - TCP RST synthesis for failed stream opens / unknown sessions so client sockets fail fast instead of hanging
    - fail-fast TCP RST teardown when transport write/close errors occur (avoids long hangs on broken streams)
    - active session reset on transport-down transition so stale sessions do not linger after disconnects
    - runtime transport reconnect attempts inside VPN loop after disconnects
    - reconnect attempts now run before packet forwarding work and stream-open timeout is shorter to reduce "stuck open" stalls
    - transport keepalive pings to reduce idle control/data socket drops on mobile networks
    - YouTube-first Stage 3 step: UDP/443 packets now receive ICMP port-unreachable to encourage QUIC fallback to HTTPS/TCP over current forwarder
    - tracks uplink/downlink bytes and connect failures
  - VPN service now uses saved Nox credentials from UI fields (server URL, shared secret, client ID)
- Internal diagnostics are now implemented for VPN data plane and Nox transport:
  - persisted log file at app-private `files/nox_vpn_diagnostics.log` (rotated)
  - in-app diagnostics panel with recent events
  - actionable events for transport connect/open/close failures, forwarder session lifecycle, packet drop/open-failure growth
  - in-app VPN status now shows transport up/down and reconnect attempt/success counters

## Important current limits (honest status)
- This is not full VPN usability yet.
- Current Stage 3 work is intentionally YouTube-first, not global internet success.
- Routing is still intentionally incremental:
  - safe mode: private ranges only
  - controlled mode: private ranges + user-provided public IPv4 CIDR allowlist
  - no blind global `0.0.0.0/0` routing yet
- Forwarding is limited to a constrained IPv4/TCP subset.
- UDP is not forwarded.
- DNS over UDP is therefore not available through the VPN data plane yet.
- TCP handling is minimal and does not implement full RFC-grade behavior (retransmission/window management/selective ACK/etc.).
- TCP close sequencing is improved but still simplified (no full FIN_WAIT/TIME_WAIT state machine).
- Runtime reconnect currently recovers only new flows after transport loss; existing in-flight flows can still drop.
- Nox data-plane support is currently the first honest subset only:
  - one long-lived WSS transport connection per VPN session
  - per-flow Nox `open/open_resp/data/close` stream usage
  - basic reconnect exists for new flows after disconnects; no seamless failover for in-flight flows
- no H2/H3 transport path in Android client yet
- no claim yet of complete WeChat/full real-app compatibility in this iteration

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
- `app/src/main/java/com/noxcore/noxdroid/core/diagnostics/DiagnosticsLog.kt`: persisted + in-app diagnostics log pipeline for VPN/transport runtime
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/NoxVpnService.kt`: foreground `VpnService` + TUN packet loop wiring
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/NoxVpnState.kt`: VPN runtime state model
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/VpnRoutingConfig.kt`: routing mode model, CIDR parser, and routing preference store
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/PacketParser.kt`: minimal IPv4/TCP parser foundation
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TcpSessionTracker.kt`: TCP flow/session mapping groundwork
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TcpTunForwarder.kt`: constrained TCP forwarding between TUN and Nox transport streams
- `app/src/main/java/com/noxcore/noxdroid/core/vpn/dataplane/TunPacketLoop.kt`: TUN loop + forwarding stats and lifecycle
- `ROADMAP.md`: next iterations
