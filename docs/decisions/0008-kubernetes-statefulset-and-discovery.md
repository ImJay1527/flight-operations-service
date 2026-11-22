# 0008. Kubernetes StatefulSet with DNS discovery and CPU-based auto-scaling

- Status: Accepted
- Date: 2026-10-16 (pull requests #3 and #4)
- Requirement: P1, horizontal scalability; PL3 p.25 (service discovery instead of a fixed list); PL3 p.20
  (horizontal scalability, high availability)

## Context

With Docker Compose the instances are a fixed list (`CLUSTER`): adding one means changing the configuration and
restarting. Auto-scaling requires instances that appear and disappear at run time, and that the others find
without reconfiguration. Rendezvous hashing ([0001](0001-shard-flights-by-aircraft.md)) ranks instances **by name**,
so a restarted instance must keep its name, or every aircraft it owned would move.

## Decision

- Each instance is a pod of the **StatefulSet** `flightops`: stable names (`flightops-0`, `flightops-1`, ...) and a
  persistent volume per database. The pod also runs the instance's PostgreSQL and MongoDB as sidecars.
- **Discovery by DNS** (`cluster.DnsDiscovery`, profile `k8s`): every 5 s each instance resolves
  `flightops-{i}.flightops-peers` (the names the headless Service gives the pods); the names that resolve are the
  instances.
- A **HorizontalPodAutoscaler** keeps 3 to 5 instances: one is added when the average CPU of the application
  containers exceeds 70 % of their request, one is removed after 2 minutes of low load, one pod per minute.
- **Rebalancing:** a new instance fetches the flights of the aircraft it wins before it reports ready; the others
  then hand off the flights they no longer hold.
- Clients use a `LoadBalancer` Service, which only routes to pods whose readiness probe passes.

## Alternatives considered

- **Deployment.** Random pod names: every restart would change the ranking and move aircraft.
- **Watching the Kubernetes API for endpoints.** Immediate, but needs a service account and RBAC permissions, and
  ties the code to Kubernetes. DNS lookups work with the standard headless Service.
- **Scaling on request rate.** Needs a custom metrics adapter; CPU is available out of the box through the
  metrics server.

## Consequences

- Scale-up and scale-down move only the aircraft involved, and their flights follow automatically.
- Removing two pods at once (by hand) could remove both holders of some aircraft before their flights are copied;
  the auto-scaler removes one pod per minute to avoid it.
- For about 15 s after a pod joins or leaves, instances may briefly disagree on an aircraft's owner.

## Evidence

`ClusterTest` (pods joining and leaving), the Kubernetes test in
[architecture.md, Kubernetes and auto-scaling](../architecture.md#kubernetes-and-auto-scaling), the screenshot of the
dashboard after a scale-up in the [README](../../README.md#screenshots).
