# Known limitations

[Documentation guide](README.md) · [Running](running.md) · [Testing](testing.md) · [Operations](operations.md) · [Security](security.md) · [Limitations](limitations.md)

The trade-offs accepted on purpose and what would remove each of them. They follow from the decisions in
[decisions/](decisions/README.md), mainly favouring availability over consistency
([ADR 0003](decisions/0003-availability-over-consistency.md)).

* **Eventual consistency.** The other copy of a change arrives moments later (outbox, retried while the target is
  down); until then the two copies can differ. Lists keep the newest copy; a single lookup answers from its own copy
  (or, for a copy this instance no longer holds, from the aircraft's holders first).
* **Network partition (AP).** If the owner and the backup of an aircraft can't reach each other, both may accept
  an overlapping booking. It is detected when the copies meet and logged as `REPLICATION CONFLICT`; both flights are
  kept and one must be cancelled by hand. Preventing it would need a quorum (CP for bookings).
* **Two copies per flight.** One instance down: everything is still served. Both holders of an aircraft down at the
  same time: its flights are unavailable (`503` saying how many instances could not be reached, lists flagged with
  `X-Partial-Result`) until one of them is back. A new booking for that aircraft is still accepted by the instance
  that receives it, with a best-effort overlap check; it reaches the holders when they are back (an overlap is then
  logged as `REPLICATION CONFLICT`). `REPLICATION_FACTOR` can raise the number of copies.
* **Read model: lists are eventually consistent on the instances not involved in a change.** The instance that
  received a change has it in its read model at once; the others get it from the broker milliseconds later, or at
  their next sync (up to 30 s) if the broker is down. A single flight is always found (a miss is looked up on the
  write side), and `X-Read-Side: write-side` gives a read that sees every change.
* **One broker node.** RabbitMQ is a single point of failure for the events (not for bookings or queries): while it
  is down, events wait in the outboxes and the read models rely on the sync. A 3-node cluster with quorum queues
  would remove it.
* **The broker still accepts plain AMQP** (port 5672) for the other two services, which don't use TLS yet. Flight
  Operations uses AMQPS (5671). Once they move, 5672 can be closed.
* **Reports without a read model ask every instance** (utilization, fuel efficiency), so on the fallback path they
  don't get faster with more instances (load test in [docs/architecture.md](architecture.md#horizontal-scaling)).
  With the read model they are precomputed and local.
* **Every instance stores every flight in its read database.** Fine for this system's size; a very large system
  would shard the read model too (and its lists would again ask several instances).
* **Kubernetes rebalancing.** Removing two pods at once (e.g. `kubectl scale` by hand) could remove both holders of
  some aircraft before their flights are copied; the auto-scaler removes one pod per minute. For ~15 s after a pod
  joins or leaves, instances may disagree on an aircraft's owner (an overlap is then reported as above).
* **Docker Compose / scripts** use a fixed list of instances: adding one needs a configuration change and restarts
  (Kubernetes discovers instances by DNS instead).
* **Development defaults.** The JWT key, database password and encryption key have defaults in the configuration for
  local runs; a real deployment must override them (Kubernetes takes them from Secrets). Encryption keys can be
  versioned, but re-encrypting old data with a new key is not implemented.
* **Events are not signed, and the services share one broker user.** The broker connection is authenticated and
  encrypted, but a message carries no signature, and the development default is `guest` / `guest` for every
  service (Kubernetes: one user from a Secret). Anyone with those credentials could publish a forged event. One
  broker user per service, allowed to publish only its own routing keys, would remove it
  ([security.md](security.md#not-covered)).
* **Measured on one laptop.** Latencies and throughput have no real network between the instances; they show
  relative effects (local vs forwarded, 2 vs 3 instances), not production numbers.
