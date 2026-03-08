# ROADMAP: NoxDroid -> Full Android VPN Client

## Iteration status (as of 2026-03-08)
- Handshake tester: implemented and kept functional.
- Handshake tester reliability: improved by fixing HTTP upgrade request CRLF formatting in probe path.
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
  - TCP stream close lifecycle improved to keep sessions alive through basic half-close instead of immediate FIN teardown
  - refined close timing to avoid sending upstream close frame immediately on client FIN (reduces premature stream termination)
  - added diagnostics/log capture for VPN data plane + transport lifecycle (persisted file + in-app tail)
  - surfaced transport continuity diagnostics in-app (transport up/down + reconnect attempt/success counters)
  - improved failure handling for practical outbound attempts:
    - inject TCP RST on failed opens/unknown sessions to avoid long client hangs
    - segment downlink payload before TUN injection to reduce oversized-packet issues
  - transport continuity hardening:
    - added transport keepalive pings
    - added runtime reconnect attempts inside VPN loop after transport drop
    - disconnect transitions now actively reset stale forwarded TCP sessions to avoid long post-drop hangs
    - fail-fast client-side TCP RST synthesis on transport write/abrupt-close failures
    - reduced per-stream open wait timeout to limit TUN loop stalls on slow/failed opens
  - current implementation is intentionally constrained (basic reconnect for new flows exists; no seamless failover for in-flight flows)
- Stage 3 kickoff (YouTube-first scope):
  - added targeted UDP/443 handling in TUN loop to emit ICMP port-unreachable back to apps
  - purpose is to encourage faster QUIC->HTTPS/TCP fallback for YouTube traffic through current TCP data path
  - this is intentionally a single-target improvement, not full internet protocol support
- Routing safety hardening: implemented for this iteration:
  - removed global `0.0.0.0/0` capture for now
  - enabled safe split-route startup profile to preserve baseline internet connectivity
  - added controlled public-routing mode (private ranges + explicit public IPv4 CIDR allowlist)
  - app traffic is disallowed from VPN interface to protect Nox control/handshake path from self-capture
  - routing mode + CIDR allowlist are persisted and validated before VPN start

## Next step (priority)
- Continue Stage 3 YouTube-first validation:
  - verify YouTube HTTPS reachability with diagnostics after UDP/443 fallback signaling
  - inspect early-close/open-failure metrics for YouTube target flows and tighten TCP lifecycle only where needed
  - keep scope narrow to YouTube traffic before attempting broader internet coverage

## Follow-on steps
- TCP correctness and resilience:
  - retransmission handling improvements
  - better sequence/window/ack behavior
  - robust FIN/RST/timeout lifecycle coverage beyond current basic half-close handling
- Protocol coverage:
  - UDP forwarding path
  - DNS handling strategy
- Transport lifecycle:
  - long-lived connection manager
  - heartbeat + reconnect/backoff
  - network-change handling
- VPN policy and reliability:
  - split/full tunnel controls (full tunnel only after forwarding path is complete and validated)
  - MTU tuning
  - foreground notification UX polish
- HyperOS hardening:
  - battery optimization guidance
  - behavior checks under aggressive process management
- Security/release readiness:
  - keystore-backed secret handling
  - trust/pinning hardening
  - integration/instrumented tests
