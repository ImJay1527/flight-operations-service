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
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Peer-to-peer access to the other instances of THIS service.
 *
 * <p>Data is partitioned: a flight lives on the instance that created it. When a read hits an instance that does not
 * hold the data, it asks its peers on their /internal/flights/** endpoints. Those internal endpoints answer from
 * local data only and never forward again, so a query can never loop. A peer that is down is skipped and counted
 * (partial availability instead of failure), which favours availability over consistency (AP in CAP).
 *
 * <p>Every call goes through {@link ResilientCaller} (retry with backoff, circuit breaker; PL3 p.14).
 * <ul>
 *   <li>Lists (all flights of an aircraft, departures, reports) need every peer: they are asked <b>in parallel</b>
 *       (P1 p.12 "Parallel Querying"). Unreachable peers are reported to the client ({@link PartialResults}).</li>
 *   <li>One flight: the peers are asked <b>one by one</b> until one has it (PL3 p.11); the peer asked first rotates
 *       from request to request, spreading the load across the healthy peers (PL3 p.12 "Load Balancing").</li>
 * </ul>
 */
@Component
public class PeerClient {

    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);

    private final List<Peer> peers = new ArrayList<>();
    private final JwtUtils jwtUtils;
    private final ResilientCaller caller;
    private final AtomicInteger nextFirst = new AtomicInteger();
    /** One cheap virtual thread per peer call (Java 21), so list queries reach all peers at the same time. */
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
        ClientHttpRequestFactory reads = httpClientFactory.create(Duration.ofMillis(timeoutMs));
        // forwarded bookings: the owner itself calls the other services before answering, so allow more time
        ClientHttpRequestFactory writes = httpClientFactory.create(Duration.ofMillis(writeTimeoutMs));
        for (Cluster.Member member : cluster.peers()) {
            peers.add(new Peer(member.name(), registry.register("flight-operations peer", member.url()),
                    builder.clone().baseUrl(member.url()).requestFactory(reads)
                            .requestInterceptor(RequestIdPropagation.INSTANCE).build(),
                    builder.clone().baseUrl(member.url()).requestFactory(writes)
                            .requestInterceptor(RequestIdPropagation.INSTANCE).build()));
        }
    }

    /**
     * POST to one specific peer (used to forward a booking to the instance that owns the aircraft). Never retried:
     * the peer may already have stored it. Errors are passed on unchanged: {@link CircuitOpenException},
     * {@link HttpClientErrorException} (the peer refused), other {@link RestClientException}s (not reached / no answer).
     */
    public <T> T postTo(String peerName, String path, Object body, Class<T> type) {
        Peer peer = peers.stream().filter(p -> p.name().equals(peerName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown peer " + peerName));
        T answer = caller.call(peer.health(), false, () -> peer.writeClient().post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer())
                .body(body)
                .retrieve()
                .body(type));
        log.debug("Peer {} handled POST {}", peer.name(), path);
        return answer;
    }

    public boolean hasPeers() {
        return !peers.isEmpty();
    }

    /**
     * Asks EVERY peer for a list, all at the same time (P1 p.12 "Parallel Querying"), and concatenates the answers.
     * The request takes as long as the slowest peer, not the sum of all of them.
     */
    public <T> PeerResult<T> getList(String path, ParameterizedTypeReference<List<T>> type, Object... uriVariables) {
        Map<String, String> logContext = MDC.getCopyOfContextMap();   // request id + instance name for the log lines
        List<Future<List<T>>> answers = new ArrayList<>();
        for (Peer peer : peers) {
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
        PartialResults.record(unreachable);   // -> X-Partial-Result / X-Unreachable-Peers response headers
        return new PeerResult<>(all, unreachable);
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

    /** Asks peers one by one until one of them owns the resource (a 404 means "not mine"). */
    public <T> PeerResult<T> getOne(String path, Class<T> type, Object... uriVariables) {
        return findOwner(false, path, type, uriVariables);
    }

    /** Sends a PATCH to the peer that owns the resource (used to cancel a flight stored elsewhere). Never retried. */
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

    /** The peers, starting at a different one each time (round-robin). */
    private List<Peer> rotated() {
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
