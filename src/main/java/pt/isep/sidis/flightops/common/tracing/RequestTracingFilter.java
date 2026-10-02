package pt.isep.sidis.flightops.common.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Traces a request across instances (PL3 p.15 "structured logging to trace request flows across instances", p.19).
 *
 * <ul>
 *   <li>Every request gets a request id: the incoming {@code X-Request-Id} header if a caller (e.g. a peer instance)
 *       sent one, otherwise a new one. Outgoing calls to peers / other services pass it on
 *       ({@link RequestIdPropagation}), so one user request has the same id in the logs of every instance it touched.</li>
 *   <li>The instance name and the request id are put in the logging context (MDC), so every log line shows them:
 *       {@code INFO [instance1] [3f2a9c1e] ...}</li>
 *   <li>The response carries {@code X-Instance} (who answered) and {@code X-Request-Id}.</li>
 * </ul>
 * Runs before every other filter, including Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTracingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String INSTANCE_HEADER = "X-Instance";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_INSTANCE = "instance";

    private final String instanceName;

    public RequestTracingFilter(@Value("${flightops.instance-name}") String instanceName) {
        this.instanceName = instanceName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (!StringUtils.hasText(requestId) || requestId.length() > 64) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_INSTANCE, instanceName);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        response.setHeader(INSTANCE_HEADER, instanceName);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_INSTANCE);
        }
    }
}
