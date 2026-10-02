# Flight Operations – architecture notes

Contents: [System architecture (p.20)](#system-architecture-pl3-p20) · [Performance (p.21)](#performance-pl3-p21) · [Consistency model](#consistency-model) · [Horizontal scaling](#horizontal-scaling)

## System architecture (PL3 p.20)

Three components, each running as **2 instances** with their **own database**. Instances of the same component
forward GET requests to each other (peer forwarding); components call each other over HTTPS with a service token.
Diagram sources: `docs/diagrams/*.mmd` (Mermaid); images regenerated with
`npx @mermaid-js/mermaid-cli -i <file>.mmd -o <file>.png -b white -s 2`.

### 1. The system

![System overview](diagrams/1-system-overview.png)

### 2. Inside one instance

![Inside an instance](diagrams/2-inside-an-instance.png)

### 3. HTTP GET forwarding (PL3 p.11)

![Forwarding](diagrams/3-forwarding.png)

### 4. Failure and automatic recovery (PL3 p.14)

![Failure and recovery](diagrams/4-failure-and-recovery.png)

### 5. A booking is stored on the aircraft's owner (P1 p.15, sharding by registration)

![Sharded booking](diagrams/5-booking-sharded.png)

### Key architectural benefits (PL3 p.20)

| Benefit | How this system provides it | Limit (honest) |
|---|---|---|
| **High availability**: the service continues if instances fail | 2 instances per component behind a load balancer (nginx: health-aware, fails over to the other instance); any instance answers any request (stateless JWT); retries, circuit breaker; Docker restarts an instance whose process stops; health checks bring a recovered instance back automatically | The flights stored on the failed instance are unavailable until it is back (no replication, by choice - PL3 p.24 Q3). Measured below. |
| **Horizontal scalability**: add instances as load grows | Instance profiles + `docker-compose.scale-3.yml`; aircraft sharded over the instances by rendezvous hashing (adding one only moves the aircraft it wins); requests spread by the load balancer | Reads that need *every* instance (utilization, fuel reports) do not get faster with more instances (load test, "Horizontal scaling" below) |
| **Fault isolation**: a problem in one instance doesn't cascade | Separate process + separate database per instance; timeouts on every remote call; circuit breaker stops calling a failing instance (no waiting on it, no flooding it); the failure is contained to its share of the data | Instances of one component share the same code, so a bug can affect all of them |
| **Geographic distribution**: instances in different regions | Instances only need each other's URL (peer lists, service URLs) and talk over HTTPS, so they could run anywhere | Not deployed that way; forwarding across regions would add real network latency to every forwarded request (see Performance) |

## Performance (PL3 p.21)

Measured with `./loadtest/performance.sh latency` and `./loadtest/performance.sh availability` (k6 in Docker;
every flight-ops instance limited to 1 CPU, HTTPS, PostgreSQL per instance; all on one laptop).
Raw output: `loadtest/results/latency.txt`, `loadtest/results/availability.txt`.

### Local vs forwarded response time

Each request looks up a flight either on the instance that stores it (**local**) or on the other one
(**forwarded**: one extra HTTPS call to the peer + its database lookup). Every answer was checked to really be
local / forwarded (`X-Data-Source` header): 100 % of 61 000 checks passed (run of 2026-10-02, after sharding by aircraft).

| | Local | Forwarded | Slide reference |
|---|---|---|---|
| 1 user, average (pure latency) | **2.5 ms** | **5.4 ms** | ~50 ms local, ~200 ms forwarded |
| 1 user, median / p95 | 1.9 / 3.8 ms | 4.0 / 9.5 ms | |
| 5 users (~264 req/s), average | 11.2 ms | 25.2 ms | |
| 5 users, median / p95 | 4.3 / 72 ms | 9.2 / 80 ms | |

* A forwarded request costs about **2×** a local one: the extra network hop and the peer's work (confirms quiz Q6:
  forwarded requests have higher latency).
* Both are far below the slide's reference values because all instances run on **one machine**: the "network" is
  local, a few tenths of a millisecond. Between machines or regions every forwarded request pays the real network
  round trip (~1 ms in a data centre, 20–150 ms between regions), which is why the slide expects ~200 ms.
* Under load the p95 of both rises to ~70–80 ms: that is queueing on a 1-CPU instance, not forwarding.

### Availability while an instance fails

A steady 20 requests/s for 150 s, each asking for a random flight (half the flights are stored on each instance).
Instance 2 is **killed** (like a crash) at ~30 s and started again at ~50 s; it is ready ~30–45 s later.
`./loadtest/performance.sh availability` sends each request to a random instance;
`./loadtest/performance.sh availability-lb` sends everything to the **load balancer** (nginx).

