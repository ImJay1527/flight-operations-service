# 0012. Publish events through a transactional outbox with publisher confirms

- Status: Accepted
- Date: 2026-11-21 (pull request #26)
- Requirement: P2 p.2, reliable asynchronous communication; RabbitMQ best practices (RMQ p.15)

## Context

A change is stored in PostgreSQL; its event goes to RabbitMQ. The two cannot share a transaction. Publishing inside
the database transaction announces changes that may still roll back; publishing after the commit loses the event
if the process stops in between, or if the broker is down at that moment. Either way, consumers would silently
diverge from the source of truth.

## Decision

1. The write side raises `FlightChanged`; only the instance that **made** the change publishes it, so each change
   is announced once.
2. `messaging.EventOutbox` stores the event in the table `event_outbox` **inside the change's transaction**, just
   before it commits (payload encrypted at rest). Change and event are saved together or not at all.
3. `messaging.EventRelay`, on its own thread, sends the waiting events in order right after the commit and every
   second, as persistent messages, and **waits for the broker's publisher confirm** before deleting each row.
4. `eventId` is derived from the change (flight, revision, status), so an event that is sent twice keeps the same id.

## Alternatives considered

- **Publish inside the transaction.** A rolled-back change would still have been announced.
- **Publish after the commit, without an outbox.** A crash or a broker outage at that moment loses the event.
- **Distributed transaction (XA / 2PC) across database and broker.** RabbitMQ does not support it, and it would
  make every booking depend on the broker being up.
- **Change data capture from the database log (e.g. Debezium).** Removes the outbox table writes, but adds a
  separate platform to operate.

## Consequences

- The broker is **not on the request path**: bookings, cancels and queries keep working while it is down, and
  their events go out when it is back (diagram [11-broker-outage](../diagrams/11-broker-outage.png)).
- Delivery is at least once (a crash after the confirm but before the delete re-sends the event), so consumers
  must be idempotent ([0013](0013-at-least-once-idempotent-consumers.md)).
- One extra row per change, and a relay thread per instance.

## Evidence

`EventOutboxTest` (broker down: one event per booking and cancel, none for a refused booking or a received copy),
`MessagingIntegrationTest` (broker paused: bookings still `201`, events wait until it is back), phases **11f** and
**11g** of `scripts/test-failures.sh`, `messaging.outbox` in `GET /api/cluster/health`.
