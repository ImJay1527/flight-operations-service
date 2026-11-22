# 0010. Fall back to the write side when the read model cannot answer

- Status: Accepted
- Date: 2026-11-20 (pull request #25)
- Requirement: P2 p.2, fault tolerance; consistent with the AP choice of [0003](0003-availability-over-consistency.md)

## Context

The read database is a new component that can fail, be slow, or be empty (an instance that has just started, a
volume that was deleted). The write side still holds the source of truth and can answer every query, as it did in
Assignment 1, only more slowly.

## Decision

`FlightQueries` uses the read model only while it is `READY`. When it is `BUILDING`, `STALE` (read database
unreachable, a projection failed, a sync incomplete) or `DISABLED`, the query takes the **fallback path**: the write
side, local copy first and then the peers, exactly as in Assignment 1. A single flight not yet present in the read
model is also looked up on the write side. Short MongoDB timeouts make the switch happen within about two seconds.

A client can ask for the write side explicitly with **`X-Read-Side: write-side`**, to read every change already made
anywhere in the cluster.

## Alternatives considered

- **Answer `503` while the read model is unusable.** Simpler and keeps the sides strictly apart, but makes the
  read database a single point of failure for every query.
- **Serve the possibly outdated read model anyway.** Available, but a deleted or empty read database would answer
  "nothing found" with `200`.

## Consequences

- No query fails because of the read side alone; the fallback is slower but its data is fresher, never older.
- An instance never answers from an empty read model: it is rebuilt before the instance reports ready.
- Every response says which path answered (`X-Read-Side`, `X-Read-Model`), so the behaviour is observable.

## Evidence

`ReadDatabaseDownTest`, `FlightQueriesTest`, the read-database pause in `CqrsIntegrationTest`
(`X-Read-Model: STALE` until it is back), Postman folder **12** (the same query with `X-Read-Side: write-side`).
