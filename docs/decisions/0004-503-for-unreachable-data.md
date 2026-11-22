# 0004. Answer `503`, not `404`, when the data may be on unreachable instances

- Status: Accepted (refines the Assignment 1 behaviour)
- Date: 2026-11-20 (pull request #24)
- Requirement: feedback at the Assignment 1 defence; RFC 9110 §15.5.5 and §15.6.4. PL3 p.11 used `404` for both
  cases.

## Context

In Assignment 1 a lookup that found nothing on the reachable instances answered `404 Not Found`. When the
instances that might hold the flight are down, that answer is wrong: the flight may exist. A client that trusts a
`404` may, for example, book again.

## Decision

`404` is returned only when every place the resource may be has answered "not here". Otherwise the answer is
`503 Service Unavailable` with `Retry-After: 10` and the number of unreachable instances in the message.

- **One flight:** every flight is on `REPLICATION_FACTOR` instances. If fewer instances than that are
  unreachable, at least one copy of every flight was asked, so not found is `404`. Otherwise `503`.
- **One aircraft's report:** `503` when its holders may be among the unreachable instances.
- **Another service:** `404` only when that service itself answered `404`; `503` when none of its instances answers.
- **Lists** stay `200` with what the reachable instances hold, flagged with `X-Unreachable-Peers` and, when data may
  really be missing, `X-Partial-Result: true` (P1 p.12, partial responses).

`Retry-After: 10` matches the time an open circuit breaker waits before trying the instance again
([0006](0006-resilience-on-remote-calls.md)).

## Alternatives considered

- **Always `404` (PL3 p.11).** Indistinguishable from "does not exist".
- **`504 Gateway Timeout`.** Describes one timed-out upstream call, not "the data is temporarily unreachable",
  and does not cover connection-refused or open-circuit cases.
- **Fail the whole list when one instance is down.** Loses availability for data that is reachable.

## Consequences

Clients can distinguish "absent" from "unknown right now" and retry the latter. With replication, a `503` for a
single flight requires at least two instances down.

## Evidence

`TwoInstancesIntegrationTest` (instance stopped → `503` with `Retry-After`), Postman folders **03** (with
`REPLICATION_FACTOR=1`) and **11c**. Details: [architecture.md, Status codes](../architecture.md#status-codes-404-or-503).
