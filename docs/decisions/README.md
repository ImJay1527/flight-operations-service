# Architecture Decision Records

Each record captures one significant design decision of the Flight Operations service: the problem it addresses,
the option chosen, the alternatives that were rejected and the consequences accepted with it. The format follows
Michael Nygard's *Documenting Architecture Decisions* (2011): one short, immutable record per decision; a decision
that changes is superseded by a new record rather than edited.

| # | Decision | Assignment | Status |
|---|---|---|---|
| [0001](0001-shard-flights-by-aircraft.md) | Shard flights by aircraft registration with rendezvous hashing | A1 | Accepted |
| [0002](0002-two-copies-of-every-flight.md) | Keep two copies of every flight, replicated through a transactional outbox | A1 | Accepted |
| [0003](0003-availability-over-consistency.md) | Favour availability during partitions (AP) and report booking conflicts | A1 | Accepted |
| [0004](0004-503-for-unreachable-data.md) | Answer `503`, not `404`, when the data may be on unreachable instances | A1 → A2 | Accepted |
| [0005](0005-database-per-instance.md) | One database per instance, no shared storage | A1 | Accepted |
| [0006](0006-resilience-on-remote-calls.md) | Timeouts, retries with backoff and a circuit breaker on every remote call | A1 | Accepted |
| [0007](0007-encryption-in-transit-and-at-rest.md) | TLS 1.3 only in transit, AES-256-SIV at rest, short-lived service tokens | A1 | Accepted |
| [0008](0008-kubernetes-statefulset-and-discovery.md) | Kubernetes StatefulSet with DNS discovery and CPU-based auto-scaling | A1 | Accepted |
| [0009](0009-cqrs-separate-read-database.md) | CQRS with a separate read database (MongoDB) holding the whole cluster | A2 | Accepted |
| [0010](0010-read-model-fallback.md) | Fall back to the write side when the read model cannot answer | A2 | Accepted |
| [0011](0011-publish-subscribe-one-queue-per-instance.md) | Publish/subscribe on a topic exchange, one queue per instance | A2 | Accepted |
| [0012](0012-event-outbox-and-publisher-confirms.md) | Publish events through a transactional outbox with publisher confirms | A2 | Accepted |
| [0013](0013-at-least-once-idempotent-consumers.md) | At-least-once delivery with idempotent consumers and dead-letter queues | A2 | Accepted |
| [0014](0014-local-copies-of-other-services-data.md) | Keep local copies of the other services' data, fed by their events | A2 | Accepted |
| [0015](0015-query-instances.md) | Optional query instances: CQRS as separate processes | A2 | Accepted |

## Template

```markdown
# NNNN. Title (the decision, stated as an action)

- Status: Proposed | Accepted | Superseded by NNNN
- Date: YYYY-MM-DD
- Requirement: the statement or slide that motivates it

## Context
The forces at play: the requirement, the constraints, what makes the choice non-trivial.

## Decision
What was decided, in one or two paragraphs.

## Alternatives considered
Each rejected option and why it was rejected.

## Consequences
What becomes easier, what becomes harder, and the limits that were accepted.

## Evidence
The tests, Postman folders, metrics or measurements that show the decision in effect.
```
