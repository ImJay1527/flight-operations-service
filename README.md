# flight-operations-service

AISafe Flight Management System – **Flight Operations** service (SIDIS 2026/27, Assignment 1).

Owns: scheduled flights, assigning aircraft to routes, availability checks, aircraft utilization (US223),
fuel efficiency (US227), departures board, and **user login** for the whole system.

Other services: [`aircraft-maintenance-service`](../aircraft-maintenance-service),
[`airports-routes-service`](../airports-routes-service). Contract between them: [docs/service-contracts.md](docs/service-contracts.md).

## Run

```bash
# one standalone replica, H2 in memory (port 8083)
./mvnw spring-boot:run

# whole system, 2 replicas per service (needs the 3 repos side by side)
docker compose up --build

# only the 2 flight-ops replicas (enough to demo peer-to-peer queries)
docker compose up --build flightops-1 flightops-2
```

Swagger UI: http://localhost:8083/swagger-ui.html

Login: `POST /api/auth/login` with `{"username":"atcc","password":"atcc123"}` (also `operator/operator123`, `admin/admin123`).

## How the distribution works

* **Sharding**: a flight is stored on the replica that created it. In the compose file `flightops-1` loads sample
  shard 1 and `flightops-2` shard 2.
* **Peer-to-peer reads**: a GET answers from the local DB and then asks every peer on `/internal/flights/**`.
  Results are merged and de-duplicated by `flightNumber`. Internal endpoints answer from the local shard only, so
  there are no loops.
  Try: `GET http://localhost:8083/api/aircraft-utilization` and `GET http://localhost:8093/api/aircraft-utilization`.
  Both return all 10 sample flights, although each replica only stores 5.
* **Fault tolerance**: an unreachable peer is skipped (logged as a warning). If a single-item lookup can't be found
  *and* a peer was unreachable, the answer is `503` (it might exist there), not a false `404`.
* **Calls to other services** (`AircraftClient`, `AirportsRoutesClient`) go round-robin over all replicas
  and fail over to the next replica on timeout or 5xx.
* **Consistency**: eventual (AP in CAP). Overlap checks for the same aircraft use a DB lock on the local shard and a
  best-effort check on peers. Two simultaneous requests on different replicas could still double-book. That trade-off
  is documented, not a bug.

## Database

H2 in memory by default. For a real DB, set `SPRING_PROFILES_ACTIVE=postgres` and `DB_URL`/`DB_USER`/`DB_PASSWORD`
(any PostgreSQL works, e.g. a container or Supabase). Each replica needs its **own** database or schema.

## TODO

- [ ] HTTPS between services (TLS keystore + `server.ssl.*`) – encryption in transit
- [ ] Postgres containers in docker-compose (one per replica)
- [ ] Postman collection for the demo
- [ ] Design document (consistency model, failure scenarios)
