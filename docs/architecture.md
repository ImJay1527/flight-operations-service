# Flight Operations – architecture notes

## Horizontal scaling

### Instances and ports

Every component starts with **2 instances**, as recommended. More are added only when load testing shows they help.

| Component | Instance 1 | Instance 2 | Instance 3 (scale-up) |
|---|---|---|---|
| aircraft-maintenance-service | 8081 | 8091 | 8101 |
| airports-routes-service | 8082 | 8092 | 8102 |
| flight-operations-service | 8083 | 8093 | 8103 |

Port scheme: base port + 10 per extra instance. Each instance has its own database (data isolation), so instances
share nothing except the network.

### What makes it scalable

* **Stateless requests.** Authentication is a self-contained JWT, so there are no server sessions and any instance can
  serve any request.
* **Client-side load balancing.** Calls to another service rotate round-robin over its instances and fail over to
  the next one on timeout or 5xx (`ReplicatedServiceClient`).
* **Service discovery with hardcoded peer lists.** `PEERS`, `AIRCRAFT_SERVICE_URLS` and `AIRPORTS_ROUTES_SERVICE_URLS`.
* **Sharded data.** A flight is stored on the instance that created it. Reads that need everything ask every peer
  and merge the answers (scatter-gather, `FlightQueryService`).

### How to add an instance

1. Start the new instance with its own port and database, and with every existing instance in its `PEERS`.
2. Add the new instance to the `PEERS` of every existing instance (and to the URL lists of services that call it).
3. Restart the existing instances so they pick up the new peer list.

The same steps are packaged as an override file:

```bash
docker compose -f docker-compose.yml -f docker-compose.scale-3.yml up --build
```

Without Docker: `./scripts/run-local.sh -n 3`.

Limitation: because the peer lists are hardcoded, scaling needs a configuration change and restarts. The next step
would be dynamic discovery: a service registry (Eureka, Consul) or DNS-based discovery (e.g. Kubernetes headless
services). Instances would then register themselves, and peers would be found at runtime.

### Load test

Tool: [k6](https://k6.io), run in Docker (`./loadtest/run.sh 2` and `./loadtest/run.sh 3`).

* **Workload:** 50 virtual users for 60 s, each request to a random instance (like a load balancer) and a random
  read endpoint: `/api/aircraft-utilization`, `/api/aircraft-utilization/CS-TPA`, `/api/fuel-efficiency/aircraft`,
  `/api/fuel-efficiency/routes`. All of them need the data of every instance, so they exercise the peer-to-peer
  path.
* **Conditions:**
  * Every flight-ops instance is limited to **1 CPU and 768 MB** (`loadtest/limits.yml`), to simulate one small
    machine per instance; all on one laptop.
  * HTTPS between all parties.
  * 2 × 30 s warm-up before measuring: on 1 CPU the JVM's JIT compilation otherwise dominates the first minute.

**Results (2026-10-02):**

| Instances | Throughput | Median latency | p95 | p99 | Errors |
|---|---|---|---|---|---|
| 2 | **59 req/s** | 705 ms | 1.78 s | 2.19 s | 0 % |
| 3 | **53 req/s** | 805 ms | 1.99 s | 2.60 s | 0 % |

Raw output: `loadtest/results/`.

### What the results mean

**For this workload, a third instance does not increase capacity.** Every request is a scatter-gather read:
the receiving instance does its local query *and* asks each of the other N−1 instances. The total work per request
therefore grows with N, at the same rate as the capacity added by the new instance. Throughput stays flat, and the
extra network hop costs a little, so 3 instances are slightly slower than 2.

Conclusion: stay at **2 instances**. Adding instances only pays off once most reads stop fanning out to every peer:

1. **Route by owner.** Pick the instance that stores a flight from a hash of the aircraft registration (consistent
   hashing). Requests about one aircraft then go to one instance, with no fan-out, so capacity grows with N. This
   also removes the double-booking race, because all bookings for an aircraft are serialised on one instance.
2. **Replicate.** Copy every write to the other instances (or to k of them), so reads can be answered locally.
   Reads then scale with N, writes get more expensive, and the system gains redundancy: a flight survives the
   loss of the instance that created it.
3. **Cache aggregates.** Utilization and fuel reports change rarely; caching them for a few seconds per instance
   removes most of the fan-out.

### Performance problems found by load testing (fixed)

| Problem | Effect | Fix |
|---|---|---|
| JDK `HttpClient` completes responses asynchronously; on 1 CPU the JDK runs those completions on a **new thread per call** | thread creation dominated CPU | Apache HttpClient 5 (synchronous, pooled), pool sized explicitly (default is only 5 connections per peer) |
| jjwt does a ServiceLoader scan every time a parser or builder is created; the code built a parser 3× per request and signed a new service token per call | high CPU per request | parser built once and each token parsed once; service tokens cached (5 min lifetime, renewed 1 min before expiry) |
| Short runs on a cold JVM | numbers ~3× too low | warm-up runs before measuring |
