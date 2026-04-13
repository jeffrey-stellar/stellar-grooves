package com.stellarideas.grooves.config;

import com.stellarideas.grooves.model.Role;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Creates an initial admin user on startup if no admin exists yet.
 * Reads credentials from environment variables or application properties:
 *   ADMIN_USERNAME (default: admin)
 *   ADMIN_PASSWORD (required — skipped if not set)
 *   ADMIN_EMAIL    (default: admin@stellargrooves.local)
 */
@Component
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Environment env;

    public AdminBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder, Environment env) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.env = env;
    }

    @Override
    public void run(String... args) {
        boolean adminExists = userRepository.findAll().stream()
                .anyMatch(u -> u.getRoles() != null && u.getRoles().contains(Role.ROLE_ADMIN));

        if (adminExists) {
            return;
        }

        String password = env.getProperty("ADMIN_PASSWORD");
        if (password == null || password.isBlank()) {
            logger.info("No admin user exists. Set ADMIN_PASSWORD env var to create one on startup.");
            return;
        }

        String username = env.getProperty("ADMIN_USERNAME", "admin");
        String email = env.getProperty("ADMIN_EMAIL", "admin@stellargrooves.local");

        if (userRepository.existsByUsername(username)) {
            // User exists but isn't admin — promote them
            User existing = userRepository.findByUsername(username).orElse(null);
            if (existing != null) {
                existing.getRoles().add(Role.ROLE_ADMIN);
                userRepository.save(existing);
                logger.info("Promoted existing user '{}' to admin", username);
            }
            return;
        }

        User admin = User.builder()
                .username(username)
                .email(email)
                .password(passwordEncoder.encode(password))
                .build();
        admin.setRoles(Set.of(Role.ROLE_USER, Role.ROLE_ADMIN));
        userRepository.save(admin);

        logger.info("Created initial admin user '{}'", username);
    }
}