| Window | Random instance, first try | Random instance + client failover | **Through the load balancer** |
|---|---|---|---|
| 0–30 s (both up) | 100 % | 100 % | 100 % |
| while instance 2 is down | ~25 % | ~50 % | **~50 %** |
| after instance 2 is back | 100 % | 100 % | 100 % |
| **Whole run (150 s)** | **65.5 %** | **76.3 %** | **84.5 %** |

"Client failover": when the instance asked first cannot be reached, the client asks the other one. The load
balancer does that for every client: in the run through it **no request reached the dead instance**
(`fail_instance_down = 0`); nginx notices the failure on the first request, retries it on instance 1 and leaves
instance 2 out for 10 s at a time. (The higher whole-run figure is also helped by a shorter outage in that run.)

What still fails during the outage is the **flights stored on instance 2**: the reachable instance answers 404
"could not be reached" for them. Instance 1's own data stays 100 % available the whole time, and recovery is
automatic: as soon as instance 2 answers again, everything is back to 100 % within one 10-s window.

### What 99.9 % would need (slide: "with proper instance distribution and health monitoring")

99.9 % allows ~43 minutes of failures per month. With a single instance failure, this system reaches 100 % for the
data of the surviving instance, but not for the whole service. The three gaps and their fixes:

| Gap | Effect measured | Status |
|---|---|---|
| Clients send requests to a dead instance | 25 % → 50 % during the outage | **Done**: nginx load balancer (`lb/nginx.conf`), passive health checks + failover |
| A crashed instance stays down | length of the outage | **Done** for crashes: `restart: unless-stopped` + container health check (a process that exits is back in ~12 s). A *killed* container or a dead machine still needs an orchestrator (Kubernetes / Swarm, PL3 p.25 "later") |
| Each flight exists on one instance only | the other 50 % (404 for the dead instance's data) | **Not done, by choice**: replication (every flight on 2 instances) would close it, but the week-3 design keeps one owner per item (PL3 p.11, p.24 Q3). See "Consistency model" |

With replication factor 2 on top of the load balancer, the measured scenario (one instance down) would stay at ~100 %.

### Other considerations from the slide

* **Request timeouts**: every remote call has one (peers 1.5 s, forwarded bookings 10 s, other services 2 s,
  health checks 1 s, load balancer 2 s to connect / 15 s to answer), and the
  circuit breaker turns a dead instance from "wait for the timeout" into "skip immediately" (diagram 4).
* **Caching**: forwarded lookups could be cached for a few seconds to save the extra hop, at the cost of possibly
  serving a stale status (e.g. a flight cancelled on the other instance a moment ago). Not done: correctness of the
  flight status was preferred over the ~4 ms saved.
* **Geographic placement**: put the instances that forward to each other close together (same region), or replicate
  the data to each region so reads stay local.

## Consistency model

*(Assignment 1: "documentation of design decisions, consistency model and fault-tolerance mechanisms")*

### In one sentence

Every flight has **exactly one owner**: the instance that owns its **aircraft** (sharding by registration). Reads of a
single flight are always answered by its owner, so they are up to date; "no double-booking" is **guaranteed while
the aircraft's owner is reachable**, and during failures the system prefers **availability** over completeness
(AP in CAP).

### How data is placed

* **Partitioned by aircraft, not replicated** (P1 p.15 "data-based sharding: partition by operational identifiers,
  e.g. aircraft registration numbers"). Every instance knows the list of all instances (`flightops.cluster`) and
  computes the owner of an aircraft with **rendezvous hashing** (highest SHA-256 of instance name + registration):
  all instances agree without talking to each other, and adding an instance only moves the aircraft it wins.
  A booking that arrives at another instance is **forwarded to the owner** (`POST /internal/flights`, diagram 5;
  the response says where it was stored: `X-Stored-On`). There are no copies, so there are never two different
  versions of the same flight - matching the practical session (PL3 p.24, quiz Q3: instances do **not** keep
  identical copies of all data).
* **No ID clashes.** Flight numbers are UUIDs, generated independently on each instance, so two instances can never
  create the same id (PL3 p.18 "Data inconsistency: same ID on multiple instances").
* **Snapshots of other services' data.** When a flight is scheduled, the aircraft model, route distance and airports
  are copied into it. They describe the flight *as scheduled* and are not updated if the aircraft or route changes
  later (deliberate: a scheduled flight keeps the data it was validated against).

### What a client can rely on

| Operation | Guarantee | Why |
|---|---|---|
| Read one flight (`GET /api/scheduled-flights/{n}`) | **Up to date** while its owner is reachable; otherwise `404` with "could not be reached" | Answered by the owner's database (locally or forwarded), never from a copy or cache |
| Read your own write | **Yes**, from any instance | The write is committed in the owner's database before the `201`; any instance finds it there |
| Cancel a flight | **Applied once**, by the owner; a second cancel is `409` | Forwarded to the owner (single writer per flight), `@Version` optimistic locking, `PATCH` never retried |
| Lists and reports (flights of an aircraft, departures, utilization, fuel) | Up to date **for the reachable instances**; **partial** while an instance is down | Scatter-gather over all instances; an unreachable peer is skipped (logged) instead of failing the whole request |
| "An aircraft is never in two flights at the same time" | **Guaranteed** while the aircraft's owner is reachable (all its bookings go there, wherever they arrive); **best effort** when the owner is down - see below | All bookings of an aircraft meet at its owner, where the per-aircraft lock serialises them |

### The weak spot: double-booking an aircraft

The booking is made on the aircraft's owner (forwarded there if it arrived elsewhere). The owner:

1. **locks the aircraft** on its own database: a row per aircraft in `aircraft_booking_lock`, locked with
   `SELECT ... FOR UPDATE` until the booking's transaction commits (`services.AircraftBookingLocks`). A second
   booking for the same aircraft on this instance waits, and then sees the first one's flight;
2. checks its **own** database for overlapping flights of that aircraft, and
3. checks its **peers**, by asking them for that aircraft's active flights.

**Fixed: two bookings at the same moment on the same instance.** Locking the overlapping *flights* (the PSOFT
monolith's approach) is not enough: when there is no overlapping flight yet, there is nothing to lock, so both
bookings passed the check. `ConcurrentBookingTest` sends 2 simultaneous overlapping bookings, 20 times: before the
fix **both succeeded in all 20 rounds**; with the aircraft lock exactly one succeeds every time (also checked over
HTTP on PostgreSQL: 20 × "201 + 409"). If the lock can't be obtained within 10 s the booking gets a `409`
"another booking for this aircraft is in progress".

**Fixed: two bookings at the same moment on different instances.** Both are forwarded to the aircraft's owner,
whose lock lets only one through. `TwoInstancesIntegrationTest` sends simultaneous overlapping bookings of the same
aircraft to instance 1 *and* instance 2, 10 times: exactly one `201` and one `409` every time (Postman folder
"09 Sharding by aircraft" does the same).

A double booking can still happen only when **the aircraft's owner is down**: if the booking certainly did not reach
it (connection refused, circuit open), it is made on the receiving instance instead (availability first) with the
best-effort check of the reachable peers; when the owner is back, both flights exist. If the owner *was* reached but
did not answer in time, the outcome is unknown, so the client gets `503 "the booking may or may not have been made"`
instead of risking a second booking.

This is the price of choosing availability: bookings keep working with an instance down. The overlap is not lost or
hidden: both flights exist and can be listed for that aircraft.

**How it could be made fully consistent** (not implemented):

* **Refuse instead of guess**: reject a booking (`503`) while the aircraft's owner is unreachable, which turns the
  system from AP to CP for writes: always correct, but bookings of that aircraft stop while its owner is down.

### During a failure (CAP)

With one instance down (a partition from the others' point of view):

* the other instances keep serving **their own data** and accept **new bookings** (Availability);
* the down instance's flights are **not visible** (404 for single reads, missing from lists) instead of the whole
  request failing (Partition tolerance);
* consistency is weakened only for the cross-instance overlap rule above (bookings on the same instance stay exact).

When the instance comes back, nothing has to be reconciled: no other instance ever held a copy of its data, so its
flights are simply visible again (measured in "Availability while an instance fails" above, and checked by the
Postman folder "03 Resilience").

### If replication is added later

Replicating each flight to a second instance would remove the "data unavailable while its owner is down" gap
(Performance section). It would also bring the classic replication problems that the current design avoids:
replicas that disagree after a failure (need versioning / a "last write wins" or quorum rule, e.g. R + W > N), and
writes that must reach two instances. PL3 p.18 lists the same options: "data versioning or authoritative instance".

## Horizontal scaling

### Instances and ports

Every component starts with **2 instances**, as recommended in the practical session (PL3, page 7). More are added only when load testing shows they help.

| Component | Instance 1 | Instance 2 | Instance 3 (scale-up) |
|---|---|---|---|
| aircraft-maintenance-service | 8081 | 8091 | 8101 |
| airports-routes-service | 8082 | 8092 | 8102 |
| flight-operations-service | 8083 | 8093 | 8103 |

Port scheme: base port + 10 per extra instance. Each instance has its own database (data isolation), so instances
share nothing except the network: PostgreSQL containers `flightops-db-1/2/3` on host ports 5433/5434/5435
(in-memory H2 `flightops_db_1/2/3` when run without the `postgres` profile, e.g. in the automated tests).

### What makes it scalable

* **Stateless requests.** Authentication is a self-contained JWT, so there are no server sessions and any instance can
  serve any request.
* **Client-side load balancing.** Calls to another service rotate round-robin over its instances and fail over to
  the next one on timeout or 5xx (`ReplicatedServiceClient`).
* **Service discovery with hardcoded lists.** `CLUSTER` (all flight-ops instances, by name), `AIRCRAFT_SERVICE_URLS`
  and `AIRPORTS_ROUTES_SERVICE_URLS`.
* **Sharded data.** A flight is stored on the instance that created it. Reads that need everything ask every peer
  and merge the answers (scatter-gather, `FlightQueryService`).

### How to add an instance

1. Start the new instance with its own port and database.
2. Give **every** instance (old and new) the same list of all instances in `CLUSTER`
   (`instance1=url,instance2=url,instance3=url`), and add the new one to the load balancer's upstream list.
3. Restart the existing instances so they pick up the new list. Aircraft are re-sharded by rendezvous hashing: only
   the aircraft the new instance wins move to it (~1/3 with 3 instances, `ClusterTest`); their *old* flights stay
   where they are and are still found by the peer queries, new bookings go to the new owner.

The same steps are packaged as an override file:

```bash
docker compose -f docker-compose.yml -f docker-compose.scale-3.yml up --build
```

Without Docker: `./scripts/run-local.sh -n 3`.

Limitation: because the peer lists are hardcoded, scaling needs a configuration change and restarts. The next step
would be dynamic discovery: a service registry (Eureka, Consul) or DNS-based discovery (e.g. Kubernetes headless
services). Instances would then register themselves, and peers would be found at runtime.

### Load test

Tool: [k6](https://k6.io), run in Docker (`./loadtest/run.sh 2` and `./loadtest/run.sh 3`).

* **Workload:** 50 virtual users for 60 s, each request to a random instance (like a load balancer) and a random
  read endpoint: `/api/aircraft-utilization`, `/api/aircraft-utilization/CS-TPA`, `/api/fuel-efficiency/aircraft`,
  `/api/fuel-efficiency/routes`. All of them need the data of every instance, so they exercise the peer-to-peer
  path.
* **Conditions:**
  * Every flight-ops instance is limited to **1 CPU and 768 MB** (`loadtest/limits.yml`), to simulate one small
    machine per instance; all on one laptop.
  * HTTPS between all parties.
  * 2 × 30 s warm-up before measuring: on 1 CPU the JVM's JIT compilation otherwise dominates the first minute.

**Results (2026-10-02), each instance with its own PostgreSQL container:**

| Instances | Throughput | Median latency | p95 | p99 | Errors |
|---|---|---|---|---|---|
| 2 | **57 req/s** | 798 ms | 1.79 s | 2.29 s | 0 % |
| 3 | **57 req/s** | 766 ms | 2.08 s | 2.69 s | 0 % |

(An earlier run with in-memory H2 gave the same picture: 59 req/s with 2 instances, 53 with 3.)

Raw output: `loadtest/results/`.

### What the results mean

**For this workload, a third instance does not increase capacity.** Every request is a scatter-gather read:
the receiving instance does its local query *and* asks each of the other N−1 instances. The total work per request
therefore grows with N, at the same rate as the capacity added by the new instance, so throughput stays flat.

Conclusion: stay at **2 instances**. Adding instances only pays off once most reads stop fanning out to every peer:

1. **Route by owner.** Pick the instance that stores a flight from a hash of the aircraft registration (consistent
   hashing). Requests about one aircraft then go to one instance, with no fan-out, so capacity grows with N. This
   also removes the double-booking race, because all bookings for an aircraft are serialised on one instance.
2. **Replicate.** Copy every write to the other instances (or to k of them), so reads can be answered locally.
   Reads then scale with N, writes get more expensive, and the system gains redundancy: a flight survives the
   loss of the instance that created it.
3. **Cache aggregates.** Utilization and fuel reports change rarely; caching them for a few seconds per instance
   removes most of the fan-out.

### Performance problems found by load testing (fixed)

| Problem | Effect | Fix |
|---|---|---|
| JDK `HttpClient` completes responses asynchronously; on 1 CPU the JDK runs those completions on a **new thread per call** | thread creation dominated CPU | Apache HttpClient 5 (synchronous, pooled), pool sized explicitly (default is only 5 connections per peer) |
| jjwt does a ServiceLoader scan every time a parser or builder is created; the code built a parser 3× per request and signed a new service token per call | high CPU per request | parser built once and each token parsed once; service tokens cached (5 min lifetime, renewed 1 min before expiry) |
| Short runs on a cold JVM | numbers ~3× too low | warm-up runs before measuring |
