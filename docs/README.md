# Documentation guide

This guide is the entry point to the documentation of the **Flight Operations** service of the AISafe Flight
Management System (SIDIS 2026/27, Assignments 1 and 2). It lists what each document covers, suggests a reading
order for each kind of reader, and points to where each design question is answered.

## Document map

| Document | Content | Primary reader |
|---|---|---|
| [README](../README.md) | Overview, Assignment 2 requirements with their evidence, quick start | everyone |
| [architecture.md](architecture.md) | Mechanisms, guarantees, failure behaviour, performance measurements, alternatives | reviewers, developers |
| [decisions/](decisions/README.md) | 15 architecture decision records: context, decision, alternatives, consequences | reviewers |
| [c4-model.md](c4-model.md) | C4 levels 1-4, logical and development views, scenarios, deployment (4+1) | reviewers, developers |
| [service-contracts.md](service-contracts.md) | REST endpoints and AMQP events shared by the three services | the other teams |
| [running.md](running.md) | Local runs, Docker Compose, Kubernetes, Spring profiles | operators, developers |
| [testing.md](testing.md) | JUnit and integration tests, Postman collection, request tracing | reviewers, developers |
| [operations.md](operations.md) | Dashboard, logs, metrics, databases, message broker | operators |
| [security.md](security.md) | Encryption in transit and at rest, authentication, access control, audit | reviewers |
| [limitations.md](limitations.md) | Trade-offs accepted on purpose, and what would remove each | reviewers |
| [glossary.md](glossary.md) | Terms used throughout the documentation | everyone |

## Reading paths

