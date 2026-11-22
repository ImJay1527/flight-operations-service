# 0007. TLS 1.3 only in transit, AES-256-SIV at rest, short-lived service tokens

- Status: Accepted (extended to the message broker in Assignment 2)
- Date: 2026-10-02 (AMQPS: 2026-11-21)
- Requirement: P1 p.16, security of a distributed system (encryption, rotating tokens, inter-service
  authentication)

## Context

Distribution turns in-process calls into network traffic between instances and services, and spreads the data over
several databases. Both the traffic and the stored data become attack surface, and every instance must be able to
tell a user from another service.

## Decision

- **In transit:** HTTPS with **TLS 1.3 only** (a TLS 1.2 client is refused) for clients, the load balancer, peers
  and the other services. Outgoing calls trust **only** the AISafe development CA (`certs/truststore.p12`) and keep
  hostname verification on. The broker connection is **AMQPS** on port 5671, with the same CA and hostname checks.
- **At rest:** the route assignment of each flight (route, aircraft, model, airports) is stored **AES-256 encrypted**
  with a deterministic, authenticated scheme (SIV construction), in PostgreSQL, in MongoDB and in the event outbox.
  Values are versioned (`enc:v1:`) for key rotation; the key comes from `DATA_ENCRYPTION_KEY`.
- **Service identity:** every call between instances and services carries a **short-lived JWT** (5 minutes,
  renewed automatically) with role `SERVICE`. `/internal/**` accepts only that role; a service token cannot call
  `/api/**`, and a user token cannot call `/internal/**`.
- **Audit:** an `AUDIT` log line per request (user, roles, method, URI, status, duration, client address).

## Alternatives considered

- **TLS 1.2 and 1.3.** Wider compatibility, but every client and service in this system supports 1.3, and 1.2 keeps
  weaker cipher suites available.
- **Randomised encryption (AES-GCM) at rest.** Stronger against frequency analysis, but equality queries and
  indexes (flights by aircraft, departures by airport) would no longer work. SIV keeps them working and detects
  tampering.
- **Mutual TLS for service identity.** Strong, but requires a client certificate per service and per instance. A
  service JWT reuses the token validation every service already has.
- **Long-lived API keys between services.** A leaked key stays valid indefinitely; rotating tokens limit exposure.

## Consequences

- A database dump or a captured message shows only ciphertext for the sensitive fields.
- The development CA and default keys are for local runs; a real deployment must provide its own (Kubernetes takes
  them from Secrets). Re-encrypting existing data with a new key version is not implemented.
- Port 5672 (plain AMQP) remains open on the broker for the services that do not yet use TLS.

## Evidence

`SecurityIntegrationTest`, `EncryptionAtRestTest` (raw SQL shows `enc:v1:...`), `common/crypto/*Test`, Postman
folders **08 Encryption in transit** and **13** (every broker connection on 5671 with `tlsv1.3`).
