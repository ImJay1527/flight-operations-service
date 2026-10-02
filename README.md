# flight-operations-service

AISafe Flight Management System – **Flight Operations** service (SIDIS 2026/27, Assignment 1).

Owns: scheduled flights, assigning aircraft to routes, availability checks, aircraft utilization (US223),
fuel efficiency (US227), departures board, and **user login** for the whole system.

Other services: [`aircraft-maintenance-service`](../aircraft-maintenance-service),
[`airports-routes-service`](../airports-routes-service). Contract between them: [docs/service-contracts.md](docs/service-contracts.md).

![System overview](docs/diagrams/1-system-overview.png)

Architecture diagrams, key benefits, performance measurements and scaling decisions: [docs/architecture.md](docs/architecture.md).

## Run

```bash
# one standalone instance, plain HTTP, H2 in memory (port 8083) - handy while developing
./mvnw spring-boot:run

# certificates for HTTPS (once; output in certs/, which is git-ignored)
./scripts/generate-dev-certs.sh

# 2 instances without Docker (8083, 8093); Ctrl+C stops both. Add --tls for HTTPS, -n 3 for a third instance.
# Prints "READY FOR TESTS" once every instance has fully started.
./scripts/run-local.sh

# instances started another way (e.g. VS Code): wait until they are ready (-n 3, --tls as above)
./scripts/wait-ready.sh

# whole system, 2 instances per service (needs the 3 repos side by side)
docker compose up --build

# only flight ops: 2 instances + their databases behind the load balancer (https://localhost:8443)
docker compose up --build flightops-lb

# scale-up: 3 flight-ops instances
docker compose -f docker-compose.yml -f docker-compose.scale-3.yml up --build flightops-1 flightops-2 flightops-3

# load test (k6 in Docker, 1 CPU per instance); results in loadtest/results/
./loadtest/run.sh 2
./loadtest/run.sh 3

# local vs forwarded latency, availability while an instance is killed (with and without the load balancer)
./loadtest/performance.sh latency
./loadtest/performance.sh availability
./loadtest/performance.sh availability-lb
```

**Instances = Spring profiles.** Each instance has its own file with its own port, database and the list of all
instances (`flightops.cluster`): `application-instance1.properties` (8083, `flightops_db_1`), `application-instance2.properties`
(8093, `flightops_db_2`) and `application-instance3.properties` (8103, scale-up). Start one with
`java -jar target/flight-operations-service-*.jar --spring.profiles.active=instance1` (add `,tls` for HTTPS).
Environment variables override the files, and docker-compose uses them for container hostnames.

**VS Code:** Run and Debug panel → "Flight Ops: 2 instances (HTTP)" or "(HTTPS)" starts both instances
(`.vscode/launch.json`).

Port scheme and scaling decisions: [docs/architecture.md](docs/architecture.md).

In docker-compose clients use the **load balancer**, https://localhost:8443 (nginx, `lb/nginx.conf`: least
connections, an instance that fails is left out for 10 s, failover to the other instance). The instances also stay
reachable directly on https://localhost:8083 and https://localhost:8093, **HTTPS only (TLS 1.3)**. Docker checks
each instance's health every 10 s (`docker compose ps`) and restarts an instance whose process stops.
Swagger UI: https://localhost:8083/swagger-ui.html

To trust the dev CA: import `certs/ca.crt` into Postman (Settings → Certificates → CA certificates) or use
`curl --cacert certs/ca.crt` (on Windows also add `--ssl-no-revoke`, because a dev CA has no revocation list).

Login: `POST /api/auth/login` with `{"username":"atcc","password":"atcc123"}` (also `operator/operator123`, `admin/admin123`).

## How the distribution works

* **Sharding by aircraft** (P1 p.15): every instance knows all instances (`flightops.cluster`); the owner of an
  aircraft is chosen by rendezvous hashing of its registration. A booking is stored on the owner, forwarded there if
  it arrived elsewhere (`X-Stored-On` says where); `GET /api/cluster/owner/{registration}` shows the owner.
  The 10 sample flights are loaded by the owners of their aircraft.
