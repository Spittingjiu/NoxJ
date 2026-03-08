# ROADMAP: NoxDroid -> Full Android VPN Client

## Iteration status (as of 2026-03-08)
- Handshake tester: implemented and kept functional.
- VPN framework: implemented (`VpnService`, permission flow, foreground service, start/stop UI).
- Data plane step 1: implemented (TUN packet capture loop, IPv4/TCP classification, TCP session mapping groundwork).
- Data plane step 2: implemented as constrained real forwarding subset:
  - TUN-side TCP session acceptance (SYN handling)
  - protected upstream socket connect per session
  - synthetic TCP replies back to TUN
  - bidirectional payload forwarding for minimal established flows
- Nox transport data-plane integration: not implemented yet.

## Next step (priority)
- Replace direct protected-socket forwarding path with Nox transport framing path:
  - map TUN-side session bytes into Nox transport messages
  - process return-path messages and inject packets to TUN
  - keep current constrained TCP proxy as fallback/debug mode until parity

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
