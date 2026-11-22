# 0015. Optional query instances: CQRS as separate processes

- Status: Accepted
- Date: 2026-11-21 (pull request #31)
- Requirement: beyond the statement; P2 p.2 CQRS, taken one step further (independent scaling of reads and writes)

## Context

With [0009](0009-cqrs-separate-read-database.md), every instance does both jobs: it takes commands for its aircraft
and answers every query from its own read model. Reads and writes are separated in code and storage, but they still
scale and fail together, in the same process. In this domain reads (departures, reports, lookups) are expected to
outnumber writes (bookings).

## Decision

A **query instance** (Spring profile `query`) is the same image with a different role:

- it has a **read database only**; an in-memory H2 holds just the login users and the reference copies, never a
  flight;
- it is **not a cluster member**: it does not appear in the rendezvous ranking, holds no shard and receives no
  replicas;
- it **refuses commands** with `405 Method Not Allowed` and `Allow: GET, HEAD`;
- its read model is fed by the broker (its own queue) and rebuilt from the command instances at startup, before it
  reports ready, and every 30 s.

The load balancer routes `GET` and `HEAD` of `/api/**` to the query instances and everything else to the command
instances, which also act as `backup` servers for queries. On Kubernetes the query instances are a Deployment with
their own auto-scaler (2 to 4) and their own address.

## Alternatives considered

- **Only separate processes.** With few instances, the combined instances are simpler and read your own write at
  once; the split pays off only when reads dominate, which the deployment decides.
- **A member that holds no aircraft.** It would still change the ranking of every aircraft (rendezvous hashing
  ranks names) and be counted in replication and sync. Outside the cluster, adding or removing query instances
  moves nothing.
- **Forward commands to the owner instead of refusing them.** Would work, but blurs the separation; a `405` shows a
  misrouted client immediately.

## Consequences

- Reads scale independently of writes; a query instance fails without affecting any command.
- "Read your own write" is not immediate on a query instance (it learns changes from the broker, milliseconds
  later); `X-Read-Side: write-side` makes it ask the command instances.
- A Kubernetes Service cannot route by HTTP method, so on Kubernetes clients use two addresses (9443 commands, 9444
  queries); Docker Compose uses nginx to keep one.

## Evidence

`QueryInstanceIntegrationTest`, `ClusterTest`, Postman folder **16**, diagram
[12-query-instances](../diagrams/12-query-instances.png),
[architecture.md, Separate processes](../architecture.md#separate-processes-query-instances-optional).
