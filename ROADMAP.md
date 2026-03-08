# ROADMAP: NoxDroid -> Full Android VPN Client

## Iteration status (as of 2026-03-08)
- Handshake tester: implemented and kept functional.
- VPN framework: implemented (`VpnService`, permission flow, foreground service, start/stop UI).
- Data plane step 1: implemented (TUN packet capture loop, IPv4/TCP classification, TCP session mapping groundwork).
- Forwarding: not implemented yet.

## Next step (priority)
- Build the first constrained forwarding path between TUN and Nox transport:
  - start with IPv4 TCP subset only
  - map captured sessions into Nox transport frames
  - return-path packet injection back to TUN

## Follow-on steps
- Transport lifecycle:
  - long-lived connection manager
  - heartbeat + reconnect/backoff
  - network-change handling
- VPN policy and reliability:
  - split/full tunnel controls
  - DNS strategy + MTU tuning
  - foreground notification UX polish
- HyperOS hardening:
  - battery optimization guidance
  - behavior checks under aggressive process management
- Security/release readiness:
  - keystore-backed secret handling
  - trust/pinning hardening
  - integration/instrumented tests