* **Peer-to-peer reads**: a GET answers from the local DB and then asks the peers on `/internal/flights/**`:
  lists ask **all peers at the same time** (P1 p.12), a single flight asks them one by one until found (PL3 p.11).
  Results are merged and de-duplicated by `flightNumber`. Internal endpoints answer from the local shard only, so
  there are no loops. A list built while a peer was unreachable is still `200`, with `X-Partial-Result: true` and
  `X-Unreachable-Peers: n`.
  Try: `GET http://localhost:8083/api/aircraft-utilization` and `GET http://localhost:8093/api/aircraft-utilization`.
  Both return all 10 sample flights, although each instance only stores those of its own aircraft.
* **Fault tolerance**: an unreachable peer is skipped (logged as a warning). If a single-item lookup can't be found
  *and* a peer was unreachable, the answer is still `404`, as the practical session expects (PL3 p.11, p.16 test 03),
  but the error message says how many peers could not be reached, because the item may exist there.
* **Calls to other services** (`AircraftClient`, `AirportsRoutesClient`) go round-robin over their instances and
  fail over to the next one on timeout or 5xx. If none can be reached the answer is `404`, with
  "(… could not be reached)" in the message.
* **Resilience** (PL3 p.12, p.14; package `resilience`):
  * *Retry with exponential backoff*: a failed GET to another instance is retried up to 2 more times, after
    ~100 ms and ~200 ms (plus random jitter). A PATCH is never retried, because the first attempt may have worked.
  * *Circuit breaker*: after 3 failures in a row an instance is skipped for 10 s (fail fast; a struggling instance
    isn't flooded). Then one trial request is let through: success closes the circuit, failure skips it for twice
    as long (max 60 s).
  * *Health checks*: every 5 s each instance calls `/actuator/health` on its peers and on the other services'
    instances. A recovered instance is used again as soon as it answers.
  * *Load balancing*: incoming requests through nginx (least connections); between instances, the peer asked first
    for a single flight rotates per request; calls to other services go round-robin.
  * Status: `GET /api/cluster/health` (roles ADMIN, ATCC, BACKOFFICE_OPERATOR) lists every peer / remote instance
    with its circuit state and failure count. Settings: `sidis.resilience.*` in `application.properties`.
* **Consistency**: every flight has exactly one owner (no copies), so single reads are up to date; lists are partial
  while an instance is down; "no double-booking" is guaranteed while the aircraft's owner is reachable (all its
  bookings meet there, under a per-aircraft lock) and best effort while it is down (AP in CAP).
  Details, the known weak spots and how to fix them: [docs/architecture.md#consistency-model](docs/architecture.md#consistency-model).

## Security

| Requirement | How |
|---|---|
| Encryption in transit | `tls` profile: HTTPS, **TLS 1.3 only** (a TLS 1.2 client is refused), server and outgoing calls. Outgoing calls to peers and other services trust **only** the AISafe dev CA (`certs/truststore.p12`), and hostname verification stays on. The load balancer also speaks TLS 1.3 on both sides and verifies the instances' certificates. Certificates come from `scripts/generate-dev-certs.sh` (one CA, one certificate per service + the load balancer). |
| Encryption at rest | The route assignment of each flight (route, aircraft, model, airports) is stored **AES-256 encrypted** (`common.crypto`: deterministic authenticated encryption, SIV construction, so equality queries still work and tampering is detected). Key from `DATA_ENCRYPTION_KEY` (256 bit, base64), values versioned `enc:v1:` for key rotation; existing rows are encrypted at startup. `select aircraft_registration from scheduled_flight` shows only `enc:v1:...` |
| Inter-service authentication | Every service-to-service call carries a short-lived JWT (5 min, renewed automatically: rotating tokens, P1 p.16) with role `SERVICE` and the calling service as subject. `/internal/**` accepts only that role. |
| Access control | User roles from the login JWT (`@PreAuthorize`). A service token can't call `/api/**`, and a user token can't call `/internal/**`. |
| Audit logging | `AUDIT` logger: user, roles, method, URI, status, duration and client address for every request. |
| CORS (PL2 p.9) | Browsers may call `/api/**` from the origins in `CORS_ALLOWED_ORIGINS` (default `http://localhost:3000,http://localhost:5173`); the custom `X-...` headers are exposed. Not for `/internal/**`. |

## Testing (PL3 p.15-17)

### Automated tests (JUnit)

```bash
./mvnw test
```

| Test | What it covers |
|---|---|
| `services/*Test`, `resilience/*Test`, `cluster/ClusterTest`, `common/crypto/*Test` | Unit tests: forwarding/merge logic, scheduling rules, routing a booking to the aircraft's owner (and the fallbacks), retry/backoff, circuit breaker, rendezvous hashing (agreement, spread, minimal movement), encryption |
| `ConcurrentBookingTest`, `EncryptionAtRestTest` | Real database: simultaneous bookings of one aircraft (exactly one succeeds), what is really stored (raw SQL shows only ciphertext) |
| `SecurityIntegrationTest` | One instance: login, 401/403 rules, service-only `/internal/**` |
| `TwoInstancesIntegrationTest` | **Two real instances** started on free ports, talking over HTTP: local access, forwarding (`X-Data-Source`), the same request id in both instances' logs, cancel forwarded to the owner, a booking stored on the aircraft's owner, simultaneous bookings via both instances (one wins), instance 2 stopped → 404, partial lists flagged, circuit open |

### Postman (PL3 p.17)

Files in `postman/`:
`flight-operations.postman_collection.json` (48 requests with test scripts) and two environments (one URL per
instance): `local.postman_environment.json` (HTTP) and `local-https.postman_environment.json` (HTTPS). Import all three.

1. Start 2 instances in **stub** mode (built-in aircraft/route data, so flights can be created without the other
   two services): `./scripts/run-local.sh --stub`, or in VS Code the compound "2 instances for the Postman tests".
   Wait for **READY FOR TESTS** (from VS Code: run `./scripts/wait-ready.sh` in a terminal).
2. Select the environment "Flight Ops - local (2 instances)".
3. Run the whole collection (about 30 s). Nothing to do by hand: folder **03 Resilience** stops instance 2 itself
   (`POST /actuator/shutdown`, only possible in stub/test mode and only for ADMIN), checks the failure behaviour, then
   waits until `run-local.sh` has restarted instance 2 and checks the automatic recovery.
   With the VS Code compound there is no automatic restart: the last request of folder 03 is then skipped.

| Folder | PL3 p.16 test |
|---|---|
| 01 Local Data Access | 01: data on instance 1, asked from instance 1 → 200, `X-Data-Source: local` (no peer query) |
| 02 Successful Forwarding | 02: data created on instance 2 only, asked from instance 1 → 200, `X-Data-Source: peer:…` |
| 03 Resilience | 03: instance 2 stopped → local data 200, remote-only data 404, lists flagged `X-Partial-Result`, instance 2 reported down; then instance 2 restarted → trusted again, forwarding works, its data survived (PL3 p.14 automatic recovery) |
| 04 Load Distribution | 04: requests alternate between instances, `X-Instance` shows who answered, response times checked |
| 05 Edge Cases | 05: invalid ids, malformed JSON, missing fields, business rules (409), 401/403 |
| 06 Monitoring | PL3 p.19 metrics: local vs forwarded times (forwarded slower), forwarding success rate, peer health, both instances served requests |
| 07 Three instances | PL3 p.27 "test with 3+ instances": data created on instance 3 reachable from 1 and 2, every instance sees 2 healthy peers. Skipped unless 3 instances run: `./scripts/run-local.sh -n 3 --stub` |
| 08 Encryption in transit | HTTPS only: works with a verified certificate, plain HTTP to the same port is refused, peers are called over HTTPS. Only with the HTTPS environment |
| 09 Sharding by aircraft | P1 p.15: owner lookup, a booking sent to instance 1 stored on its owner instance 2 (`X-Stored-On`), simultaneous bookings of the same aircraft via both instances → one 201, one 409 |

**Over HTTPS** (evidence of encryption in transit): start the instances with `./scripts/run-local.sh --tls --stub`
and select "Flight Ops - local HTTPS (2 instances)". Postman must trust the dev CA: Settings → Certificates →
CA certificates → on → select `certs/ca.crt` (created by `./scripts/generate-dev-certs.sh`). If that setting isn't
available (Postman on the web), turn off Settings → General → "SSL certificate verification" instead: the traffic is
still encrypted, only the server's identity isn't checked. Command line:
`npx newman run postman/flight-operations.postman_collection.json -e postman/local-https.postman_environment.json --ssl-extra-ca-certs certs/ca.crt`.

From the command line (same files): `npx newman run postman/flight-operations.postman_collection.json -e postman/local.postman_environment.json --folder "01 Local Data Access"`.

Postman **on the web** reaches `localhost` only through the
[Postman Desktop Agent](https://www.postman.com/downloads/postman-agent/) (or use the Postman desktop app).

### Tracing a request across instances (PL3 p.15)

Every response has `X-Instance` (which instance answered) and `X-Request-Id`. The request id is passed on to peers,
and every log line shows `[instance] [request id]`, so one request can be followed through all instances:

```
INFO [instance1] [postman-forwarding-1] AUDIT : user=atcc ... uri=/api/scheduled-flights/2db4... status=200 durationMs=31
INFO [instance2] [postman-forwarding-1] AUDIT : user=flight-operations-service roles=[ROLE_SERVICE] ... uri=/internal/flights/2db4...
```

Send your own id with the `X-Request-Id` header to find a request easily (`grep postman-forwarding-1 logs/*.log`).

## Monitoring (PL3 p.19)

**Log format** (as on the slide, plus the request id), for every line of every instance:

```
15:30:16.431 [http-nio-8083-exec-3] INFO  [instance1] [16ad3a5d] AUDIT - user=admin ... uri=/api/cluster/metrics status=200 durationMs=12
```

The `AUDIT` line is the slide's "request tracer": method, URL, status and processing time of every request.
`DEBUG` lines of `PeerClient` show each forwarding step (enabled in the instance profiles).

**Metrics**: `GET /api/cluster/metrics` (roles ADMIN, ATCC, BACKOFFICE_OPERATOR) answers the slide's four points for
this instance, counted since it started:

| Slide | Field | Example |
|---|---|---|
| Response times: local vs forwarded | `lookupResponseTimes.local / forwarded / notFound` (count, avg, p95, max ms) | local 17 ms, forwarded 65 ms |
| Success rates: forwarding success % | `forwarding.successRatePercent` (found on a peer / lookups that had to ask peers) | 50 % |
| Peer health: availability, failure rates | `remoteInstances[]`: `healthy`, `circuit`, `callsByOutcome`, `failureRatePercent`, `avgCallMs` | instance 2: healthy, 9 % failures |
| Load distribution | `requestsServed.total` / `byStatus`: compare the instances | 34 requests on instance 1 |

Recorded with Micrometer (`monitoring.FlightOpsMetrics`): timers `flightops.lookups` (tag `source`) and
`flightops.remote.calls` (tags `group`, `target`, `outcome`), gauge `flightops.remote.healthy`, plus Spring Boot's
`http.server.requests`. Raw values: `GET /actuator/metrics/flightops.lookups?tag=source:forwarded` (ADMIN).
The Postman folder "06 Monitoring" checks all of it.

## Database

Each instance has **its own PostgreSQL database**, running in its own container, so the data of one instance is
isolated from the others and **survives restarts**:

| Instance | Database container | Host port | Stored in (Docker volume) |
|---|---|---|---|
| 1 | `flightops-db-1` | 5433 | `flightops-db-1-data` |
| 2 | `flightops-db-2` | 5434 | `flightops-db-2-data` |
| 3 (scale-up) | `flightops-db-3` | 5435 | `flightops-db-3-data` |

* `docker compose up`, `./scripts/run-local.sh` and the VS Code "PostgreSQL" configurations start the database
  containers automatically. Docker Desktop must be running.
* The sample data is loaded only into an **empty** database, so restarts don't duplicate it.
* Stop the databases: `docker compose stop`. **Delete all data** (start fresh): `docker compose down -v`.
* Look inside a database: `docker compose exec flightops-db-1 psql -U flightops -d flightops`,
  then e.g. `select flight_number, aircraft_registration, status from scheduled_flight;` (the registration is
  encrypted: `enc:v1:...`, see Security)
* Without Docker: `./scripts/run-local.sh --h2` or the VS Code "H2 in-memory" configuration (data is lost on stop).
  The automated tests always use in-memory H2, so they need no setup.

## TODO

- [x] HTTPS for flight-ops replicas and their outgoing calls
- [ ] HTTPS on aircraft-maintenance-service and airports-routes-service (their owners), then switch the URLs in docker-compose to `https://`
- [x] PostgreSQL container per instance in docker-compose
- [x] Postman collection (postman/)
- [x] Architecture diagrams and performance analysis (docs/architecture.md)
- [x] Consistency model, failure scenarios (docs/architecture.md)