**Architecture review (about 15 minutes).**
1. [README](../README.md): the system overview diagram and the requirements table.
2. [architecture.md, System architecture](architecture.md#system-architecture-pl3-p20): diagrams 1 to 6.
3. [CQRS](architecture.md#cqrs-assignment-2) and [Messaging](architecture.md#messaging-assignment-2): diagrams 8 to 12.
4. [Decision records](decisions/README.md): the index, then the records of interest.
5. [Known limitations](limitations.md).

**Checking the statement.** The [requirements table](../README.md#assignment-2-requirements-and-evidence) maps every
requirement of P2 p.2 and RMQ p.15 to what was built and to the test, Postman folder or endpoint that shows it.

**Running and observing the system.** [running.md](running.md), then the dashboard and metrics in
[operations.md](operations.md), then the Postman collection in [testing.md](testing.md#postman-pl3-p17).

**Changing the code.** [C4 levels 3 and 4](c4-model.md#level-3--components), the
[development view](c4-model.md#development-view-packages) (generated from the imports), then the tests that cover the
area in [testing.md](testing.md#automated-tests-junit).

## Where each question is answered

| Question | Short answer | Detail |
|---|---|---|
| How are the flights distributed? | Sharded by aircraft registration with rendezvous hashing; each flight on its owner and a backup | [ADR 0001](decisions/0001-shard-flights-by-aircraft.md), [ADR 0002](decisions/0002-two-copies-of-every-flight.md), [Distribution](architecture.md#distribution-and-resilience) |
| What happens when an instance fails? | Its flights are served from their copies; the backup takes its bookings; it catches up before it serves again | [Failure and recovery](architecture.md#4-failure-and-automatic-recovery-pl3-p14), [During a failure (CAP)](architecture.md#during-a-failure-cap) |
| What consistency does a client get? | Eventual between instances, read-your-writes on the instance that made the change, no double-booking outside a partition | [Consistency model](architecture.md#consistency-model), [ADR 0003](decisions/0003-availability-over-consistency.md) |
| Why `503` and not `404`? | `404` only when every possible holder answered; otherwise the data may exist | [ADR 0004](decisions/0004-503-for-unreachable-data.md), [Status codes](architecture.md#status-codes-404-or-503) |
| How are reads and writes separated? | Commands on PostgreSQL (sharded), queries on a MongoDB per instance holding the whole cluster | [CQRS](architecture.md#cqrs-assignment-2), [ADR 0009](decisions/0009-cqrs-separate-read-database.md) |
| How is the read model kept up to date? | Projection after each commit, broker events from the other instances, periodic rebuild | [How the read model is kept up to date](architecture.md#how-the-read-model-is-kept-up-to-date) |
| What if the read database fails? | Queries fall back to the write side; commands are unaffected | [ADR 0010](decisions/0010-read-model-fallback.md) |
| How do the services communicate? | REST for clients and lookups; AMQP events on one topic exchange, one queue per instance | [Messaging](architecture.md#messaging-assignment-2), [ADR 0011](decisions/0011-publish-subscribe-one-queue-per-instance.md), [contract](service-contracts.md#events-amqp-assignment-2) |
| Can an event be lost? | No: transactional outbox and publisher confirms; the read models are also rebuilt periodically | [ADR 0012](decisions/0012-event-outbox-and-publisher-confirms.md) |
| What about duplicates and failed messages? | Idempotent consumers (newest revision wins); 3 attempts, then a dead-letter queue | [ADR 0013](decisions/0013-at-least-once-idempotent-consumers.md) |
| What if the broker is down? | Commands and queries continue; events wait in the outbox; the sync keeps read models current | [Messaging failures](architecture.md#failures-1), diagram 11 |
| What if another service is down? | Bookings use the local copies of aircraft, routes and airports | [ADR 0014](decisions/0014-local-copies-of-other-services-data.md) |
| How does it scale? | Kubernetes auto-scaler (3-5 instances) with DNS discovery; optional query instances scale reads alone | [ADR 0008](decisions/0008-kubernetes-statefulset-and-discovery.md), [ADR 0015](decisions/0015-query-instances.md), [Horizontal scaling](architecture.md#horizontal-scaling) |
| How fast is it? | Local and forwarded latency, availability during a failure, 2 vs 3 instances, measured with k6 | [Performance](architecture.md#performance-pl3-p21) |
| How is it secured? | TLS 1.3 only (also AMQPS), AES-256-SIV at rest, user and service JWTs | [security.md](security.md), [ADR 0007](decisions/0007-encryption-in-transit-and-at-rest.md) |
| How is it tested? | JUnit with real instances, databases and broker (Testcontainers); 151 Postman requests | [testing.md](testing.md) |

## Diagrams

Sources are Mermaid files kept next to the images (`docs/diagrams/*.mmd`, `docs/c4/*.mmd`). Images are regenerated
with `npx @mermaid-js/mermaid-cli -i <file>.mmd -o <file>.png -b white -s 2`; the development view is generated from
the code by `scripts/generate-development-view.py` and checked in CI.

| Diagram | Shows | Type |
|---|---|---|
| [1 System overview](diagrams/1-system-overview.png) | the three services, their instances, databases, load balancers and broker | deployment |
| [3 Forwarding](diagrams/3-forwarding.png) | a lookup answered by another instance | sequence |
| [4 Failure and recovery](diagrams/4-failure-and-recovery.png) | an instance fails: copies, circuit breaker, catch-up | sequence |
| [5 Sharded booking](diagrams/5-booking-sharded.png) | a booking forwarded to the aircraft's owner and copied to its backup | sequence |
| [6 Replication](diagrams/6-replication.png) | outbox, delivery, revisions, anti-entropy | flow |
| [7 Auto-scaling](diagrams/7-autoscaling.png) | a pod added by the auto-scaler, rebalancing | sequence |
| [8 CQRS](diagrams/8-cqrs.png) | write side and read side of one instance, and how the read side is fed | flow |
| [9 Messaging topology](diagrams/9-messaging-topology.png) | exchange, routing keys, queues of every service, dead letters | flow |
| [10 Event flow](diagrams/10-event-flow.png) | a completed flight from the commit to every consumer | sequence |
| [11 Broker outage](diagrams/11-broker-outage.png) | the broker down and back: outbox, sync, idempotent redelivery | sequence |
| [12 Query instances](diagrams/12-query-instances.png) | CQRS as separate processes behind the load balancer | flow |
| [C4 1 to 4, logical, development, Kubernetes](c4-model.md) | the C4+1 model | structure |

## Conventions

- **References to the course material** are given as document and page: P1 / P2 (the statements of Assignments 1
  and 2), PL2 / PL3 (practical-session slides), RMQ (RabbitMQ slides), Week N (lecture slides).
- **Code references** use the package below `pt.isep.sidis.flightops` (for example `readmodel.ReadModelSync`).
- **Evidence** names a test class, a Postman folder (two digits, e.g. folder **12**), an endpoint or a response
  header, so every claim can be checked.
