# Service contracts (AISafe – SIDIS 2026/27)

This is the agreement between the three services. **Change it only after all three owners agree**, then copy the
new version to every repo.

## Services

| Service | Repo | Port (container) | Owns |
|---|---|---|---|
| Aircraft & Maintenance | `aircraft-maintenance-service` | 8081 | aircraft models, aircraft, maintenance templates & records |
| Airports & Routes | `airports-routes-service` | 8082 | airports, runways, certifications, flight routes & route history |
| Flight Operations | `flight-operations-service` | 8083 | scheduled flights, aircraft assignment, utilization, fuel efficiency, **login (users)** |

Each service has its **own database**. No service reads another service's tables. Cross-references are by ID only
(`routeId`, `aircraftRegistration`, `iataCode`).

## Security (all services)

* All requests carry `Authorization: Bearer <JWT>` (HS256). The secret comes from env `JWT_SECRET` and **must be
  identical in all services**.
* **Users** log in at `POST /api/auth/login` on flight-operations-service. The token's `role` claim is a
  comma-separated list: `ATCC`, `BACKOFFICE_OPERATOR`, `MAINTENANCE_TECHNICIAN`, `MAINTENANCE_SUPERVISOR`, `ADMIN`.
* **Services** calling each other generate their own token with `role = SERVICE` and `sub = <service name>`
  (`JwtUtils.generateServiceToken`).
* `/internal/**` requires role `SERVICE` → a user token gets 403. Public `/api/**` endpoints use the user roles,
  so a service token gets 403 there.
* Every request is written to the `AUDIT` logger (user, roles, method, URI, status, remote address).

## Internal endpoints other services must provide

All return `200` + JSON, `404` if no replica has the resource. A replica that does not hold the resource locally must
ask its peers **before** answering 404 (so callers can treat 404 as final).

### Aircraft & Maintenance

`GET /internal/aircraft/{registration}`

```json
{
  "registrationNumber": "CS-TPA",
  "status": "AVAILABLE",
  "modelName": "A320neo",
  "maxRange": 6300.0,
  "fuelCapacity": 24000.0,
  "activeCapacity": 160
}
```
`status` ∈ `AVAILABLE | IN_FLIGHT | UNDER_MAINTENANCE | INACTIVE`

### Airports & Routes

`GET /internal/airports/{iata}`

```json
{ "iataCode": "LIS", "status": "OPERATIONAL", "certifiedModels": ["A320neo", "737 MAX"] }
```
`status` ∈ `OPERATIONAL | CLOSED | UNDER_MAINTENANCE`

`GET /internal/routes/{routeId}`

```json
{
  "routeId": "route-opo-lis",
  "status": "ACTIVE",
  "distanceKm": 277.0,
  "minRangeRequired": 350.0,
  "minCapacityRequired": 100,
  "origin":      { "iataCode": "OPO", "status": "OPERATIONAL", "certifiedModels": ["A320neo"] },
  "destination": { "iataCode": "LIS", "status": "OPERATIONAL", "certifiedModels": ["A320neo"] }
}
```
`status` ∈ `ACTIVE | DEACTIVATED`

### Flight Operations (replica-to-replica only)

