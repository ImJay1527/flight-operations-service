# 0001. Shard flights by aircraft registration with rendezvous hashing

- Status: Accepted
- Date: 2026-10-02
- Requirement: P1 p.15, "data-based sharding: partition by operational identifiers, e.g. aircraft registration
  numbers"; PL3 p.24 (instances do not keep identical copies of all data)

## Context

Flight Operations runs as several instances, each with its own database ([0005](0005-database-per-instance.md)).
Every flight must be stored somewhere specific, and every instance must be able to tell where without asking a
coordinator. The most important invariant of the domain, "an aircraft is never in two flights at the same time",
is checked per aircraft: the overlap check reads every active flight of that aircraft.

## Decision

Flights are partitioned by the **registration of their aircraft**. Each instance ranks all instances for a given
registration by **rendezvous (highest random weight) hashing**: the score of an instance is the SHA-256 of
`instance name + registration`, and the highest score wins. The first instance of the ranking is the aircraft's
**owner**: every booking of that aircraft is made there, and forwarded there when it arrives at another instance
(`X-Stored-On` reports where it was stored). The second instance is its backup ([0002](0002-two-copies-of-every-flight.md)).

## Alternatives considered

- **Shard by flight number.** Spreads load evenly, but the flights of one aircraft end up on every instance, so the
  overlap check becomes a distributed read on every booking.
- **Modulo hashing (`hash(reg) % N`).** Simple, but changing the number of instances moves almost every aircraft,
  which defeats auto-scaling ([0008](0008-kubernetes-statefulset-and-discovery.md)).
- **Consistent hashing on a ring.** Moves few keys, but needs virtual nodes to spread evenly and a shared ring
  description. Rendezvous hashing gives the same minimal movement with no shared state: a list of names is enough.
- **A directory service that maps aircraft to instances.** A new component, and a single point of failure.

## Consequences

- Every instance computes the same ranking independently; no coordination, no extra network call.
- Adding an instance moves only the aircraft the new instance wins; removing one moves only its aircraft.
- All bookings of an aircraft are serialised on one instance, so the overlap check and the per-aircraft lock are
  local ([0003](0003-availability-over-consistency.md)).
- A lookup by flight number does not reveal the aircraft, so it may have to ask several instances (forwarding,
  PL3 p.11). Since Assignment 2 this is answered by the read model instead ([0009](0009-cqrs-separate-read-database.md)).
- Instance names must be stable, which shapes the Kubernetes deployment ([0008](0008-kubernetes-statefulset-and-discovery.md)).

## Evidence

`ClusterTest` (agreement, spread, minimal movement), Postman folder **09 Sharding by aircraft**,
`GET /api/cluster/owner/{registration}`, the dashboard's aircraft placement panel.
