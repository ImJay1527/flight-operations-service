package pt.isep.sidis.flightops.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import pt.isep.sidis.flightops.cluster.Cluster;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * On a query instance (CQRS with separate processes, {@code flightops.query-only=true}) only queries are answered:
 * a command (POST / PATCH / PUT / DELETE) gets {@code 405 Method Not Allowed} with {@code Allow: GET, HEAD}. The load
 * balancer never sends one here; this is for a client that calls the instance directly. Login and the actuator stay
 * open (a user logs in wherever they are; the Postman resilience tests stop instances through the actuator).
 * Runs after Spring Security, so a request without a token still gets 401 first.
 */
@Component
@ConditionalOnProperty(name = "flightops.query-only", havingValue = "true")
public class QueryInstanceFilter extends OncePerRequestFilter {

    private static final Set<String> QUERIES = Set.of("GET", "HEAD", "OPTIONS");

    private final Cluster cluster;
    private final ObjectMapper json;

    public QueryInstanceFilter(Cluster cluster, ObjectMapper json) {
        this.cluster = cluster;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return QUERIES.contains(request.getMethod()) || path.equals("/api/auth/login") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setStatus(HttpStatus.METHOD_NOT_ALLOWED.value());
        response.setHeader(HttpHeaders.ALLOW, "GET, HEAD");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String commandInstances = String.join(", ", cluster.members().stream().map(Cluster.Member::name).toList());
        json.writeValue(response.getOutputStream(), Map.of("error",
                "This is a query instance (CQRS): it only answers queries. Send commands to the load balancer or to a "
                        + "command instance (" + commandInstances + ")."));
    }
}
