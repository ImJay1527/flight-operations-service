# 0014. Keep local copies of the other services' data, fed by their events

- Status: Accepted
- Date: 2026-11-21 (pull requests #28 and #29)
- Requirement: P2 p.2, asynchronous communication between services; PL3 p.20, fault isolation

## Context

A booking needs the aircraft (status, model, range, fuel, seats), the route (status, distance, minimum range and
capacity) and its airports (status, certified models). In Assignment 1 every booking asked Aircraft & Maintenance
and Airports & Routes over REST, so a booking failed whenever either service was down, and every booking paid two
remote calls.

## Decision

Each instance keeps **its own copy** of the data a booking needs, in its write database (`aircraft_copy`,
`route_copy`, `airport_copy`), maintained from the other services' events:

| Event | Effect on the copy |
|---|---|
| `aircraft.created` | the aircraft is stored |
| `aircraft.status.changed` | its status (`UNDER_MAINTENANCE` blocks bookings at once) |
| `airport.created`, `airport.status.changed`, `airport.certification.added` | the airport, its status, its certified models |
| `route.created`, `route.updated`, `route.deactivated` | the route, if the event carries its requirements; its status |

`clients.LocalAircraftCopy` and `clients.LocalRouteCopy` answer bookings from the copies. **REST remains the
fallback:** what the copy does not have is read once and kept, a copy unchanged for an hour is read again, and if
the other service is down the existing copy is used.

## Alternatives considered

- **A REST call per booking (Assignment 1).** Always current, but couples the availability of bookings to the
  availability of two other services.
- **Reading the other services' databases.** Violates database-per-service ([0005](0005-database-per-instance.md)).
- **Copies fed by events only, without REST.** An event missed before the instance first listened (or a queue
  removed after a long stop) would leave a gap with no way to fill it.

## Consequences

- Bookings work while Aircraft & Maintenance or Airports & Routes are down, and need no remote call when the copy
  is present.
- A copy can be a few milliseconds behind its source (longer while the broker is down); the owning service remains
  the source of truth.
- Only an aircraft or a route never seen before cannot be booked while its service is down (`503`).
- The copies are reference data with no flight in them, so they are not encrypted at rest.

## Evidence

`LocalAircraftCopyTest`, `LocalRouteCopyTest`, `AircraftEventsTest`, `RouteEventsTest`, `MessagingIntegrationTest`,
Postman folders **14** and **15**, phase **11e** of `scripts/test-failures.sh`; `aircraftLookups` and
`routeLookups` in `GET /api/cluster/metrics` (`copy`, `rest`, `refreshed`, `old_copy`).
