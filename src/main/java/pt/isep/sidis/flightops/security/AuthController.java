package pt.isep.sidis.flightops.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import pt.isep.sidis.flightops.common.security.JwtUtils;

@Tag(name = "Authentication", description = "Login endpoint - returns a JWT accepted by every AISafe service")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtUtils jwtUtils;
    private final SystemUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(JwtUtils jwtUtils, SystemUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Operation(summary = "Login", description = "Authenticates a user and returns a JWT token.")
    @PostMapping("/login")
    public ResponseEntity<JwtResponseDTO> login(@Valid @RequestBody AuthRequestDTO request) {
        SystemUser user = userRepository.findByUsername(request.getUsername())
                .filter(u -> passwordEncoder.matches(request.getPassword(), u.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials."));

        return ResponseEntity.ok(new JwtResponseDTO(jwtUtils.generateToken(user.getUsername(), user.getRoles())));
    }
}
