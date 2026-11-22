# 0011. Publish/subscribe on a topic exchange, one queue per instance

- Status: Accepted
- Date: 2026-11-21 (pull request #26)
- Requirement: P2 p.2, "AMQP and a message broker" for the communication between services, external API stays
  REST; RabbitMQ tutorials 3 to 5

## Context

Two kinds of consumer need the flight changes: every other Flight Operations instance (each has a read model of the
whole cluster, [0009](0009-cqrs-separate-read-database.md)) and the other services (Aircraft & Maintenance adds the
flight hours of a completed flight). Conversely, every instance needs the other services' changes to aircraft,
airports and routes ([0014](0014-local-copies-of-other-services-data.md)). Each event must reach **every**
interested instance, not just one of them.

## Decision

- One **topic exchange**, `aisafe.events` (durable), shared by the three services. Routing keys follow
  `<entity>.<what happened>`: `flight.scheduled`, `flight.cancelled`, `flight.completed`, `aircraft.*`,
  `airport.*`, `route.*`.
- **One durable queue per instance** of every service (`flight.events.<instance>` here), bound to the patterns it
  needs: `flight.#`, `aircraft.#`, `airport.#`, `route.#`. Each instance therefore receives its own copy of every
  event (publish/subscribe).
- The external API remains REST; AMQP is used only between services and between instances.
- The message format is plain JSON without Java type headers, specified in
  [service-contracts.md](../service-contracts.md#events-amqp-assignment-2).

## Alternatives considered

- **One shared queue per service (work queues, tutorial 2).** Each event would reach only one instance (competing
  consumers): right for tasks, wrong for state, because the other instances' read models would miss it.
- **Fanout exchange.** Delivers everything to everyone; a consumer could not choose the events it needs.
- **Direct exchange.** Requires one binding per exact routing key; adding an event type means changing every
  consumer's bindings, where a topic pattern (`aircraft.#`) already covers it.
- **Kafka.** A replayable log suits event sourcing, which is not used. RabbitMQ is the broker of the course, and its
  per-queue acknowledgements, dead letters and limits are what this design needs.
- **Replicating the write side through the broker.** Rejected: a copy must reach exactly the aircraft's holders;
  that stays HTTP with its own outbox ([0002](0002-two-copies-of-every-flight.md)).

## Consequences

- Adding an instance or a service means declaring one more queue; publishers do not change.
- Every instance receives its own events too, and skips them (already projected after the commit).
- The single broker node is a point of failure **for the events only**: commands and queries do not depend on it
  ([0012](0012-event-outbox-and-publisher-confirms.md)).

## Evidence

`MessagingIntegrationTest` (real broker, one queue and consumer per instance), Postman folder **13**, the RabbitMQ
management UI (http://localhost:15672), diagram [9-messaging-topology](../diagrams/9-messaging-topology.png).
