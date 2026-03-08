# ROADMAP: NoxDroid -> Full Mobile Client

## Phase 1 (current baseline)
- Basic native UI for server URL, secret, client ID
- Real secure WebSocket (`wss`) hello/hello_ack test
- HMAC-based hello auth payload generation on-device

## Phase 2 (protocol correctness)
- Expand handshake compatibility to exact deployed NoxCore schema variants
- Add full request/response framing (including binary), error codes, and retry/backoff
- Support token/certificate-based auth model replacement where needed

## Phase 3 (transport lifecycle)
- Introduce long-lived connection manager with heartbeat
- Add reconnect policy, network change handling, and offline queueing
- Persist non-sensitive connection profile settings

## Phase 4 (mobile tunnel integration)
- Implement `VpnService`-based virtual interface
- Build packet routing between TUN and Nox transport
- Add DNS handling, MTU tuning, and split/full tunnel policies

## Phase 5 (Xiaomi HyperOS hardening)
- Foreground service UX for reliable background operation
- Battery optimization guidance and detection
- Startup/boot resilience where policy allows

## Phase 6 (security and release readiness)
- Android Keystore-backed secret/token handling
- Certificate pinning / trust model hardening
- Instrumented tests, integration tests, crash reporting, observability
- Signed release pipeline and staged rollout
