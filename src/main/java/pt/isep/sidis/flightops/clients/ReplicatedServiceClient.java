package pt.isep.sidis.flightops.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pt.isep.sidis.flightops.common.exceptions.ResourceNotFoundException;
import pt.isep.sidis.flightops.common.exceptions.ServiceUnavailableException;
import pt.isep.sidis.flightops.common.security.JwtUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for calling another service that runs as several replicas.
 * Requests are spread round-robin; if a replica is down (connection error, timeout, 5xx) the next one is tried.
 * A 404 is authoritative (the replica already asked its own peers) and is NOT retried.
 */
public abstract class ReplicatedServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ReplicatedServiceClient.class);

    public static final String CALLER_NAME = "flight-operations-service";

    private final List<RestClient> replicas = new ArrayList<>();
    private final List<String> replicaUrls;
    private final JwtUtils jwtUtils;
    private final String targetName;
    private final AtomicInteger next = new AtomicInteger();

    protected ReplicatedServiceClient(String targetName, List<String> baseUrls, JwtUtils jwtUtils,
                                      RestClient.Builder builder, HttpClientFactory httpClientFactory, Duration timeout) {
        this.targetName = targetName;
        this.jwtUtils = jwtUtils;
        this.replicaUrls = baseUrls;
        ClientHttpRequestFactory factory = httpClientFactory.create(timeout);
        for (String url : baseUrls) {
            replicas.add(builder.clone().baseUrl(url).requestFactory(factory).build());
        }
    }

    protected <T> T get(String path, Class<T> type, String notFoundMessage, Object... uriVariables) {
        if (replicas.isEmpty()) {
            throw new ServiceUnavailableException(targetName + " is not configured.");
        }
        int start = Math.floorMod(next.getAndIncrement(), replicas.size());
        RestClientException last = null;
        for (int i = 0; i < replicas.size(); i++) {
            int idx = (start + i) % replicas.size();
            try {
                return replicas.get(idx).get().uri(path, uriVariables)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtUtils.generateServiceToken(CALLER_NAME))
                        .retrieve()
                        .body(type);
            } catch (HttpClientErrorException.NotFound e) {
                throw new ResourceNotFoundException(notFoundMessage);
            } catch (HttpClientErrorException e) {
                // 401/403/400...: this is a configuration/contract problem, another replica will not fix it
                throw new ServiceUnavailableException(targetName + " rejected the request (" + e.getStatusCode() + ").", e);
            } catch (RestClientException e) {
                log.warn("{} replica {} failed for {}: {}", targetName, replicaUrls.get(idx), path, e.getMessage());
                last = e;
            }
        }
        throw new ServiceUnavailableException(targetName + " is currently unavailable.", last);
    }
}
