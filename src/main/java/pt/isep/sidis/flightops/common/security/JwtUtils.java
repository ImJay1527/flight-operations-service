package pt.isep.sidis.flightops.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and validates HS256 JWTs. All three services share the secret (JWT_SECRET), so a token issued by one is
 * accepted by the others. Users carry roles such as ATCC; service-to-service calls carry the role SERVICE.
 *
 * <p>The parser is built once (jjwt scans for services every time one is created, which is slow) and service tokens
 * are reused until shortly before they expire.
 */
@Component
public class JwtUtils {

    public static final String SERVICE_ROLE = "SERVICE";

    /** Service tokens live 5 minutes and are renewed when less than 1 minute is left. */
    private static final long SERVICE_TOKEN_TTL_MS = 5 * 60_000;
    private static final long SERVICE_TOKEN_RENEW_BEFORE_MS = 60_000;

    private final Key key;
    private final JwtParser parser;
    private final long jwtExpirationMs;
    private final Map<String, CachedToken> serviceTokens = new ConcurrentHashMap<>();

    public JwtUtils(@Value("${sidis.jwt.secret}") String jwtSecret,
                    @Value("${sidis.jwt.expiration-ms}") long jwtExpirationMs) {
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        this.parser = Jwts.parserBuilder().setSigningKey(key).build();
        this.jwtExpirationMs = jwtExpirationMs;
    }

    public String generateToken(String subject, String roles) {
        return generateToken(subject, roles, jwtExpirationMs);
    }

    private String generateToken(String subject, String roles, long ttlMs) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(subject)
                .claim("role", roles)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + ttlMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** Token a service uses to call another service's /internal/** endpoints (cached, short-lived). */
    public String generateServiceToken(String serviceName) {
        long now = System.currentTimeMillis();
        CachedToken cached = serviceTokens.get(serviceName);
        if (cached == null || cached.expiresAt - now < SERVICE_TOKEN_RENEW_BEFORE_MS) {
            cached = new CachedToken(generateToken(serviceName, SERVICE_ROLE, SERVICE_TOKEN_TTL_MS), now + SERVICE_TOKEN_TTL_MS);
            serviceTokens.put(serviceName, cached);
        }
        return cached.token;
    }

    /** Verifies the signature and expiry; empty if the token is invalid. */
    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(parser.parseClaimsJws(token).getBody());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean isValid(String token) {
        return parse(token).isPresent();
    }

    private record CachedToken(String token, long expiresAt) {
    }
}
