# Glossary

[Documentation guide](README.md)

Terms as they are used in this documentation and in the code: first the domain (what the service manages, as in
the [logical view](c4-model.md#logical-view-uml)), then the architecture.

## Domain

| Term | Meaning |
|---|---|
| **Scheduled flight** (`ScheduledFlight`) | An aircraft assigned to a route for a departure and an arrival time (US212). Identified by its flight number, a UUID. The only business entity of the service |
| **Flight status** (`FlightStatus`) | `SCHEDULED` when booked; `CANCELED` by a cancel command; `COMPLETED` by a complete command, allowed only after departure. Cancelled flights count in no report |
| **Overlap check** | A booking is refused (`409`) if the aircraft already has a scheduled flight that overlaps the new one's departure-to-arrival window widened by 30 minutes on each side (turnaround buffer) |
| **Aircraft booking lock** (`AircraftBookingLock`) | One row per aircraft, locked during a booking so that two bookings of the same aircraft cannot pass the overlap check together |
| **Departures board** | The non-cancelled flights leaving an airport within the next N hours (default 24), for backoffice operators |
| **Aircraft utilization** (US223) | Per aircraft: number of flights and flight hours, in total and per month |
| **Fuel efficiency** (US227) | Per aircraft and per route: distance flown and fuel used, and kilometres per litre. The fuel burn rate of a flight is the model's fuel capacity divided by its maximum range, stored on the flight when it is booked |
| **Mood** (`/api/v2/unicorns`) | An airplane's mood, from its flights at the time of the request: `FLYING` (in the air now), `EXCITED` (leaves within 24 h), `TIRED` (10 h or more flown in the last 7 days), `BORED` (nothing scheduled), `HAPPY` (otherwise) |
| **System user** (`SystemUser`) | A person who signs in, with a BCrypt password hash and a list of roles (ATCC, BACKOFFICE_OPERATOR, MAINTENANCE_TECHNICIAN, MAINTENANCE_SUPERVISOR, ADMIN) |
| **Flight document** (`FlightDocument`) | A flight in the read database (MongoDB collection `flights`), shaped for the queries |
| **Aircraft and route statistics** (`AircraftStatsDocument`, `RouteStatsDocument`) | The utilization and fuel-efficiency reports, stored already computed in the read database (`aircraft_stats`, `route_stats`) |

## Architecture

| Term | Meaning |
|---|---|
| **Instance** | One running process of the Flight Operations API, with its own databases. Named `instance1` .. `instance3` (local, Docker Compose) or `flightops-0` .. `flightops-4` (Kubernetes) |
| **Command instance** | An instance that is a cluster member: holds flights and takes commands. All instances are command instances unless started as query instances |
| **Query instance** | An optional instance with the `query` profile: read database only, not a member, refuses commands with `405` ([ADR 0015](decisions/0015-query-instances.md)) |
| **Cluster** | The set of command instances. Known from a fixed list (`flightops.cluster`, `CLUSTER`) or by DNS on Kubernetes |
| **Owner** | The first instance of an aircraft's ranking: where its bookings are made ([ADR 0001](decisions/0001-shard-flights-by-aircraft.md)) |
| **Backup** | The second instance of the ranking: holds a copy of every flight of the aircraft and takes its bookings when the owner is unreachable |
| **Holder** | The owner or the backup of an aircraft |
| **Rendezvous hashing** | Highest-random-weight hashing: each instance scores `SHA-256(instance name + registration)`; the highest score is the owner. Every instance computes the same ranking without coordination |
| **Replication factor** | Number of holders per aircraft (`REPLICATION_FACTOR`, default 2) |
| **Revision** | A counter on each flight that grows with every change; the copy with the higher revision wins |
| **Replication outbox** | Table `replication_outbox`: "send flight F to instance B", saved in the change's transaction ([ADR 0002](decisions/0002-two-copies-of-every-flight.md)) |
| **Catch-up (anti-entropy)** | Each instance fetching from its peers the flights it should hold: at startup, before it reports ready, and every 60 s |
| **Hand-off** | After instances join or leave, sending flights an instance no longer holds to their new holders, then deleting its own copy |
| **Forwarding** | An instance asking its peers (`/internal/**`) for data it does not hold |
| **Write side / write model** | PostgreSQL per instance; changed only by commands; the source of truth |
| **Read side / read model** | MongoDB per instance with every flight of the cluster and precomputed reports; answers the queries ([ADR 0009](decisions/0009-cqrs-separate-read-database.md)) |
| **Projection** | Applying a change to the read model (`readmodel.ReadModelProjection`) |
| **Read model sync** | Rebuilding the read model from every instance's write side (`readmodel.ReadModelSync`): at startup, every 30 s, after a peer recovers |
| **Read model state** | `READY` (used for queries), `BUILDING`, `STALE: <reason>` or `DISABLED`; anything but `READY` takes the fallback path |
| **Fallback path** | A query answered by the write side, as in Assignment 1 ([ADR 0010](decisions/0010-read-model-fallback.md)) |
| **Event outbox** | Table `event_outbox`: an event saved in the change's transaction, deleted once the broker confirms it ([ADR 0012](decisions/0012-event-outbox-and-publisher-confirms.md)) |
| **Event relay** | `messaging.EventRelay`: the thread that publishes the outbox rows and waits for the publisher confirms |
| **Publisher confirm** | RabbitMQ's acknowledgement to the publisher that a message has been accepted |
| **Routing key** | The event type in `<entity>.<what happened>` form, e.g. `flight.completed`; queues bind to patterns such as `flight.#` |
| **Dead-letter queue (DLQ)** | `flight.events.dlq`: messages that failed 3 times, expired or overflowed ([ADR 0013](decisions/0013-at-least-once-idempotent-consumers.md)) |
| **Local copy** | `aircraft_copy`, `route_copy`, `airport_copy`: the other services' data a booking needs, fed by their events ([ADR 0014](decisions/0014-local-copies-of-other-services-data.md)) |
| **Stub mode** | `stub` profile: built-in aircraft and route data, so the service runs without the other two services |
| **Service token** | A JWT with role `SERVICE`, valid 5 minutes, carried by every call between instances and services |
| **AP** | Available and partition-tolerant (CAP): the system keeps answering during a partition and converges afterwards ([ADR 0003](decisions/0003-availability-over-consistency.md)) |
