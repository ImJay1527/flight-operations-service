package pt.isep.sidis.flightops.security;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A user that can log in via /api/auth/login. This service is the identity provider for the whole system:
 * the JWT it issues is accepted by the other services (shared signing secret).
 */
@Entity
@Table(name = "\"system_user\"")
@Getter
@NoArgsConstructor
public class SystemUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    /** Comma-separated roles, e.g. "ATCC" or "ADMIN,BACKOFFICE_OPERATOR,ATCC". */
    @Column(nullable = false)
    private String roles;

    public SystemUser(String username, String passwordHash, String roles) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username cannot be blank.");
        if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("Password hash cannot be blank.");
        if (roles == null || roles.isBlank()) throw new IllegalArgumentException("Roles cannot be blank.");
        this.username = username;
        this.passwordHash = passwordHash;
        this.roles = roles;
    }
}
