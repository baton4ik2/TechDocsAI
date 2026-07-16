package ru.techdocs.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import ru.techdocs.config.AppProperties;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    @Override
    public void run(String... args) {
        String email = props.auth().adminEmail().trim().toLowerCase();
        if (userRepository.findByEmail(email).isEmpty()) {
            User user = new User();
            user.setOrganizationId(1L);
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode(props.auth().adminPassword()));
            user.setRole("ADMIN");
            userRepository.save(user);
            log.info("Создан администратор: {}", email);
        }
    }
}
