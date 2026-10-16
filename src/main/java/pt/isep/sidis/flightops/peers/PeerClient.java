package pt.isep.sidis.flightops.peers;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pt.isep.sidis.flightops.api.dto.FlightView;
import pt.isep.sidis.flightops.clients.HttpClientFactory;
import pt.isep.sidis.flightops.cluster.Cluster;
import pt.isep.sidis.flightops.common.security.JwtUtils;
import pt.isep.sidis.flightops.common.tracing.RequestIdPropagation;
import pt.isep.sidis.flightops.resilience.CircuitOpenException;
import pt.isep.sidis.flightops.resilience.EndpointHealth;
import pt.isep.sidis.flightops.resilience.HealthRegistry;
import pt.isep.sidis.flightops.resilience.ResilientCaller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Calls the other instances of this service on their /internal/flights/** endpoints. Those endpoints answer from
 * local data only and never forward again, so a query can't loop. A peer that is down is skipped and counted
 * instead of failing the request (AP in CAP). Every call goes through {@link ResilientCaller} (PL3 p.14).
 * <ul>
 *   <li>Lists need every peer, so they are asked in parallel (P1 p.12).</li>
 *   <li>A single flight: peers are asked one by one until one has it (PL3 p.11); the first peer asked rotates per
 *       request to spread the load (PL3 p.12).</li>
 *   <li>Copies of flights (replication): pushed to the instances that hold them, and fetched when catching up.</li>
 * </ul>
 * The peers are always the current members of the {@link Cluster}: with discovery (Kubernetes) they change while the
 * instance runs, and an instance that left is forgotten (no more health checks or circuit for it).
 */