| Endpoint | Does (local data only, never forwards) |
|---|---|
| `GET /internal/flights/{flightNumber}` | one flight or 404 |
| `GET /internal/flights?aircraft=REG` | flights of an aircraft |
| `GET /internal/flights/active[?aircraft=REG]` | non-cancelled flights |
| `GET /internal/flights/departures/{iata}?hours=N` | upcoming departures |
| `GET /internal/flights/all` | every local flight, cancelled ones too (the peers' CQRS read models are rebuilt from these) |
| `PATCH /internal/flights/{flightNumber}/cancel` | cancelled flight, 404 if not here, 409 if not cancellable |
| `PATCH /internal/flights/{flightNumber}/complete` | completed flight, 404 if not here, 409 if cancelled, already completed or not departed yet |
| `POST /internal/flights` `{routeId, aircraftRegistration, departureTime, arrivalTime}` | books the flight on THIS instance (the aircraft's owner - sharding by registration); 201 + flight, or the same 4xx a normal booking gets |
| `POST /internal/replicas` `{flight, with revision}` | stores or updates this instance's copy of a flight (replication); kept only if newer (higher revision); 204 |
| `GET /internal/replicas?for=NAME` | the flights here that instance NAME should also hold (its catch-up after a restart, and the periodic repair) |
| `GET /internal/cluster/status` | this instance's status (health, load, flights as owner / backup, peers); gathered by `GET /api/cluster/overview` for the dashboard |

Every flight is kept on the first `flightops.replication.factor` (default 2) instances of its aircraft's
rendezvous-hash ranking; the flight JSON exchanged on these endpoints carries a `revision` number.

## Events (AMQP, Assignment 2)

Between services, changes are announced as events through **one RabbitMQ broker** (`rabbitmq:3-management`, host
`rabbitmq`, port 5672, dev user `guest` / `guest`, env `RABBITMQ_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`). The
external API stays REST.

| What | Name |
|---|---|
| Exchange (topic, durable) for every service's events | `aisafe.events` |
| Dead-letter exchange (topic, durable) | `aisafe.dlx` |
| Queue of one instance (durable, publish/subscribe: **one per instance**, never shared) | `<service>.events.<instance>`, e.g. `flight.events.instance1`, `aircraft.events.instance2` |
| Dead-letter queue of a service, bound to `aisafe.dlx` with **its own name** as key (not `#`) | `<service>.events.dlq`, e.g. `flight.events.dlq` |

Routing keys: `<entity>.<what happened>` — `flight.scheduled`, `flight.cancelled`, `flight.completed`,
`aircraft.created`, `aircraft.status.changed`, `aircraft.maintenance.scheduled`, `aircraft.maintenance.completed`.
Each service publishes only its own entity's keys.

**Every message**: JSON body (`content_type: application/json`, UTF-8), persistent, `message_id` = `eventId`, no
`__TypeId__` header (consumers don't need the publisher's classes). Every body has:

| Field | |
|---|---|
| `eventId` | UUID. The same change always has the same id: a consumer drops one it has already processed |
| `eventType` | e.g. `FlightCompletedEvent` |
| `occurredAt` | ISO-8601 instant (UTC), e.g. `2026-11-20T14:45:00Z` |

**Rules for publishers and consumers**
* Publish through a **transactional outbox** (the event is stored in the same transaction as the change, then
  sent), so no event is lost or sent for a change that was rolled back. Delivery is **at least once**.
* A consumer **acknowledges after processing**, is **idempotent** (`eventId`, or a revision / version), and
  **checks the routing key or `eventType`** before acting (a queue bound to `flight.#` also gets `flight.scheduled`).
* A message that keeps failing goes to the service's dead-letter queue after a few attempts (no endless retries).
* Queues have limits (`x-max-length`, `x-message-ttl`); unknown fields are ignored (new fields don't break anyone).

### Published by Flight Operations

`flight.scheduled` (`FlightScheduledEvent`), `flight.cancelled` (`FlightCancelledEvent`),
`flight.completed` (`FlightCompletedEvent`), each the flight after the change:

```json
{
  "eventId": "8a3c3f7e-5d1b-3b6f-9a52-0c1f7d0e4b21",
  "eventType": "FlightCompletedEvent",
  "occurredAt": "2026-11-20T14:45:00Z",
  "flightNumber": "4f1e9c1a-...",
  "registration": "CS-TPA",
  "aircraftModel": "A320neo",
  "routeId": "route-opo-lis",
  "originAirportIata": "OPO",
  "destinationAirportIata": "LIS",
  "distanceKm": 277.0,
  "fuelBurnRate": 3.8095,
  "scheduledDeparture": "2026-11-20T13:54:00",
  "scheduledArrival": "2026-11-20T14:45:00",
  "status": "COMPLETED",
  "revision": 2,
  "durationHours": 0.85
}
```
`durationHours` (scheduled departure to arrival) is **only in `flight.completed`**: a consumer that adds flight hours
never adds them for a booking or a cancellation. `revision` grows with every change of the flight: of two events of
the same flight, the higher revision is the newer. Header `instance`: the flight-ops instance that made the change.

### Published by Aircraft & Maintenance

`aircraft.created`, `aircraft.status.changed`, `aircraft.maintenance.scheduled`, `aircraft.maintenance.completed`:
fields as in `aircraft-maintenance-service/docs/AMQP_CONTRACT.md` (section 2). It consumes `flight.completed`.

## Shared bootstrap data

So the sample data lines up across services, use these fixed identifiers:

| Kind | IDs |
|---|---|
| Routes | `route-opo-lis` (OPO→LIS, 277 km, min range 350, min capacity 100), `route-lis-mad` (LIS→MAD, 502 km, 600, 150), `route-mad-opo` (MAD→OPO, 420 km, 500, 120) |
| Airports | `OPO`, `LIS`, `MAD` (operational, certified for at least `A320neo` and `737 MAX`) |
| Aircraft | `CS-TPA` (A320neo), `CS-TPB` (737 MAX), `CS-TPC` (A320neo), `CS-TPD` (777X), `CS-TPE` (A350) |
| Models | same specs as the PSOFT bootstrap (e.g. A320neo: fuel 24000, range 6300; 737 MAX: fuel 26000, range 6500) |
