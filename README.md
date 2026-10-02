# flight-operations-service

AISafe Flight Management System – **Flight Operations** service (SIDIS 2026/27, Assignment 1).

Owns: scheduled flights, assigning aircraft to routes, availability checks, aircraft utilization (US223),
fuel efficiency (US227), departures board, and **user login** for the whole system.

Other services: [`aircraft-maintenance-service`](../aircraft-maintenance-service),
[`airports-routes-service`](../airports-routes-service). Contract between them: [docs/service-contracts.md](docs/service-contracts.md).

## Run

```bash
# one standalone instance, plain HTTP, H2 in memory (port 8083) - handy while developing
./mvnw spring-boot:run

# certificates for HTTPS (once; output in certs/, which is git-ignored)
./scripts/generate-dev-certs.sh

# 2 instances without Docker (8083, 8093); Ctrl+C stops both. Add --tls for HTTPS, -n 3 for a third instance.
./scripts/run-local.sh

# whole system, 2 instances per service (needs the 3 repos side by side)
docker compose up --build

# only the 2 flight-ops instances (enough to demo peer-to-peer queries)
docker compose up --build flightops-1 flightops-2

# scale-up: 3 flight-ops instances
docker compose -f docker-compose.yml -f docker-compose.scale-3.yml up --build flightops-1 flightops-2 flightops-3

# load test (k6 in Docker, 1 CPU per instance); results in loadtest/results/
./loadtest/run.sh 2
./loadtest/run.sh 3
```

**Instances = Spring profiles.** Each instance has its own file with its own port, database, peers and slice of the
sample data: `application-instance1.properties` (8083, `flightops_db_1`), `application-instance2.properties`
(8093, `flightops_db_2`) and `application-instance3.properties` (8103, scale-up). Start one with
`java -jar target/flight-operations-service-*.jar --spring.profiles.active=instance1` (add `,tls` for HTTPS).
Environment variables override the files, and docker-compose uses them for container hostnames.

**VS Code:** Run and Debug panel → "Flight Ops: 2 instances (HTTP)" or "(HTTPS)" starts both instances
(`.vscode/launch.json`).

Port scheme and scaling decisions: [docs/architecture.md](docs/architecture.md).

In docker-compose the flight-ops replicas serve **HTTPS only**: https://localhost:8083 and https://localhost:8093.
Swagger UI: https://localhost:8083/swagger-ui.html

To trust the dev CA: import `certs/ca.crt` into Postman (Settings → Certificates → CA certificates) or use
`curl --cacert certs/ca.crt` (on Windows also add `--ssl-no-revoke`, because a dev CA has no revocation list).

Login: `POST /api/auth/login` with `{"username":"atcc","password":"atcc123"}` (also `operator/operator123`, `admin/admin123`).

## How the distribution works

* **Sharding**: a flight is stored on the instance that created it. The 10 sample flights are split across the
  instances (`BOOTSTRAP_SHARD` of `SHARD_COUNT`).
* **Peer-to-peer reads**: a GET answers from the local DB and then asks every peer on `/internal/flights/**`.
  Results are merged and de-duplicated by `flightNumber`. Internal endpoints answer from the local shard only, so
  there are no loops.
  Try: `GET http://localhost:8083/api/aircraft-utilization` and `GET http://localhost:8093/api/aircraft-utilization`.
  Both return all 10 sample flights, although each replica only stores 5.
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
  * *Load balancing*: when looking for the instance that holds a flight, the peer asked first rotates per request.
  * Status: `GET /api/cluster/health` (roles ADMIN, ATCC, BACKOFFICE_OPERATOR) lists every peer / remote instance
    with its circuit state and failure count. Settings: `sidis.resilience.*` in `application.properties`.
* **Consistency**: eventual (AP in CAP). Overlap checks for the same aircraft use a DB lock on the local shard and a
  best-effort check on peers. Two simultaneous requests on different replicas could still double-book. That trade-off
  is documented, not a bug.

## Security

| Requirement | How |
|---|---|
| Encryption in transit | `tls` profile: HTTPS server (keystore `certs/flightops.p12`). Outgoing calls to peers and other services trust **only** the AISafe dev CA (`certs/truststore.p12`), and hostname verification stays on. Certificates come from `scripts/generate-dev-certs.sh` (one CA, one certificate per service). |
| Inter-service authentication | Every service-to-service call carries a JWT with role `SERVICE`. `/internal/**` accepts only that role. |
| Access control | User roles from the login JWT (`@PreAuthorize`). A service token can't call `/api/**`, and a user token can't call `/internal/**`. |
| Audit logging | `AUDIT` logger: user, roles, method, URI, status and client address for every request. |

## Testing (PL3 p.15-17)

### Automated tests (JUnit)

```bash
./mvnw test
```

| Test | What it covers |
|---|---|
| `services/*Test`, `resilience/*Test` | Unit tests: forwarding/merge logic, scheduling rules, retry/backoff, circuit breaker |
| `SecurityIntegrationTest` | One instance: login, 401/403 rules, service-only `/internal/**` |
| `TwoInstancesIntegrationTest` | **Two real instances** started on free ports, talking over HTTP: local access, forwarding (`X-Data-Source`), the same request id in both instances' logs, cancel forwarded to the owner, instance 2 stopped → 404 + circuit open |

### Postman (PL3 p.17)

Files in `postman/`:
`flight-operations.postman_collection.json` (31 requests with test scripts) and
`local.postman_environment.json` (one URL per instance). Import both in Postman.

1. Start 2 instances in **stub** mode (built-in aircraft/route data, so flights can be created without the other
   two services): `./scripts/run-local.sh --stub`, or in VS Code the compound "2 instances for the Postman tests".
2. Select the environment "Flight Ops - local (2 instances)".
3. Run the whole collection. Folder **03 Resilience** shows its tests as *skipped* while instance 2 is running.
4. Stop **instance 2**, then run folder **03 Resilience** on its own. Start instance 2 again afterwards.

| Folder | PL3 p.16 test |
|---|---|
| 01 Local Data Access | 01: data on instance 1, asked from instance 1 → 200, `X-Data-Source: local` (no peer query) |
| 02 Successful Forwarding | 02: data created on instance 2 only, asked from instance 1 → 200, `X-Data-Source: peer:…` |
| 03 Resilience | 03: instance 2 stopped → local data 200, remote-only data 404, instance 2 reported down |
| 04 Load Distribution | 04: requests alternate between instances, `X-Instance` shows who answered, response times checked |
| 05 Edge Cases | 05: invalid ids, malformed JSON, missing fields, business rules (409), 401/403 |

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
  then e.g. `select flight_number, status from scheduled_flight;`
* Without Docker: `./scripts/run-local.sh --h2` or the VS Code "H2 in-memory" configuration (data is lost on stop).
  The automated tests always use in-memory H2, so they need no setup.

## TODO

- [x] HTTPS for flight-ops replicas and their outgoing calls
- [ ] HTTPS on aircraft-maintenance-service and airports-routes-service (their owners), then switch the URLs in docker-compose to `https://`
- [x] PostgreSQL container per instance in docker-compose
- [x] Postman collection (postman/)
- [ ] Design document (consistency model, failure scenarios)
