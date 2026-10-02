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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Peer-to-peer access to the other replicas of THIS service.
 *
 * <p>Data is partitioned: a flight lives on the replica that created it. When a read hits a replica that does not
 * hold the data, it asks every peer on their /internal/flights/** endpoints. Those internal endpoints answer from
 * local data only and never forward again, so a query can never loop. A peer that is down is skipped and counted
 * (partial availability instead of failure), which favours availability over consistency (AP in CAP).
 */
@Component
public class PeerClient {

    private static final Logger log = LoggerFactory.getLogger(PeerClient.class);

    private final List<String> peerUrls;
    private final List<RestClient> peers = new ArrayList<>();
    private final JwtUtils jwtUtils;

    public PeerClient(@Value("${flightops.peers:}") List<String> peerUrls,
                      @Value("${flightops.peers-timeout-ms:1500}") long timeoutMs,
                      JwtUtils jwtUtils, RestClient.Builder builder, HttpClientFactory httpClientFactory) {
        this.peerUrls = peerUrls.stream().filter(u -> !u.isBlank()).toList();
        this.jwtUtils = jwtUtils;
        ClientHttpRequestFactory factory = httpClientFactory.create(Duration.ofMillis(timeoutMs));
        for (String url : this.peerUrls) {
            peers.add(builder.clone().baseUrl(url).requestFactory(factory).build());
        }
    }

    public boolean hasPeers() {
        return !peers.isEmpty();
    }

    /** Asks every peer for a list and concatenates the answers. */
    public <T> PeerResult<T> getList(String path, ParameterizedTypeReference<List<T>> type, Object... uriVariables) {
        List<T> all = new ArrayList<>();
        int unreachable = 0;
        for (int i = 0; i < peers.size(); i++) {
            try {
                List<T> part = peers.get(i).get().uri(path, uriVariables)
                        .header(HttpHeaders.AUTHORIZATION, bearer())
                        .retrieve()
                        .body(type);
                if (part != null) {
                    all.addAll(part);
                }
                log.debug("Peer {} answered GET {} with {} item(s)", peerUrls.get(i), path, part == null ? 0 : part.size());
            } catch (RestClientException e) {
                unreachable++;
                log.warn("Peer {} unreachable for GET {}: {}", peerUrls.get(i), path, e.getMessage());
            }
        }
        return new PeerResult<>(all, unreachable);
    }

    /** Asks peers one by one until one of them owns the resource (a 404 means "not mine"). */
    public <T> PeerResult<T> getOne(String path, Class<T> type, Object... uriVariables) {
        return findOwner(false, path, type, uriVariables);
    }

    /** Sends a PATCH to the peer that owns the resource (used to cancel a flight stored elsewhere). */
    public <T> PeerResult<T> patchOne(String path, Class<T> type, Object... uriVariables) {
        return findOwner(true, path, type, uriVariables);
    }

    private <T> PeerResult<T> findOwner(boolean patch, String path, Class<T> type, Object[] uriVariables) {
        int unreachable = 0;
        for (int i = 0; i < peers.size(); i++) {
            try {
                RestClient.RequestHeadersSpec<?> spec = patch
                        ? peers.get(i).patch().uri(path, uriVariables)
                        : peers.get(i).get().uri(path, uriVariables);
                T body = spec.header(HttpHeaders.AUTHORIZATION, bearer()).retrieve().body(type);
                if (body != null) {
                    log.debug("Peer {} owns {} {} - answered", peerUrls.get(i), patch ? "PATCH" : "GET", path);
                    return new PeerResult<>(List.of(body), unreachable);
                }
            } catch (HttpClientErrorException.NotFound e) {
                log.debug("Peer {} does not have {} - asking the next one", peerUrls.get(i), path);
            } catch (HttpClientErrorException.Conflict e) {
                // the owner replied: the operation is not allowed in the flight's current state
                throw new IllegalStateException("The flight cannot be changed in its current state.");
            } catch (RestClientException e) {
                unreachable++;
                log.warn("Peer {} unreachable for {} {}: {}", peerUrls.get(i), patch ? "PATCH" : "GET", path, e.getMessage());
            }
        }
        return new PeerResult<>(List.of(), unreachable);
    }

    private String bearer() {
        return "Bearer " + jwtUtils.generateServiceToken("flight-operations-service");
    }
}
