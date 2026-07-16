package ru.techdocs.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        var user = userRepository.findByEmail(request.email().trim().toLowerCase()).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Неверный email или пароль"));
        }
        return ResponseEntity.ok(Map.of(
                "token", jwtService.generateToken(user),
                "user", Map.of("id", user.getId(), "email", user.getEmail(), "role", user.getRole())
        ));
    }
}
