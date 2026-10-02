package pt.isep.sidis.flightops.peers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pt.isep.sidis.flightops.clients.HttpClientFactory;
import pt.isep.sidis.flightops.common.security.JwtUtils;
import pt.isep.sidis.flightops.resilience.CircuitOpenException;
import pt.isep.sidis.flightops.resilience.EndpointHealth;
import pt.isep.sidis.flightops.resilience.HealthRegistry;
import pt.isep.sidis.flightops.resilience.ResilientCaller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Peer-to-peer access to the other instances of THIS service.
 *
 * <p>Data is partitioned: a flight lives on the instance that created it. When a read hits an instance that does not
 * hold the data, it asks its peers on their /internal/flights/** endpoints. Those internal endpoints answer from
 * local data only and never forward again, so a query can never loop. A peer that is down is skipped and counted
 * (partial availability instead of failure), which favours availability over consistency (AP in CAP).
 *
 * <p>Every call goes through {@link ResilientCaller} (retry with backoff, circuit breaker; PL3 p.14). When looking
 * for the owner of one flight, the peer asked first rotates from request to request, spreading the load across the
 * healthy peers (PL3 p.12 "Load Balancing").
 */
@Component
public class PeerClient {

    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);

    private final List<Peer> peers = new ArrayList<>();
    private final JwtUtils jwtUtils;
    private final ResilientCaller caller;
    private final AtomicInteger nextFirst = new AtomicInteger();

    private record Peer(EndpointHealth health, RestClient client) {
    }

    public PeerClient(@Value("${flightops.peers:}") List<String> peerUrls,
                      @Value("${flightops.peers-timeout-ms:1500}") long timeoutMs,
                      JwtUtils jwtUtils, RestClient.Builder builder, HttpClientFactory httpClientFactory,
                      HealthRegistry registry, ResilientCaller caller) {
        this.jwtUtils = jwtUtils;
        this.caller = caller;
        ClientHttpRequestFactory factory = httpClientFactory.create(Duration.ofMillis(timeoutMs));
        for (String url : peerUrls.stream().filter(u -> !u.isBlank()).toList()) {
            peers.add(new Peer(registry.register("flight-operations peer", url),
                    builder.clone().baseUrl(url).requestFactory(factory).build()));
        }
    }

    public boolean hasPeers() {
        return !peers.isEmpty();
    }

    /** Asks every peer for a list and concatenates the answers. */
    public <T> PeerResult<T> getList(String path, ParameterizedTypeReference<List<T>> type, Object... uriVariables) {
        List<T> all = new ArrayList<>();
        int unreachable = 0;
        for (Peer peer : peers) {
            try {
                List<T> part = caller.call(peer.health(), true, () -> peer.client().get().uri(path, uriVariables)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .retrieve()
                        .body(type));
                if (part != null) {
                    all.addAll(part);
                }
                log.debug("Peer {} answered GET {} with {} item(s)", peer.health().url(), path, part == null ? 0 : part.size());
            } catch (CircuitOpenException e) {
                unreachable++;
                log.debug("Peer {} skipped for GET {}: circuit open", peer.health().url(), path);
            } catch (RestClientException e) {
                unreachable++;
                log.warn("Peer {} unreachable for GET {}: {}", peer.health().url(), path, e.getMessage());
            }
        }
        return new PeerResult<>(all, unreachable);
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
                    return new PeerResult<>(List.of(body), unreachable);
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
