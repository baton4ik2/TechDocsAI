package ru.techdocs;

import org.junit.jupiter.api.Test;
import ru.techdocs.auth.JwtService;
import ru.techdocs.auth.User;
import ru.techdocs.config.AppProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private AppProperties props(String secret, int minutes) {
        return new AppProperties(
                new AppProperties.Auth("admin@x", "pass", secret, minutes),
                null, null, null, null, null, null);
    }

    private User user() {
        User u = new User();
        u.setId(42L);
        u.setEmail("admin@techdocs.local");
        u.setRole("ADMIN");
        return u;
    }

    @Test
    void roundTripsEmail() {
        JwtService jwt = new JwtService(props("a-very-long-secret-key-for-hmac-sha-signing-32+", 60));
        String token = jwt.generateToken(user());
        assertThat(jwt.extractEmail(token)).isEqualTo("admin@techdocs.local");
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        JwtService issuer = new JwtService(props("secret-number-one-secret-number-one-secret-1", 60));
        JwtService other = new JwtService(props("secret-number-two-secret-number-two-secret-2", 60));
        String token = issuer.generateToken(user());
        assertThatThrownBy(() -> other.extractEmail(token)).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsExpiredToken() {
        JwtService jwt = new JwtService(props("a-very-long-secret-key-for-hmac-sha-signing-32+", -1));
        String token = jwt.generateToken(user());
        assertThatThrownBy(() -> jwt.extractEmail(token)).isInstanceOf(Exception.class);
    }

    @Test
    void rejectsGarbage() {
        JwtService jwt = new JwtService(props("a-very-long-secret-key-for-hmac-sha-signing-32+", 60));
        assertThatThrownBy(() -> jwt.extractEmail("not.a.jwt")).isInstanceOf(Exception.class);
    }
}
