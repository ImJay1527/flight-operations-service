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
 * Audit trail: one line per request with who did what and the outcome.
 * Runs after the JWT filter so the authenticated principal is known. Logger name: AUDIT.
 */
@Component
public class AuditLogFilter extends OncePerRequestFilter {

    private static final Logger AUDIT = LoggerFactory.getLogger("AUDIT");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String user = auth != null ? auth.getName() : "anonymous";
            String roles = auth != null ? auth.getAuthorities().toString() : "[]";
            AUDIT.info("user={} roles={} method={} uri={} status={} remote={}",
                    user, roles, request.getMethod(), request.getRequestURI(), response.getStatus(), request.getRemoteAddr());
        }
    }
}
