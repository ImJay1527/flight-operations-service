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

### Key architectural benefits (PL3 p.20)

| Benefit | How this system provides it | Limit (honest) |
|---|---|---|
| **High availability**: the service continues if instances fail | 2 instances per component; any instance answers any request (stateless JWT); retries, circuit breaker and failover between instances; health checks bring a recovered instance back automatically | Data stored only on the failed instance is unavailable until it is back (no replication); clients must retry on the other instance (no load balancer yet). Measured below. |
| **Horizontal scalability**: add instances as load grows | Instance profiles + `docker-compose.scale-3.yml`; sample data split over N instances; requests spread round-robin | Reads that need *every* instance (utilization, fuel reports) do not get faster with more instances (load test, "Horizontal scaling" below) |
| **Fault isolation**: a problem in one instance doesn't cascade | Separate process + separate database per instance; timeouts on every remote call; circuit breaker stops calling a failing instance (no waiting on it, no flooding it); the failure is contained to its share of the data | Instances of one component share the same code, so a bug can affect all of them |
| **Geographic distribution**: instances in different regions | Instances only need each other's URL (peer lists, service URLs) and talk over HTTPS, so they could run anywhere | Not deployed that way; forwarding across regions would add real network latency to every forwarded request (see Performance) |

## Performance (PL3 p.21)

Measured with `./loadtest/performance.sh latency` and `./loadtest/performance.sh availability` (k6 in Docker;
every flight-ops instance limited to 1 CPU, HTTPS, PostgreSQL per instance; all on one laptop).
Raw output: `loadtest/results/latency.txt`, `loadtest/results/availability.txt`.

### Local vs forwarded response time

Each request looks up a flight either on the instance that stores it (**local**) or on the other one
(**forwarded**: one extra HTTPS call to the peer + its database lookup). Every answer was checked to really be
local / forwarded (`X-Data-Source` header): 100 % of 55 000 checks passed.

| | Local | Forwarded | Slide reference |
|---|---|---|---|
| 1 user, average (pure latency) | **4.1 ms** | **8.7 ms** | ~50 ms local, ~200 ms forwarded |
| 1 user, median / p95 | 3.4 / 6.3 ms | 7.3 / 20 ms | |
| 5 users (~310 req/s), average | 9.2 ms | 21.7 ms | |
| 5 users, median / p95 | 2.9 / 70 ms | 6.9 / 78 ms | |

* A forwarded request costs about **2×** a local one: the extra network hop and the peer's work (confirms quiz Q6:
  forwarded requests have higher latency).
* Both are far below the slide's reference values because all instances run on **one machine**: the "network" is
  local, a few tenths of a millisecond. Between machines or regions every forwarded request pays the real network
  round trip (~1 ms in a data centre, 20–150 ms between regions), which is why the slide expects ~200 ms.
* Under load the p95 of both rises to ~70–80 ms: that is queueing on a 1-CPU instance, not forwarding.

### Availability while an instance fails

A steady 20 requests/s for 150 s, each asking a random instance for a random flight (half the flights are stored on
each instance). Instance 2 is **killed** (like a crash) at ~30 s and started again at ~50 s; it is ready ~45 s later.

| Window | ok on first try | ok with client failover |
|---|---|---|
| 0–30 s (both up) | 100 % | 100 % |
| 30–100 s (instance 2 down) | ~25 % | ~50 % |
| 100–150 s (instance 2 back) | 100 % | 100 % |
| **Whole run (150 s)** | **65.5 %** | **76.3 %** |

"Client failover": when the instance asked first cannot be reached, the client asks the other one (what a load
balancer would do).

Why ~25 % / ~50 % during the outage: half the requests go to the dead instance (fail, unless the client fails over);
of the requests that reach instance 1, half ask for a flight stored on instance 2, which gets a 404 "could not be
reached" until instance 2 is back. Instance 1's own data stays 100 % available the whole time, and recovery is
automatic: as soon as instance 2 answers again, everything is back to 100 % within one 10-s window.

### What 99.9 % would need (slide: "with proper instance distribution and health monitoring")

99.9 % allows ~43 minutes of failures per month. With a single instance failure, this system reaches 100 % for the
data of the surviving instance, but not for the whole service. The three gaps and their fixes:

