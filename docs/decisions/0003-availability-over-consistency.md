# 0003. Favour availability during partitions (AP) and report booking conflicts

- Status: Accepted
- Date: 2026-10-02
- Requirement: P1 p.11, consistency model and trade-offs; Assignment 1, "documentation of the consistency model"

## Context

With two copies of every flight ([0002](0002-two-copies-of-every-flight.md)), a network partition between the
owner and the backup of an aircraft forces the CAP choice: either refuse bookings that cannot be confirmed by both
holders (consistency), or accept them and reconcile later (availability). Scheduling a flight is an operational
action; refusing it while the system is partially reachable has a direct cost.

## Decision

The service is **AP**. During failures it keeps answering and accepting bookings, and converges afterwards.

- Bookings of an aircraft go to its **first reachable holder**, which locks the aircraft (`aircraft_booking_lock`,
  `SELECT ... FOR UPDATE`) and checks for overlaps. While one holder takes the bookings, double-booking is
  **prevented**.
- If the owner certainly did not receive the booking (connection refused, circuit open), the backup takes it. If
  the owner was reached but did not answer in time, the outcome is unknown and the client gets `503` rather than a
  possible second booking.
- If both holders are running but cannot reach each other, both may accept an overlapping booking. When the copies
  meet, the overlap is **detected and reported** (`REPLICATION CONFLICT`, metric
  `flightops.replication{event=conflict}`); both flights are kept and an operator cancels one.

## Alternatives considered

- **CP with a quorum** (accept a booking only when a majority of the holders confirm, R + W > N with three copies).
  Always correct, but bookings of an aircraft stop while its holders are partitioned. Documented as the path to
  strict consistency; not implemented.
- **Last-writer-wins on conflict.** Silently discards a confirmed booking. Rejected: losing data is worse than
  reporting a conflict.

## Consequences

- Reads and bookings keep working with one instance down, and with a partition between holders.
- "No double-booking" is exact except during a partition between the two holders of the same aircraft; that case is
  detected, never silent.
- Clients may briefly read an older copy on an instance that did not make the change (eventual consistency).

## Evidence

`ConcurrentBookingTest` (20 rounds of simultaneous bookings, exactly one succeeds), `TwoInstancesIntegrationTest`
(simultaneous bookings through two instances), Postman folder **09**, phase **11c** of `scripts/test-failures.sh`.
Full analysis: [architecture.md, Consistency model](../architecture.md#consistency-model).
