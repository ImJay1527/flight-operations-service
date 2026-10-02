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

| Endpoint | Does (local shard only, never forwards) |
|---|---|
| `GET /internal/flights/{flightNumber}` | one flight or 404 |
| `GET /internal/flights?aircraft=REG` | flights of an aircraft |
| `GET /internal/flights/active[?aircraft=REG]` | non-cancelled flights |
| `GET /internal/flights/departures/{iata}?hours=N` | upcoming departures |
| `PATCH /internal/flights/{flightNumber}/cancel` | cancelled flight, 404 if not here, 409 if not cancellable |
| `POST /internal/flights` `{routeId, aircraftRegistration, departureTime, arrivalTime}` | books the flight on THIS instance (the aircraft's owner - sharding by registration); 201 + flight, or the same 4xx a normal booking gets |

## Shared bootstrap data

So the sample data lines up across services, use these fixed identifiers:

| Kind | IDs |
|---|---|
| Routes | `route-opo-lis` (OPO→LIS, 277 km, min range 350, min capacity 100), `route-lis-mad` (LIS→MAD, 502 km, 600, 150), `route-mad-opo` (MAD→OPO, 420 km, 500, 120) |
| Airports | `OPO`, `LIS`, `MAD` (operational, certified for at least `A320neo` and `737 MAX`) |
| Aircraft | `CS-TPA` (A320neo), `CS-TPB` (737 MAX), `CS-TPC` (A320neo), `CS-TPD` (777X), `CS-TPE` (A350) |
| Models | same specs as the PSOFT bootstrap (e.g. A320neo: fuel 24000, range 6300; 737 MAX: fuel 26000, range 6500) |