@Component
public class PeerClient {

    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);

    private final Map<String, Peer> peersByName = new ConcurrentHashMap<>();
    private final Cluster cluster;
    private final HealthRegistry registry;
    private final RestClient.Builder builder;
    private final ClientHttpRequestFactory reads;
    private final ClientHttpRequestFactory writes;
    private final JwtUtils jwtUtils;
    private final ResilientCaller caller;
    private final AtomicInteger nextFirst = new AtomicInteger();
    /** Virtual threads (Java 21): one per peer call, so list queries reach all peers at the same time. */
    private final ExecutorService parallel = Executors.newVirtualThreadPerTaskExecutor();

    private record Peer(String name, EndpointHealth health, RestClient client, RestClient writeClient) {
    }

    public PeerClient(Cluster cluster,
                      @Value("${flightops.peers-timeout-ms:1500}") long timeoutMs,
                      @Value("${flightops.peers-write-timeout-ms:10000}") long writeTimeoutMs,
                      JwtUtils jwtUtils, RestClient.Builder builder, HttpClientFactory httpClientFactory,
                      HealthRegistry registry, ResilientCaller caller) {
        this.jwtUtils = jwtUtils;
        this.caller = caller;
        this.cluster = cluster;
        this.registry = registry;
        this.builder = builder;
        this.reads = httpClientFactory.create(Duration.ofMillis(timeoutMs));
        // forwarded bookings: the owner itself calls the other services before answering, so allow more time
        this.writes = httpClientFactory.create(Duration.ofMillis(writeTimeoutMs));
        peers();   // registers the peers known now, so health checks start at once
        cluster.onChange(change -> change.left().forEach(this::forget));
    }

    /** The current peers, in the cluster's order. */
    private List<Peer> peers() {
        return cluster.peers().stream().map(this::peerFor).toList();
    }

    private Peer peerFor(Cluster.Member member) {
        return peersByName.computeIfAbsent(member.name(), name -> new Peer(name,
                registry.register("flight-operations peer", member.url()),
                builder.clone().baseUrl(member.url()).requestFactory(reads)
                        .requestInterceptor(RequestIdPropagation.INSTANCE).build(),
                builder.clone().baseUrl(member.url()).requestFactory(writes)
                        .requestInterceptor(RequestIdPropagation.INSTANCE).build()));
    }

    private void forget(Cluster.Member member) {
        peersByName.remove(member.name());
        registry.unregister(member.url());
    }

    /**
     * Forwards a booking to the aircraft's owner. Never retried: the peer may already have stored it.
     * Errors are passed on unchanged so the caller can tell "refused" (4xx) from "not reached" / "no answer".
     */
    public <T> T postTo(String peerName, String path, Object body, Class<T> type) {
        Peer peer = peer(peerName);
        T answer = caller.call(peer.health(), false, () -> peer.writeClient().post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .body(body)
                .retrieve()
                .body(type));
        log.debug("Peer {} handled POST {}", peer.name(), path);
        return answer;
    }

    /** Sends the current state of a flight to an instance that keeps a copy. Safe to repeat, so it is retried. */
    public void pushCopy(String peerName, FlightView flight) {
        Peer peer = peer(peerName);
        caller.call(peer.health(), true, () -> peer.client().post().uri("/internal/replicas")
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .body(flight)
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * The flights a peer holds that the given instance should also hold; null if the peer could not be asked.
     * One attempt only: at startup the peers may still be starting too, and retries would open their circuit.
     */
    public List<FlightView> copiesFor(String peerName, String instanceName) {
        Peer peer = peer(peerName);
        try {
            return caller.call(peer.health(), false, () -> peer.client().get()
                    .uri("/internal/replicas?for={instance}", instanceName)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<FlightView>>() {}));
        } catch (CircuitOpenException | RestClientException e) {
            log.debug("Could not fetch copies from {}: {}", peer.health().url(), e.getMessage());
            return null;
        }
    }

    public boolean hasPeers() {
        return !cluster.peers().isEmpty();
    }

    /** A current peer; IllegalArgumentException if no instance has this name (any more). */
    private Peer peer(String name) {
        return cluster.peers().stream().filter(m -> m.name().equals(name)).findFirst().map(this::peerFor)
                .orElseThrow(() -> new IllegalArgumentException("Unknown peer " + name));
    }

    /** Asks every peer at the same time and concatenates the answers. */
    public <T> PeerResult<T> getList(String path, ParameterizedTypeReference<List<T>> type, Object... uriVariables) {
        Map<String, String> logContext = MDC.getCopyOfContextMap();   // keeps the request id in the peer threads' logs
        List<Future<List<T>>> answers = new ArrayList<>();
        for (Peer peer : peers()) {
            answers.add(parallel.submit(() -> withLogContext(logContext, () -> listFrom(peer, path, type, uriVariables))));
        }

        List<T> all = new ArrayList<>();
        int unreachable = 0;
        for (Future<List<T>> answer : answers) {
            try {
                List<T> part = answer.get();
                if (part == null) {
                    unreachable++;
                } else {
                    all.addAll(part);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unreachable++;
            } catch (ExecutionException e) {
                unreachable++;
            }
        }
        // Every flight is on replicationFactor instances, this one included. Data can only be missing if at least
        // that many peers did not answer.
        PartialResults.record(unreachable, unreachable >= cluster.replicationFactor());
        return new PeerResult<>(all, unreachable);
    }

    /**
     * Asks every peer at the same time for one object (e.g. its status for the dashboard). The value is null for a
     * peer that could not be asked; peers are in the cluster's order.
     */
    public <T> Map<String, T> getFromEach(String path, Class<T> type) {
        Map<String, String> logContext = MDC.getCopyOfContextMap();
        Map<String, Future<T>> answers = new LinkedHashMap<>();
        for (Peer peer : peers()) {
            answers.put(peer.name(), parallel.submit(() -> withLogContext(logContext, () -> {
                try {
                    return caller.call(peer.health(), false, () -> peer.client().get().uri(path)
                            .header(HttpHeaders.AUTHORIZATION, bearer())
                            .retrieve()
                            .body(type));
                } catch (CircuitOpenException | RestClientException e) {
                    log.debug("Peer {} skipped for GET {}: {}", peer.health().url(), path, e.getMessage());
                    return null;
                }
            })));
        }
        Map<String, T> result = new LinkedHashMap<>();
        answers.forEach((name, answer) -> {
            T value = null;
            try {
                value = answer.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                log.debug("Peer {} failed for GET {}: {}", name, path, e.getMessage());
            }
            result.put(name, value);
        });
        return result;
    }

    /** One peer's list, or null if it could not be asked (down, timeout, circuit open). */
    private <T> List<T> listFrom(Peer peer, String path, ParameterizedTypeReference<List<T>> type, Object[] uriVariables) {
        try {
            List<T> part = caller.call(peer.health(), true, () -> peer.client().get().uri(path, uriVariables)
                    .header(HttpHeaders.AUTHORIZATION, bearer())
                    .retrieve()
                    .body(type));
            log.debug("Peer {} answered GET {} with {} item(s)", peer.health().url(), path, part == null ? 0 : part.size());
            return part == null ? List.of() : part;
        } catch (CircuitOpenException e) {
            log.debug("Peer {} skipped for GET {}: circuit open", peer.health().url(), path);
            return null;
        } catch (RestClientException e) {
            log.warn("Peer {} unreachable for GET {}: {}", peer.health().url(), path, e.getMessage());
            return null;
        }
    }

    private static <V> V withLogContext(Map<String, String> context, Callable<V> task) throws Exception {
        if (context != null) {
            MDC.setContextMap(context);
        }
        try {
            return task.call();
        } finally {
            MDC.clear();
        }
    }

    @PreDestroy
    void shutdown() {
        parallel.shutdownNow();
    }

    /** Asks peers one by one until one has the resource (a 404 means "not mine"). */
    public <T> PeerResult<T> getOne(String path, Class<T> type, Object... uriVariables) {
        return findOwner(false, path, type, uriVariables);
    }

    /** PATCH to the peer that has the resource (cancel a flight stored elsewhere). Never retried. */
    public <T> PeerResult<T> patchOne(String path, Class<T> type, Object... uriVariables) {
        return findOwner(true, path, type, uriVariables);
    }

    private <T> PeerResult<T> findOwner(boolean patch, String path, Class<T> type, Object[] uriVariables) {
        int unreachable = 0;
        String method = patch ? "PATCH" : "GET";
        for (Peer peer : rotated()) {
            try {
                T body = caller.call(peer.health(), !patch, () -> {
                    RestClient.RequestHeadersSpec<?> spec = patch
                            ? peer.client().patch().uri(path, uriVariables)
                            : peer.client().get().uri(path, uriVariables);
                    return spec.header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().body(type);
                });
                if (body != null) {
                    log.debug("Peer {} owns {} {} - answered", peer.health().url(), method, path);
                    return new PeerResult<>(List.of(body), unreachable, peer.health().url());
                }
            } catch (HttpClientErrorException.NotFound e) {
                log.debug("Peer {} does not have {} - asking the next one", peer.health().url(), path);
            } catch (HttpClientErrorException.Conflict e) {
                // the owner replied: the operation is not allowed in the flight's current state
                throw new IllegalStateException("The flight cannot be changed in its current state.");
            } catch (CircuitOpenException e) {
                unreachable++;
                log.debug("Peer {} skipped for {} {}: circuit open", peer.health().url(), method, path);
            } catch (RestClientException e) {
                unreachable++;
                log.warn("Peer {} unreachable for {} {}: {}", peer.health().url(), method, path, e.getMessage());
            }
        }
        return new PeerResult<>(List.of(), unreachable);
    }

    private List<Peer> rotated() {
        List<Peer> peers = peers();
        if (peers.size() < 2) {
            return peers;
        }
        int start = Math.floorMod(nextFirst.getAndIncrement(), peers.size());
        List<Peer> order = new ArrayList<>(peers.size());
        for (int i = 0; i < peers.size(); i++) {
            order.add(peers.get((start + i) % peers.size()));
        }
        return order;
    }

    private String bearer() {
        return "Bearer " + jwtUtils.generateServiceToken("flight-operations-service");
    }
}
