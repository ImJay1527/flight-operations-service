package pt.isep.sidis.flightops.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

/**
 * Validates and issues HS256 JWTs. All three services share the same secret (env JWT_SECRET),
 * so a token issued by one service is accepted by the others.
 * Human users carry roles such as ATCC; service-to-service calls carry the role SERVICE.
 */
@Component
public class JwtUtils {

    public static final String SERVICE_ROLE = "SERVICE";

    @Value("${sidis.jwt.secret}")
    private String jwtSecret;

    @Value("${sidis.jwt.expiration-ms}")
    private long jwtExpirationMs;

    private Key key() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes());
    }

    public String generateToken(String subject, String roles) {
        Date now = new Date();
        return Jwts.builder()
                .setSubject(subject)
                .claim("role", roles)
                .setIssuedAt(now)
                .setExpiration(new Date(now.getTime() + jwtExpirationMs))
                .signWith(key(), SignatureAlgorithm.HS256)
                .compact();
    }

    /** Token a service uses to call another service's /internal/** endpoints. */
    public String generateServiceToken(String serviceName) {
        return generateToken(serviceName, SERVICE_ROLE);
    }

    public String getSubject(String token) {
        return Jwts.parserBuilder().setSigningKey(key()).build().parseClaimsJws(token).getBody().getSubject();
    }

    public String getRoles(String token) {
        return Jwts.parserBuilder().setSigningKey(key()).build().parseClaimsJws(token).getBody().get("role", String.class);
    }

    public boolean isValid(String token) {
        try {
            Jwts.parserBuilder().setSigningKey(key()).build().parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
