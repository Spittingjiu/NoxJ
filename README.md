# NoxDroid (NoxCore WSS Handshake Probe)

Native Kotlin Android app that validates the current NoxCore WSS handshake behavior.

## What this app does
- Collects:
  - Server URL (`wss://host/path`)
  - Shared secret
  - Client ID
- Opens a TLS socket to the server.
- Performs the same HTTP upgrade flow NoxCore uses for WSS (`GET`, `Upgrade: websocket`, `Sec-WebSocket-Key`, `Sec-WebSocket-Protocol: http/1.1`, etc.).
- After `101 Switching Protocols`, speaks NoxCore control framing directly:
  - 4-byte big-endian length prefix
  - UTF-8 JSON envelope: `{ "type": "...", "payload": { ... } }`
- Sends real NoxCore `hello` payload fields:
  - `client_id`
  - `ts` (Unix seconds)
  - `nonce` (hex)
  - `auth = base64(HMAC-SHA256(secret, "hello|client_id|ts|nonce"))`
- Requires `hello_ack` and validates:
  - `server_ts`
  - `server_nonce`
  - `auth = base64(HMAC-SHA256(secret, "ack|client_id|ts|nonce|server_ts|server_nonce"))`

## Scope limits
- This is a handshake/connect probe, not a full tunnel client.
- `open`/`open_resp`, `data`, `close`, keepalive loops, SOCKS/VPN path, and reconnect manager are not implemented yet.
- No claim of VPN/proxy functionality in current app state.

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
- `app/src/main/java/com/noxcore/noxdroid/ui/MainActivity.kt`: UI + result display
- `app/src/main/java/com/noxcore/noxdroid/core/connection/SocketConnectionService.kt`: WSS upgrade + Nox `hello`/`hello_ack` framing and auth validation
- `ROADMAP.md`: planned next steps
