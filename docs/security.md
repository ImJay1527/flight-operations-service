# Security

[Documentation guide](README.md) · [Running](running.md) · [Testing](testing.md) · [Operations](operations.md) · [Security](security.md) · [Limitations](limitations.md)

How Flight Operations protects its traffic, its data and its endpoints. The reasons for each choice and the
alternatives: [ADR 0007](decisions/0007-encryption-in-transit-and-at-rest.md).

| Requirement | How |
|---|---|
| Encryption in transit | `tls` profile: HTTPS, **TLS 1.3 only** (a TLS 1.2 client is refused), server and outgoing calls. Outgoing calls to peers and other services trust **only** the AISafe dev CA (`certs/truststore.p12`), and hostname verification stays on. The load balancer also speaks TLS 1.3 on both sides and verifies the instances' certificates. The connection to the message broker too: **AMQPS** (port 5671, TLS 1.3, the broker's certificate checked against the same CA and hostname). Certificates come from `scripts/generate-dev-certs.sh` (one CA, kept when it is run again; one certificate per service, the load balancer and the broker). |
| Encryption at rest | The route assignment of each flight (route, aircraft, model, airports) is stored **AES-256 encrypted** (`common.crypto`: deterministic authenticated encryption, SIV construction, so equality queries still work and tampering is detected). Key from `DATA_ENCRYPTION_KEY` (256 bit, base64), values versioned `enc:v1:` for key rotation; existing rows are encrypted at startup. `select aircraft_registration from scheduled_flight` shows only `enc:v1:...` |
| Inter-service authentication | Every service-to-service call carries a short-lived JWT (5 min, renewed automatically: rotating tokens, P1 p.16) with role `SERVICE` and the calling service as subject. `/internal/**` accepts only that role. |
| Access control | Role-based: user roles come from the login JWT and are checked per endpoint (`@PreAuthorize` on each controller method, `SecurityConfig` for the rest); see the table below. A service token can't call `/api/**`, and a user token can't call `/internal/**`. |
| Audit logging | `AUDIT` logger: user, roles, method, URI, status, duration and client address for every request. |
| CORS (PL2 p.9) | Browsers may call `/api/**` from the origins in `CORS_ALLOWED_ORIGINS` (default `http://localhost:3000,http://localhost:5173`); the custom `X-...` headers are exposed. Not for `/internal/**`. |

## Roles per endpoint

Users and their roles (sample data): `atcc` (ATCC), `operator` (BACKOFFICE_OPERATOR), `technician`
(MAINTENANCE_TECHNICIAN), `supervisor` (MAINTENANCE_SUPERVISOR), `admin` (all roles). Maintenance staff sign in
here but work in Aircraft & Maintenance: no Flight Operations endpoint accepts their roles. A request without a
token gets `401`; a token without the required role gets `403`.

| Endpoint | Method | Allowed roles | User story |
|---|---|---|---|
| `/api/auth/login` | POST | anyone | sign-in |
| `/api/scheduled-flights` | POST | ATCC | US212 assign an aircraft to a route |
| `/api/scheduled-flights/{flightNumber}`, `/api/scheduled-flights/aircraft/{registration}` | GET | ATCC | US213 flights of an aircraft |
| `/api/scheduled-flights/{flightNumber}/cancel`, `.../complete` | PATCH | ATCC | cancel; complete (Assignment 2) |
| `/api/scheduled-flights/departures/{iata}` | GET | ATCC, BACKOFFICE_OPERATOR | departures board |
| `/api/aircraft-utilization`, `/api/aircraft-utilization/{registration}` | GET | ATCC | US223 aircraft utilization |
| `/api/fuel-efficiency/aircraft/**`, `/api/fuel-efficiency/routes/**` | GET | ATCC | US227 fuel efficiency |
| `/api/v2/unicorns`, `/api/v2/unicorns/{registration}` | GET | ATCC, BACKOFFICE_OPERATOR, ADMIN | mock endpoint (P2 p.2) |
| `/api/cluster/health`, `/owner/{registration}`, `/metrics`, `/overview` | GET | ADMIN, ATCC, BACKOFFICE_OPERATOR | monitoring |
| `/internal/**` | all | SERVICE (service tokens only) | instance-to-instance |
| `/actuator/health/**` | GET | anyone | probes of Docker and Kubernetes |
| `/actuator/metrics/**`, `/actuator/shutdown` | | ADMIN | operations (shutdown only in stub mode) |
| `/swagger-ui.html`, `/v3/api-docs` | GET | anyone | API documentation |
| `/dashboard` | GET | anyone (the page only); its data comes from `/api/cluster/**` and needs a sign-in | monitoring |

Evidence: `SecurityIntegrationTest` (no token `401`; wrong role `403` for a backoffice operator and for maintenance
staff; user token on `/internal/**` and service token on `/api/**` `403`), Postman folder **05**.

## Not covered

What a real deployment would add, also listed in [limitations.md](limitations.md):

* **Events are not signed.** A message on the broker is trusted because the broker requires credentials and the
  connection is encrypted; the message itself carries no signature. Anyone holding the broker's credentials could
  publish a forged event (for example an `aircraft.status.changed`). Per-service broker users with publish rights
  limited to their own routing keys, or a signature on each message, would close this.
* **One broker user for all services.** The development default is RabbitMQ's `guest` / `guest` (Docker Compose);
  Kubernetes takes the user and password from a Secret, but it is still one user shared by every service.
