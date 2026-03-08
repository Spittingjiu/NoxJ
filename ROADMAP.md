# ROADMAP: NoxDroid -> Full Mobile Client

## Phase 1 (current trial)
- Basic native UI for endpoint, secret, client ID
- TCP connect test and status reporting

## Phase 2 (protocol correctness)
- Define and implement authenticated handshake with Nox server
- Add request/response framing, error codes, and retry/backoff
- Cryptographically use shared secret (or token/cert replacement)

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
