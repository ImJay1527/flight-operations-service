# Testing

[Documentation guide](README.md) · [Running](running.md) · [Testing](testing.md) · [Operations](operations.md) · [Security](security.md) · [Limitations](limitations.md)

Three levels of evidence (PL3 p.15-17): JUnit tests, including integration tests with real instances,
databases and broker; the Postman collection, run against three local instances or the whole system; and the
request id that traces one request through every instance it touches.

## Automated tests (JUnit)

```bash
./mvnw test
```

The tests that use MongoDB or RabbitMQ (`MongoReadModelTest`, `CqrsIntegrationTest`, `MessagingIntegrationTest`,
`QueryInstanceIntegrationTest`)
start them in Docker (Testcontainers) and are skipped when Docker is not running; CI runs them.

| Test | What it covers |
|---|---|
| `services/*Test`, `resilience/*Test`, `cluster/ClusterTest`, `common/crypto/*Test`, `replication/ReplicaStoreTest`, `replication/ReplicaSyncTest`, `api/*Test` | Unit tests: forwarding/merge logic, scheduling rules, routing a booking to the aircraft's owner (and to its backup when the owner is down), retry/backoff, circuit breaker, rendezvous hashing (agreement, spread, minimal movement, replica sets), DNS discovery (pods joining and leaving), hand-off of flights an instance no longer holds, applying copies (newest wins, any order, conflicts reported), encryption, fuel efficiency and utilization (ported from PSOFT) |
| `ConcurrentBookingTest`, `EncryptionAtRestTest` | Real database: simultaneous bookings of one aircraft (exactly one succeeds), what is really stored (raw SQL shows only ciphertext) |
| `SecurityIntegrationTest` | One instance: login, 401/403 rules, service-only `/internal/**` |
| `TwoInstancesIntegrationTest` | **Two real instances** started on free ports, talking over HTTP, replication off (the week-3 behaviour on its own): local access, forwarding (`X-Data-Source`), the same request id in both instances' logs, cancel forwarded to the owner, a booking stored on the aircraft's owner, simultaneous bookings via both instances (one wins), instance 2 stopped → 503 (+ `Retry-After`), partial lists flagged, circuit open; `201` with `Location` |
| `FlightQueriesTest`, `readmodel/ReadDatabaseDownTest` | CQRS query side: the read model answers while READY and the write side is not asked; a flight not yet in the read model, a read model not ready or a read database failing during a query → the write side answers; a failed projection makes the model STALE without failing the write |
| `readmodel/MongoReadModelTest` | **Real MongoDB** (Testcontainers): projection read back, fields encrypted at rest, newer revision wins and an old one is ignored, the same change twice counted once, reports recomputed on cancel, utilization by month, departures window, rebuild (removes flights nobody holds, keeps new projections, nothing removed when incomplete) |
| `CqrsIntegrationTest` | **Two real instances with the `cqrs` profile**, each with its own write database (H2) and read database (MongoDB), replication off so the write side is split: both read models hold every flight, read your own write at once (also a booking forwarded to the owner), a change made on the other instance reaches the read model, a cancel updates the reports on both, `/api/v2/unicorns` answered from the read model (the same moods from the write side), the read database paused → the write side answers (`X-Read-Model: STALE`) until it is back |
| `services/UnicornMoodTest` | The airplane moods: in the air now, leaving within 24 h, 10 h in the last week (only the part inside the week counts), nothing scheduled, a trip planned; cancelled flights ignored |
| `messaging/FlightEventTest`, `messaging/EventOutboxTest` | The events as other services see them (the fields Aircraft & Maintenance reads, flight hours only in `flight.completed`, the same change always the same `eventId`); the outbox with the broker down: a booking and a cancel each leave one event, encrypted at rest, a refused booking and a copy from another instance leave none |
| `clients/LocalAircraftCopyTest`, `messaging/AircraftEventsTest` | The aircraft copy: an aircraft from an event needs no REST call, one not in the copy is read once and kept, an old copy is read again or used when the REST API is down (503 only for an aircraft never seen); the events with the contract's JSON given to the listener: an aircraft unknown to the built-in data can be booked after its `aircraft.created`, `UNDER_MAINTENANCE` → booking 409 until it is `AVAILABLE` again, a duplicate or older event changes nothing, a broken event is rejected (dead-letter queue) |
| `clients/LocalRouteCopyTest`, `messaging/RouteEventsTest` | The route and airport copies: a route is read once with its two airports and kept, a route and airports from events need no REST call; with the JSON Airports & Routes publishes (`@type`, `AIRPORT_CREATED`, `airport.status`): a route unknown to the built-in data can be booked after its events, a closed airport → 409 until it is open again (an older event changes nothing), a certification added lets a new model fly there, a deactivated route → 409, a `route.created` without the requirements is left to the REST API, a broken event is rejected |
| `MessagingIntegrationTest` | **Real RabbitMQ** and two instances with the `cqrs` and `amqp` profiles, the 30 s sync turned off: one queue per instance with its consumer, a booking reaches the other instance's read model and another service's queue through the broker only, a completed flight announced with its flight hours (and completing it again → 409), the broker paused → bookings still 201 and their events wait in the outbox until it is back, a message that can't be read → dead-letter queue after the retries, `aircraft.created` / `aircraft.status.changed` sent the way Aircraft & Maintenance sends them reach both instances' aircraft copies (booking 201, then 409 under maintenance), and the same for `airport.created` / `route.created` / `route.deactivated` |
| `QueryInstanceIntegrationTest` | **Real RabbitMQ and MongoDB**, two command instances and a **query instance** (profile `query`), the 30 s sync turned off: the query instance is not a member (`role: queries only`) and has its own queue; a booking reaches it through the broker only; asked for the write side, a command instance answers (`X-Data-Source: peer:`); unicorns from its read model; a booking or a cancel sent to it → 405 (401 without a token, login works); commands work with it stopped, and a new query instance starts with the whole read model |
| `ReplicationIntegrationTest` | **Three real instances**, 2 copies of each flight: a booking copied to the backup, the owner stopped → its flights still readable and lists complete, bookings go to the backup, a cancel while it is down; the owner restarts with an empty database and catches up; the backup's outbox empties; `/api/cluster/overview` shows the 3 instances and each flight once as owner and once as backup |

