# Operations: monitoring and data stores

[Documentation guide](README.md) · [Running](running.md) · [Testing](testing.md) · [Operations](operations.md) · [Security](security.md) · [Limitations](limitations.md)

What an operator looks at while the system runs (dashboard, logs, metrics) and where each instance keeps its
data (write database, read database, message broker).

## Monitoring (PL3 p.19)

**Dashboard:** open `/dashboard` on any instance or on the load balancer (https://localhost:9443/dashboard on
Kubernetes, https://localhost:8443/dashboard with docker-compose) and sign in (e.g. `admin`). It refreshes every 2 s
and shows every instance (health, CPU, memory, requests per second, flights as owner / backup, copies waiting, its
view of the peers), where each aircraft's flights are stored, the instances that joined or left (Kubernetes) and the
other services' health. Its data is `GET /api/cluster/overview`: the instance that answers adds its own status to
its peers' (`/internal/cluster/status`, asked in parallel), so a stopped instance shows as "not answering". The page
loads nothing from the internet.

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

Recorded with Micrometer (`common.metrics.FlightOpsMetrics`): timers `flightops.lookups` (tag `source`) and
`flightops.remote.calls` (tags `group`, `target`, `outcome`), gauge `flightops.remote.healthy`, plus Spring Boot's
`http.server.requests`. Raw values: `GET /actuator/metrics/flightops.lookups?tag=source:forwarded` (ADMIN).
The Postman folder "06 Monitoring" checks all of it.

## Data stores

### Write databases (PostgreSQL)

Each instance has **its own PostgreSQL database**, running in its own container, so the data of one instance is
isolated from the others and **survives restarts**:

| Instance | Database container | Host port | Stored in (Docker volume) |
|---|---|---|---|
| 1 | `flightops-db-1` | 5433 | `flightops-db-1-data` |
| 2 | `flightops-db-2` | 5434 | `flightops-db-2-data` |
| 3 | `flightops-db-3` | 5435 | `flightops-db-3-data` |

* `docker compose up`, `./scripts/run-local.sh` and the VS Code "PostgreSQL" configurations start the database
  containers automatically. Docker Desktop must be running.
* The sample data is loaded only into an **empty** database, so restarts don't duplicate it.
* Stop the databases: `docker compose stop`. **Delete all data** (start fresh): `docker compose down -v`.
* Look inside a database: `docker compose exec flightops-db-1 psql -U flightops -d flightops`,
  then e.g. `select flight_number, aircraft_registration, status from scheduled_flight;` (the registration is
  encrypted: `enc:v1:...`, see Security)
* Without Docker: `./scripts/run-local.sh --h2` or the VS Code "H2 in-memory" configuration (data is lost on stop,
  and there is no read database: queries take the CQRS fallback path).
  The automated tests always use in-memory H2, so they need no setup.

### Read databases (CQRS)

Each instance also has **its own MongoDB**, the read model of CQRS (see [CQRS](architecture.md#cqrs-assignment-2)):

| Instance | Read database container | Host port | Stored in (Docker volume) |
|---|---|---|---|
| 1 | `flightops-read-db-1` | 27018 | `flightops-read-db-1-data` |
| 2 | `flightops-read-db-2` | 27019 | `flightops-read-db-2-data` |
| 3 | `flightops-read-db-3` | 27020 | `flightops-read-db-3-data` |

* Started with the PostgreSQL containers (`docker compose up`, `./scripts/run-local.sh`). It can always be rebuilt
  from the write side: `docker compose down -v` (or deleting one volume) loses nothing the write databases don't have.
* Look inside: `docker compose exec flightops-read-db-1 mongosh -u flightops -p flightops --authenticationDatabase admin flightops_read`,
  then e.g. `db.flights.countDocuments()`, `db.aircraft_stats.findOne()` (registration, model and airports are
  encrypted, as in PostgreSQL).

### Message broker

One RabbitMQ for the whole system (see [Messaging](architecture.md#messaging-assignment-2)): container `rabbitmq`, AMQPS (TLS 1.3)
on 5671 when the dev certificates exist (`rabbitmq/tls.conf`), plain AMQP on 5672 for the services without TLS,
management UI on http://localhost:15672 (`guest` / `guest`; `RABBITMQ_USER` / `RABBITMQ_PASSWORD` override them),
data in the Docker volume `rabbitmq-data`. Started with the databases (`docker compose up`, `./scripts/run-local.sh`).
Each instance's events wait in its table `event_outbox` until the broker confirms them. With the `tls` profile the
instances connect on 5671 (AMQPS); without it (`run-local.sh` without `--tls`) on 5672. RabbitMQ's UI shows it per
connection (Connections → TLS); Postman folder 13 checks it.
