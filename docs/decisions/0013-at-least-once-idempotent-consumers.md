# 0013. At-least-once delivery with idempotent consumers and dead-letter queues

- Status: Accepted
- Date: 2026-11-21 (pull request #26)
- Requirement: RabbitMQ best practices (RMQ p.15): acknowledge after processing, use dead-letter exchanges, set
  TTL and queue limits, monitor queue depth

## Context

The outbox guarantees that every event is sent at least once ([0012](0012-event-outbox-and-publisher-confirms.md)).
Duplicates and reordering are therefore normal: a re-sent event, a redelivery after a consumer crash, an old
event queued while an instance was down. A message that can never be processed must not block the queue or be
retried forever.

## Decision

- **Acknowledge after processing** (Spring AMQP AUTO mode: the ack is sent when the listener returns).
- **Idempotent, order-independent processing:** the read model keeps only the newest revision of each flight; each
  local copy of another service's data keeps the time its data was true (`asOf`) and ignores older events. Applying
  an event twice, or late, changes nothing, so no table of processed ids is needed.
- **Bounded retries, then dead-letter:** a message that fails is retried 3 times (0.5 s, then 1 s apart), then
  rejected without requeue and routed through `aisafe.dlx` to `flight.events.dlq` (kept 7 days).
- **Queue limits:** at most 100 000 messages and 1 day per message (overflow and expired messages are
  dead-lettered); a queue without a consumer for 7 days is removed (query instances: 1 minute).
- A read database that is down does **not** reject the message: the read model becomes `STALE`, queries use the
  write side ([0010](0010-read-model-fallback.md)), and the sync repairs it.

## Alternatives considered

- **Exactly-once delivery.** Would require the broker and the database in one distributed transaction, which
  RabbitMQ does not offer. Idempotent processing gives the same final state.
- **Acknowledge on receipt.** A crash during processing would lose the event.
- **Requeue failed messages indefinitely.** A single malformed message would be redelivered forever and starve the
  queue.

## Consequences

- Duplicates and out-of-order delivery are harmless by construction.
- A lost or dead-lettered event is still repaired by the 30 s sync from the write side.
- Dead letters are visible (`messaging.deadLetters` in `GET /api/cluster/health`) and kept for inspection.

## Evidence

`MessagingIntegrationTest` (a message that cannot be read reaches the dead-letter queue after the retries),
`AircraftEventsTest` and `RouteEventsTest` (duplicate and older events change nothing), `MongoReadModelTest`
(newer revision wins, the same change counted once), Postman folders **13**, **15**, **16**.
