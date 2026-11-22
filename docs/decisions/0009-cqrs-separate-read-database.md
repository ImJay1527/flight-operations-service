# 0009. CQRS with a separate read database (MongoDB) holding the whole cluster

- Status: Accepted
- Date: 2026-11-20 (pull request #25)
- Requirement: P2 p.2, "adopt the CQRS pattern for the core domain resources" and "evidence of independent read and
  write models"

## Context

In Assignment 1 every query read the write database: a list or report asked every instance (scatter-gather), so
reads got no faster with more instances and failed partially when an instance was down. Commands and queries
competed for the same tables, and the data shape (normalised rows, encrypted fields) suited the booking
transaction, not the reports.

## Decision

- **Commands** (`ScheduleFlightCommand`, `CancelFlightCommand`, `CompleteFlightCommand`) are handled by
  `FlightCommandService` on the **write model**: PostgreSQL, sharded and replicated exactly as in Assignment 1.
- **Queries** (every `GET` of flights and reports) are answered by `FlightQueries` from a **read model**: a
  **MongoDB database per instance** holding **every flight of the cluster** and the reports precomputed
  (`flights`, `aircraft_stats`, `route_stats`).
- The read model is updated three ways: after each local commit (`ReadModelProjection`, so a client reads its own
  write at once), from the other instances' events through the broker within milliseconds
  ([0011](0011-publish-subscribe-one-queue-per-instance.md)), and rebuilt from every instance's write side at
  startup and every 30 s (`ReadModelSync`).
- Updates are idempotent and order-independent: a document is replaced only by a newer revision, and reports are
  recomputed, never incremented.

## Alternatives considered

- **Separate models on the same database.** Splits the code but not the failure or the load: reads and writes
  would still compete for one database and fail together.
- **A second PostgreSQL as read database.** Each read document is the answer itself (a report embeds its months);
  in SQL that is a join on every read. The read model can be rebuilt at any time, so a fixed schema adds nothing.
  A different engine also makes it impossible to share tables between the two sides (polyglot persistence).
- **A read model sharded like the write side.** Every list would again ask every instance; holding the whole
  cluster makes every query local.
- **Event sourcing.** Would allow replaying history, but would replace the write side and its replication. The
  events are used to update the read model, not as the source of truth.

## Consequences

- Queries never ask another instance, and keep working when the write database is down.
- Each instance stores every flight in its read database: fine at this scale; a very large system would shard it.
- Lists on instances not involved in a change are eventually consistent (milliseconds; at worst the sync interval).
- The response headers `X-Read-Side`, `X-Read-Model` and `X-Read-Model-Updated` make the side that answered visible.

## Evidence

`CqrsIntegrationTest` (two instances with their own write and read databases), `MongoReadModelTest`,
`FlightQueriesTest`, Postman folder **12 CQRS**, diagram [8-cqrs](../diagrams/8-cqrs.png),
[architecture.md, CQRS](../architecture.md#cqrs-assignment-2).