## Postman (PL3 p.17)

Files in `postman/`:
`flight-operations.postman_collection.json` (151 requests with test scripts) and two environments (one URL per
instance): `local.postman_environment.json` (HTTP) and `local-https.postman_environment.json` (HTTPS). Import all three.

1. Start 3 instances in **stub** mode (built-in aircraft/route data, so flights can be created without the other
   two services): `./scripts/run-local.sh --stub`, or in VS Code the compound "3 instances for the Postman tests".
   Wait for **READY FOR TESTS** (from VS Code: run `./scripts/wait-ready.sh` in a terminal).
2. Select the environment "Flight Ops - local (3 instances)".
3. Run the whole collection (about 30 s). Nothing to do by hand: folder **03 Resilience** stops instance 2 itself
   (`POST /actuator/shutdown`, only possible in stub/test mode and only for ADMIN), checks the failure behaviour, then
   waits until `run-local.sh` has restarted instance 2 and checks the automatic recovery.
   With the VS Code compound there is no automatic restart: the last request of folder 03 is then skipped.

The collection also runs against the **whole system** (no `--stub`: the real aircraft and airports/routes services,
e.g. `docker compose up`). The first folder detects the mode from `GET /api/cluster/health` (in stub mode no
instances of the other services are known): in the whole system, folder **10** checks requests that cross the three
services, and folder 03 is skipped (it needs `/actuator/shutdown`, which only stub mode exposes). The failure tests
for the whole system are folder **11**, run by a script that stops and starts the containers between its phases
(Postman can't stop a container):

```bash
docker compose up -d --build                        # whole system, wait until every instance is healthy
./scripts/test-failures.sh                          # 11a-11g, about 2 min; starts again whatever it stopped
./scripts/test-failures.sh --skip-other-services    # without 11e (aircraft/airports on other machines)
```

By hand (Postman app, e.g. in a demo): run 00 Setup, set the environment variable `failurePhase` to `a` and run
11a, `docker compose stop flightops-2`, set it to `b` and run 11b, and so on (each phase's description says what to
stop or start). In a normal run of the collection folder 11 is skipped.

Last runs (2026-11-22, the current versions of the three services):

| Setup | Assertions | Failed |
|---|---|---|
| Stub mode, `./scripts/run-local.sh --stub --tls --queries 2` (folder 17 included) | 289 | 0 |
| Whole system, `docker compose up` | 253 | 0 |
| Whole system with `--profile queries`, folders 00 and 17 | 37 | 0 |
| Whole system with `--profile queries`, one instance of each other service, whole collection | 278 | 0 |
| Whole system, folder 11 through `./scripts/test-failures.sh` (phases 11a to 11g) | 71 | 0 |

The stub run has no request errors (folder 03 asks instance 1 whether instance 2 is up, instead of calling the
stopped instance itself). A run without query instances reports one request error: folder 17's check for a query
instance on port 8113, after which the folder is skipped.

| Folder | PL3 p.16 test |
|---|---|
| 01 Local Data Access | 01: data on instance 1, asked from instance 1 → 200, `X-Data-Source: local` (no peer query) |
| 02 Successful Forwarding | 02: data created on instance 2, asked from instance 1 → 200; `X-Data-Source: local` when instance 1 keeps a copy (2 instances, 2 copies), `peer:…` when it doesn't (3 instances, or `REPLICATION_FACTOR=1`) |
| 03 Resilience | 03 + P1 p.2 redundancy: instance 2 stopped → its flights still 200 from their copies, lists complete (`X-Unreachable-Peers: 1`, no `X-Partial-Result`), a booking for its aircraft taken by the backup and queued in its outbox; instance 2 restarted → trusted again, has caught up with that booking, outbox empty (PL3 p.14 automatic recovery). With `REPLICATION_FACTOR=1`: 503 (not 404: the flight exists but can't be reached) and `X-Partial-Result` as in PL3 p.16 |
| 04 Load Distribution | 04: requests alternate between instances, `X-Instance` shows who answered, response times checked |
| 05 Edge Cases | 05: invalid ids, malformed JSON, missing fields, business rules (409), 401/403 |
| 06 Monitoring | PL3 p.19 metrics: lookups counted by source (read model, local, forwarded; the medians are logged), forwarding success rate, peer health, both instances served requests |
| 07 Three instances | PL3 p.27 "test with 3+ instances": data created on instance 3 reachable from 1 and 2, every instance sees 2 healthy peers. Skipped when only 2 instances run (`-n 2`) |
| 08 Encryption in transit | HTTPS only: works with a verified certificate, plain HTTP to the same port is refused, peers are called over HTTPS. Only with the HTTPS environment |
| 09 Sharding by aircraft | P1 p.15: owner lookup, a booking sent to instance 1 stored on its owner instance 2 (`X-Stored-On`, `X-Replicas` owner first), simultaneous bookings of the same aircraft via both instances → one 201, one 409 |
| 10 Whole system | Week 5 slides p.14 "cross-component tests" (whole system only): the other services are known and healthy; a booking checked with both services → 201; aircraft unknown to Aircraft & Maintenance and route unknown to Airports & Routes → 404; departures of an airport the airports service knows → 200, doesn't know → 404 |
| 12 CQRS - commands and queries | Assignment 2: the read model is READY; a booking (command) read back at once from the read model (`X-Read-Side: read-model`, `X-Data-Source: read-model`, `X-Read-Model-Updated`); the same query from the write side with `X-Read-Side: write-side`; another instance's read model gets it (eventual consistency, polled every 2 s); a cancel via instance 2 read back there at once; the utilization report and the departures board come from the read model. Skipped without a read database (`--h2`) |
| 13 Messaging - events through RabbitMQ | Assignment 2: instance 1 connected to the broker, its own queue with one consumer, empty outbox; a flight of CS-TPA booked to depart in 3 s, then completed (`flight.completed`), completing it again → 409; the instance holding **no copy** of CS-TPA sees it COMPLETED in its read model a second later (only the broker can have told it) and counts one more `flight.completed` received; RabbitMQ's management API: one durable queue per instance with its consumer, dead-letter exchange and limits, `flight.events.dlq`. Skipped without a broker (`--h2`) |
| 14 Unicorns | Assignment 2 mock endpoint (runs on its own: its first request checks whether there is a read model): `GET /api/v2/unicorns` returns a JSON list, each airplane with a known mood and a message, from the read model (`X-Read-Side`); CS-TPB booked to leave in 3 s is `FLYING`, and after completing the flight it isn't any more; unknown airplane → 404, no token → 401 |
| 15 Aircraft events | Assignment 2 (runs on its own, like 14 and 16): `aircraft.created` for CS-EVP (unknown to the built-in data) sent through RabbitMQ's management API → booking 201 from the copy; `aircraft.status.changed` → `UNDER_MAINTENANCE` → booking 409; a late, older `AVAILABLE` changes nothing (still 409); a newer `AVAILABLE` → 201; the owner's metrics: lookups from the copy, aircraft events received and the late one ignored. Skipped without a broker (`--h2`) |
| 16 Route and airport events | Assignment 2: `airport.created` FNC and PDL and `route.created` route-fnc-pdl (unknown to the built-in data), in the JSON Airports & Routes publishes → booking 201 from the copies; a 737 MAX → 409 (FNC not certified) until `airport.certification.added`; `airport.status` PDL CLOSED → 409, a late older OPERATIONAL changes nothing, a newer one reopens it; `route.deactivated` → 409; a `route.created` without the requirements (what the service sends today) is ignored and the REST API decides (404 in stub mode; in the whole system Airports & Routes consumes the event too, so its REST API may know the route: 201 or 404); the owner's metrics: lookups from the copy, events received and ignored. Skipped without a broker (`--h2`) |
| 17 CQRS - query instances | Assignment 2, separate processes (runs on its own; skipped without query instances: `run-local.sh --queries 2`, `docker compose --profile queries`, or on Kubernetes set `queryInstance` to https://localhost:9444): instance 1 is a command instance and the query instance is not a member; the query instance answers queries only, its read model READY, its own queue; a booking on instance 1 read on the query instance from its read model (it came through the broker, polled every second); with `X-Read-Side: write-side` a command instance answers (`peer:`); a booking sent to it → 405 `Allow: GET, HEAD`; unicorns from its read model; a cancel on instance 2 seen there as CANCELED |
| 11 Whole system - failures | Week 5 p.14 "stop container, verify 404" (this system answers `503` while the data may be on a stopped instance, and `404` only when it is known not to exist: [ADR 0004](decisions/0004-503-for-unreachable-data.md)), Week 4 p.11, PL3 p.14/p.16 (whole system, `scripts/test-failures.sh`). **11a** CS-TPC's holders recorded (owner instance2), flights booked. **11b** flightops-2 stopped: its flight read from the copy (`X-Data-Source: peer:…`), list complete (`X-Unreachable-Peers: 1`), the backup takes a booking and queues it for instance 2. **11c** the backup stopped too: **503** "could not be reached" with `Retry-After`, list flagged `X-Partial-Result`, other aircraft unaffected, a booking still accepted where it was received (AP). **11d** both started: caught up (`X-Data-Source: local`), outbox empty, the left-over copy handed off. **11e** aircraft-1 and airports-1 stopped: bookings still 201 (their other instances). **11f** the broker (rabbitmq) stopped: the instances stay UP and see it down, a booking still 201 and readable, its event waits in an outbox. **11g** the broker started: every outbox empty again, the flight in the read models |

**Over HTTPS** (evidence of encryption in transit): start the instances with `./scripts/run-local.sh --tls --stub`
and select "Flight Ops - local HTTPS (3 instances)". Postman must trust the dev CA: Settings → Certificates →
CA certificates → on → select `certs/ca.crt` (created by `./scripts/generate-dev-certs.sh`). If that setting isn't
available (Postman on the web), turn off Settings → General → "SSL certificate verification" instead: the traffic is
still encrypted, only the server's identity isn't checked. Command line:
`npx newman run postman/flight-operations.postman_collection.json -e postman/local-https.postman_environment.json --ssl-extra-ca-certs certs/ca.crt`.

From the command line (same files): `npx newman run postman/flight-operations.postman_collection.json -e postman/local.postman_environment.json --folder "01 Local Data Access"`.

Postman **on the web** reaches `localhost` only through the
[Postman Desktop Agent](https://www.postman.com/downloads/postman-agent/) (or use the Postman desktop app).

## Tracing a request across instances (PL3 p.15)

Every response has `X-Instance` (which instance answered) and `X-Request-Id`. The request id is passed on to peers,
and every log line shows `[instance] [request id]`, so one request can be followed through all instances:

```
INFO [instance1] [postman-forwarding-1] AUDIT : user=atcc ... uri=/api/scheduled-flights/2db4... status=200 durationMs=31
INFO [instance2] [postman-forwarding-1] AUDIT : user=flight-operations-service roles=[ROLE_SERVICE] ... uri=/internal/flights/2db4...
```

Send your own id with the `X-Request-Id` header to find a request easily (`grep postman-forwarding-1 logs/*.log`).
