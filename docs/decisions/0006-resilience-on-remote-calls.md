# 0006. Timeouts, retries with backoff and a circuit breaker on every remote call

- Status: Accepted
- Date: 2026-10-02
- Requirement: PL3 p.12 and p.14 (retry, circuit breaker, health checks, automatic recovery); PL3 p.20, fault
  isolation

## Context

Every instance calls its peers (forwarding, replication, sync) and the other two services. In a distributed
system the default failure is not an error but a call that hangs. Without limits, one slow or dead instance makes
every caller slow, threads pile up, and the failure cascades.

## Decision

Every outgoing call goes through `resilience.ResilientCaller`:

| Mechanism | Setting | Behaviour |
|---|---|---|
| Timeout | peers 1.5 s, other services 2 s | no call waits longer |
| Retry with exponential backoff and jitter | 3 attempts, ~100 ms then ~200 ms | **reads only**; a `PATCH` or `POST` is never retried, because the first attempt may have succeeded |
| Circuit breaker, per remote instance | opens after 3 consecutive failures for 10 s, doubling up to 60 s | fail fast; a struggling instance is not flooded; one trial request closes it again |
| Active health checks | every 5 s, 1 s timeout | a recovered instance is used again as soon as it answers |
| Client-side load balancing | round-robin over the other services' instances, rotating first peer | failover to the next instance on timeout or `5xx` |

The circuit state and failure counts of every remote instance are exposed in `GET /api/cluster/health`; all
settings live under `sidis.resilience.*`.

## Alternatives considered

- **A resilience library (Resilience4j).** Provides the same patterns. The service needs a small subset, and an
  in-house implementation of five classes keeps the semantics explicit (which calls are retried, how the circuit
  state is reported) and directly unit-tested.
- **Retry every call.** Unsafe for non-idempotent commands: a timed-out booking may have been made.
- **No circuit breaker, timeouts only.** Every request would still pay the full timeout while an instance is down.

## Consequences

- A dead instance costs at most three failed calls before it is skipped; afterwards requests are not slowed down.
- `Retry-After: 10` on `503` responses matches the circuit's open duration ([0004](0004-503-for-unreachable-data.md)).
- Replication runs on its own thread, so a dead peer never delays a client request.

## Evidence

`resilience/*Test` (backoff, circuit transitions), `TwoInstancesIntegrationTest` (circuit open after an instance
stops), Postman folders **03 Resilience** and **06 Monitoring**, `remoteInstances[]` in `GET /api/cluster/metrics`.