| Gap | Effect measured | Fix |
|---|---|---|
| Clients send requests to a dead instance | 25 % → 50 % with client failover | A **load balancer** with health checks in front of the instances (nginx / HAProxy, PL3 p.25) |
| Each flight exists on one instance only | the other 50 % (404 for the dead instance's data) | **Replication**: store every flight on 2 instances (replication factor 2), so a single failure loses no data |
| Restart takes ~30–50 s (JVM start on 1 CPU) | length of the outage | An orchestrator that restarts / replaces instances automatically (Docker restart policy, Kubernetes), and a spare instance |

With a load balancer and replication factor 2, the measured scenario (one instance down) would stay at ~100 %.

### Other considerations from the slide

* **Request timeouts**: every remote call has one (peers 1.5 s, other services 2 s, health checks 1 s), and the
  circuit breaker turns a dead instance from "wait for the timeout" into "skip immediately" (diagram 4).
* **Caching**: forwarded lookups could be cached for a few seconds to save the extra hop, at the cost of possibly
  serving a stale status (e.g. a flight cancelled on the other instance a moment ago). Not done: correctness of the
  flight status was preferred over the ~4 ms saved.
* **Geographic placement**: put the instances that forward to each other close together (same region), or replicate
  the data to each region so reads stay local.

## Consistency model

*(Assignment 1: "documentation of design decisions, consistency model and fault-tolerance mechanisms")*

### In one sentence

Every flight has **exactly one owner** (the instance that created it); reads of a single flight are always answered
by its owner, so they are up to date; rules that span several instances (no double-booking of an aircraft) are only
checked **best-effort**, and during failures the system prefers **availability** over completeness (AP in CAP).

### How data is placed

* **Partitioned, not replicated.** A flight is stored only in the database of the instance that received the
  `POST` (its *owner*). There are no copies, so there are never two different versions of the same flight. This
  matches the practical session (PL3 p.24, quiz Q3: instances do **not** keep identical copies of all data).
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
| "An aircraft is never in two flights at the same time" | **Best effort only** - see below | The rule spans instances, and there is no distributed lock |

### The weak spot: double-booking an aircraft

Before saving a new flight, the receiving instance checks for overlapping flights of the same aircraft:

1. on its **own** database, with a `PESSIMISTIC_WRITE` lock on the overlapping rows, and
2. on its **peers**, by asking them for that aircraft's active flights.

A double booking can still happen when:

* **two requests for the same aircraft arrive at the same moment on different instances**: each checks the other
  before the other has saved;
* **a peer is down**: its flights are skipped by the check (availability first), so a booking that overlaps one of
  them is accepted;
* **two requests arrive at the same moment on the same instance**: the lock only covers flights that *already
  exist*; when there is no overlapping flight yet there is nothing to lock, so both can pass the check. (This one was
  already present in the PSOFT monolith.)

This is the price of choosing availability: bookings keep working with an instance down. The overlap is not lost or
hidden: both flights exist and can be listed for that aircraft.

**How it could be made strongly consistent** (not implemented):

* **One owner per aircraft**: send every booking for an aircraft to the instance chosen by a hash of its
  registration (consistent hashing). All flights of an aircraft then live on one instance and the check is local.
  This also removes the fan-out for per-aircraft reads (see "Horizontal scaling").
* **Lock the aircraft, not the flights**: e.g. a row per aircraft locked with `SELECT ... FOR UPDATE`, or a PostgreSQL
  advisory lock on the registration, so concurrent bookings on one instance are serialised even when no flight
  exists yet.
* **Refuse instead of guess**: reject a booking (`503`) while a peer is unreachable, which turns the system from AP to
  CP for writes: always correct, but bookings stop during a failure.

### During a failure (CAP)

With one instance down (a partition from the others' point of view):

* the other instances keep serving **their own data** and accept **new bookings** (Availability);
* the down instance's flights are **not visible** (404 for single reads, missing from lists) instead of the whole
  request failing (Partition tolerance);
* consistency is weakened only for the cross-instance overlap rule above.

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
* **Service discovery with hardcoded peer lists.** `PEERS`, `AIRCRAFT_SERVICE_URLS` and `AIRPORTS_ROUTES_SERVICE_URLS`.
* **Sharded data.** A flight is stored on the instance that created it. Reads that need everything ask every peer
  and merge the answers (scatter-gather, `FlightQueryService`).

### How to add an instance

1. Start the new instance with its own port and database, and with every existing instance in its `PEERS`.
2. Add the new instance to the `PEERS` of every existing instance (and to the URL lists of services that call it).
3. Restart the existing instances so they pick up the new peer list.

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
