package pt.isep.sidis.flightops.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Audit trail (logger AUDIT): one line per request - who, what, outcome, duration. Runs after the JWT filter so the
 * authenticated user is known.
 */
@Component
public class AuditLogFilter extends OncePerRequestFilter {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // health checks every few seconds would flood the audit log
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String user = auth != null ? auth.getName() : "anonymous";
            String roles = auth != null ? auth.getAuthorities().toString() : "[]";
            AUDIT.info("user={} roles={} method={} uri={} status={} durationMs={} remote={}",
                    user, roles, request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs,
                    request.getRemoteAddr());
        }
    }
}
