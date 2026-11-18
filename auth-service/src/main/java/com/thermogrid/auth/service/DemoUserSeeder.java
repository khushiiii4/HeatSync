package com.thermogrid.auth.service;

import com.thermogrid.auth.config.AuthProperties;
import com.thermogrid.auth.domain.AppUser;
import com.thermogrid.auth.domain.Role;
import com.thermogrid.auth.repo.AppUserRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Idempotent demo-user seeding from configuration. A user is created when missing; when it exists and the configured
 * password no longer matches, the stored hash is updated so changing .env takes effect. Passwords are never logged.
 */
@Component
public class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final AuthProperties props;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public DemoUserSeeder(AuthProperties props, AppUserRepository users, PasswordEncoder encoder, Clock clock) {
        this.props = props;
        this.users = users;
        this.encoder = encoder;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (AuthProperties.SeedUser s : props.seedUsers()) {
            if (s.username() == null || s.username().isBlank()) continue;
            boolean hasHash = s.passwordHash() != null && !s.passwordHash().isBlank();
            boolean hasPlain = s.password() != null && !s.password().isBlank();
            if (!hasHash && !hasPlain) {
                log.warn("Skipping seed user '{}': no password configured", s.username());
                continue;
            }
            Role role = Role.valueOf(s.role().trim().toUpperCase());
            AppUser existing = users.findByUsername(s.username()).orElse(null);
            if (existing == null) {
                String hash = hasHash ? s.passwordHash() : encoder.encode(s.password());
                users.save(new AppUser(s.username(), hash, role, clock.instant()));
                log.info("Seeded user '{}' with role {}", s.username(), role);
            } else {
                boolean same = hasHash ? existing.getPasswordHash().equals(s.passwordHash())
                        : encoder.matches(s.password(), existing.getPasswordHash());
                if (!same || existing.getRole() != role || !existing.isEnabled()) {
                    existing.setPasswordHash(hasHash ? s.passwordHash() : encoder.encode(s.password()));
                    existing.setRole(role);
                    existing.setEnabled(true);
                    users.save(existing);
                    log.info("Updated seeded user '{}' (role {})", s.username(), role);
                }
            }
        }
    }
}
