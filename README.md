# flight-operations-service

AISafe Flight Management System – **Flight Operations** service (SIDIS 2026/27, Assignment 1).

Owns: scheduled flights, assigning aircraft to routes, availability checks, aircraft utilization (US223),
fuel efficiency (US227), departures board, and **user login** for the whole system.

Other services: [`aircraft-maintenance-service`](../aircraft-maintenance-service),
[`airports-routes-service`](../airports-routes-service). Contract between them: [docs/service-contracts.md](docs/service-contracts.md).

## Run

```bash
# one standalone replica, plain HTTP, H2 in memory (port 8083) - handy while developing
./mvnw spring-boot:run

# certificates for HTTPS (once; output in certs/, which is git-ignored)
./scripts/generate-dev-certs.sh

# whole system, 2 replicas per service (needs the 3 repos side by side)
docker compose up --build

# only the 2 flight-ops replicas (enough to demo peer-to-peer queries)
docker compose up --build flightops-1 flightops-2
```

In docker-compose the flight-ops replicas serve **HTTPS only**: https://localhost:8083 and https://localhost:8093.
Swagger UI: https://localhost:8083/swagger-ui.html

To trust the dev CA: import `certs/ca.crt` into Postman (Settings → Certificates → CA certificates) or use
`curl --cacert certs/ca.crt` (on Windows also add `--ssl-no-revoke`, because a dev CA has no revocation list).

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

## Security

| Requirement | How |
|---|---|
| Encryption in transit | `tls` profile: HTTPS server (keystore `certs/flightops.p12`). Outgoing calls to peers and other services trust **only** the AISafe dev CA (`certs/truststore.p12`), and hostname verification stays on. Certificates come from `scripts/generate-dev-certs.sh` (one CA, one certificate per service). |
| Inter-service authentication | Every service-to-service call carries a JWT with role `SERVICE`. `/internal/**` accepts only that role. |
| Access control | User roles from the login JWT (`@PreAuthorize`). A service token can't call `/api/**`, and a user token can't call `/internal/**`. |
| Audit logging | `AUDIT` logger: user, roles, method, URI, status and client address for every request. |

## Database

H2 in memory by default. For a real DB, set `SPRING_PROFILES_ACTIVE=postgres` and `DB_URL`/`DB_USER`/`DB_PASSWORD`
(any PostgreSQL works, e.g. a container or Supabase). Each replica needs its **own** database or schema.

## TODO

- [x] HTTPS for flight-ops replicas and their outgoing calls
- [ ] HTTPS on aircraft-maintenance-service and airports-routes-service (their owners), then switch the URLs in docker-compose to `https://`
- [ ] Postgres containers in docker-compose (one per replica)
- [ ] Postman collection for the demo
- [ ] Design document (consistency model, failure scenarios)
