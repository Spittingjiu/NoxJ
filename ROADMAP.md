# ROADMAP: NoxDroid -> Full Android VPN Client

## Iteration status (as of 2026-03-08)
- Handshake tester: implemented and kept functional.
- VPN framework: implemented (`VpnService`, permission flow, foreground service, start/stop UI).
- Data plane step 1: implemented (TUN packet capture loop, IPv4/TCP classification, TCP session mapping groundwork).
- Data plane step 2: implemented as constrained real forwarding subset:
  - TUN-side TCP session acceptance (SYN handling)
  - synthetic TCP replies back to TUN
  - bidirectional payload forwarding for minimal established flows
- Data plane step 3 (first honest Nox transport subset): implemented:
  - one protected long-lived WSS Nox control/data socket for VPN session
  - per-flow `open/open_resp/data/close` mapped from constrained TCP forwarder
  - downlink Nox `data` frames injected back as TCP payload to TUN
  - current implementation is intentionally constrained (no reconnect/failover yet)

## Next step (priority)
- Harden the new Nox transport forwarding path:
  - add transport keepalive/reconnect and session cleanup on transport resets
  - improve open/data/close error propagation and metrics
  - evaluate fallback/debug mode policy for local direct sockets (currently removed from main path)

## Follow-on steps
- TCP correctness and resilience:
  - retransmission handling improvements
  - better sequence/window/ack behavior
  - robust FIN/RST/timeout lifecycle coverage
- Protocol coverage:
  - UDP forwarding path
  - DNS handling strategy
- Transport lifecycle:
  - long-lived connection manager
  - heartbeat + reconnect/backoff
  - network-change handling
- VPN policy and reliability:
  - split/full tunnel controls
  - MTU tuning
  - foreground notification UX polish
- HyperOS hardening:
  - battery optimization guidance
  - behavior checks under aggressive process management
- Security/release readiness:
  - keystore-backed secret handling
  - trust/pinning hardening
  - integration/instrumented tests
