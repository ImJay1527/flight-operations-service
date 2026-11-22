# 0005. One database per instance, no shared storage

- Status: Accepted
- Date: 2026-10-02
- Requirement: P1 and P2 p.2, "database per service instance" and "data isolation across instances"

## Context

The monolith (PSOFT) had one database. A distributed service whose instances share a database is distributed only
in its compute: the database remains a single point of failure, a scaling bottleneck and a coupling point.

## Decision

Each instance has its **own PostgreSQL 16** database in its own container (Kubernetes: in its own pod, with its own
persistent volume). Since Assignment 2 it also has its **own MongoDB** read database
([0009](0009-cqrs-separate-read-database.md)). No instance reads another instance's database, and no service reads
another service's database: data crosses those boundaries only through the REST API or events.

The sample data is loaded only into an empty database. H2 in memory is used only for the unit tests and for
running without Docker (`--h2`).

## Alternatives considered

- **One shared PostgreSQL.** Simpler transactions, but a single point of failure and a hidden channel between
  instances; contradicts the statement.
- **One server with a schema per instance.** Isolates the data logically, but not the failure: the server is still
  shared.
- **Embedded H2 per instance in production.** Isolated, but loses the data on restart and lacks the row locks the
  booking needs (`SELECT ... FOR UPDATE`).

## Consequences

- An instance's failure, restart or data loss does not affect another instance's storage; replication and
  anti-entropy restore it ([0002](0002-two-copies-of-every-flight.md)).
- No cross-instance transactions: consistency between instances is eventual by construction
  ([0003](0003-availability-over-consistency.md)).
- More containers to run: three PostgreSQL and three MongoDB for three instances.

## Evidence

`docker-compose.yml` (`flightops-db-<i>`, `flightops-read-db-<i>`, one volume each), `k8s/flightops.yaml` (both
databases in each pod, one persistent volume claim each), [operations.md, Data stores](../operations.md#data-stores).
