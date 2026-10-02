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
import pt.isep.sidis.flightops.resilience.CircuitOpenException;
import pt.isep.sidis.flightops.resilience.EndpointHealth;
import pt.isep.sidis.flightops.resilience.HealthRegistry;
import pt.isep.sidis.flightops.resilience.ResilientCaller;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base class for calling another service that runs as several instances.
 * Requests are spread round-robin over the instances whose circuit is not open; every call goes through
 * {@link ResilientCaller} (retry with backoff, circuit breaker). If an instance fails, the next one is tried.
 * A 404 is authoritative (that instance already asked its own peers) and is NOT retried elsewhere.
 * If no instance can be reached, the answer is 404 too (like peer forwarding, PL3 p.11), with a message
 * that says the service could not be reached.
 */
public abstract class ReplicatedServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ReplicatedServiceClient.class);

    public static final String CALLER_NAME = "flight-operations-service";

    private final List<Instance> instances = new ArrayList<>();
    private final JwtUtils jwtUtils;
    private final ResilientCaller caller;
    private final String targetName;
    private final AtomicInteger next = new AtomicInteger();

    private record Instance(EndpointHealth health, RestClient client) {
    }

    protected ReplicatedServiceClient(String targetName, List<String> baseUrls, JwtUtils jwtUtils,
                                      RestClient.Builder builder, HttpClientFactory httpClientFactory, Duration timeout,
                                      HealthRegistry registry, ResilientCaller caller) {
        this.targetName = targetName;
        this.jwtUtils = jwtUtils;
        this.caller = caller;
        ClientHttpRequestFactory factory = httpClientFactory.create(timeout);
        for (String url : baseUrls) {
            instances.add(new Instance(registry.register(targetName, url),
                    builder.clone().baseUrl(url).requestFactory(factory).build()));
        }
    }

    protected <T> T get(String path, Class<T> type, String notFoundMessage, Object... uriVariables) {
        if (instances.isEmpty()) {
            throw new ServiceUnavailableException(targetName + " is not configured.");
        }
        int start = Math.floorMod(next.getAndIncrement(), instances.size());
        for (int i = 0; i < instances.size(); i++) {
            Instance instance = instances.get((start + i) % instances.size());
            try {
                return caller.call(instance.health(), true, () -> instance.client().get().uri(path, uriVariables)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtUtils.generateServiceToken(CALLER_NAME))
                        .retrieve()
                        .body(type));
            } catch (HttpClientErrorException.NotFound e) {
                throw new ResourceNotFoundException(notFoundMessage);
            } catch (HttpClientErrorException e) {
                // 401/403/400...: a configuration/contract problem, another instance will not fix it
                throw new ServiceUnavailableException(targetName + " rejected the request (" + e.getStatusCode() + ").", e);
            } catch (CircuitOpenException e) {
                log.debug("{} instance {} skipped for {}: circuit open", targetName, instance.health().url(), path);
            } catch (RestClientException e) {
                log.warn("{} instance {} failed for {}: {}", targetName, instance.health().url(), path, e.getMessage());
            }
        }
        throw new ResourceNotFoundException(notFoundMessage + " (" + targetName + " could not be reached)");
    }
}
