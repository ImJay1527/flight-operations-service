# 0002. Keep two copies of every flight, replicated through a transactional outbox

- Status: Accepted
- Date: 2026-10-02 (pull request #2)
- Requirement: P1 p.2, "redundancy-based fault tolerance"; P1 p.10-11, redundancy and eventual consistency

## Context

With sharding alone ([0001](0001-shard-flights-by-aircraft.md)), the flights of a stopped instance cannot be read
until it is back: the behaviour the week-3 practical session expects (PL3 p.16). The statement asks for fault
tolerance based on redundancy, so the data of one failed instance must remain available from another.

## Decision

Every flight is kept on the first **two** instances of its aircraft's ranking: the **owner** and the **backup**
(`flightops.replication.factor`, environment variable `REPLICATION_FACTOR`, default 2).

1. A change and a task "send flight F to instance B" are saved in the **same database transaction**
   (`replication_outbox`), so no change is ever stored without being queued for its other copy.
2. `ReplicationSender` delivers the current state right after the commit (`POST /internal/replicas`), retrying every
   second while the target is down.
3. Every flight carries a **revision**; a copy replaces the existing one only if it is newer, so copies converge
   whatever order they arrive in.
4. **Anti-entropy:** at startup, before reporting ready, and every 60 s, each instance fetches from its peers the
   flights it should hold. A restarted instance catches up even with an empty database.
5. When the owner is unreachable, the backup takes the aircraft's bookings.

## Alternatives considered

- **No replication.** Simplest, but a failed instance makes its flights unavailable. Still available as
  `REPLICATION_FACTOR=1` to demonstrate the difference.
- **Synchronous replication (both copies in one transaction, 2PC).** Strong consistency, but a booking would block
  or fail whenever the backup is down, which is exactly the failure being tolerated.
- **Primary-backup with leader election.** Adds an election protocol and a failover delay. With rendezvous hashing
  the next holder is known to every instance without electing it.
- **Full replication (every instance holds every flight).** Contradicts PL3 p.24 and does not scale the write side.
- **Replication through the message broker.** Rejected in Assignment 2: a copy must reach exactly the one or two
  holders of the aircraft, and the outbox already retries per target ([0011](0011-publish-subscribe-one-queue-per-instance.md)).

## Consequences

- One instance down: every flight is still served, bookings continue on the backups.
- Both holders of an aircraft down at once: its flights are unavailable until one returns ([0004](0004-503-for-unreachable-data.md)).
- Consistency between the two copies is eventual; the window is normally milliseconds ([0003](0003-availability-over-consistency.md)).
- Each instance holds about 2/3 of the aircraft with 3 instances.

## Evidence

`ReplicationIntegrationTest` (three real instances), `ReplicaStoreTest`, `ReplicaSyncTest`, Postman folders
**03 Resilience** and **11b-11d**; `replicationBacklog` in `GET /api/cluster/health`.
