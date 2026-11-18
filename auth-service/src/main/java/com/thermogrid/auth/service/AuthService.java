package com.thermogrid.auth.service;

import com.thermogrid.auth.domain.AppUser;
import com.thermogrid.auth.repo.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    /** A valid BCrypt hash of a random value, compared when the username is unknown so timing does not reveal it. */
    private final String decoyHash;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public AuthService(AppUserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
        this.decoyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /** The user when the credentials are right and the account is enabled; empty otherwise (no reason given). */
    public Optional<AppUser> authenticate(String username, String password) {
        Optional<AppUser> found = users.findByUsername(username == null ? "" : username.trim());
        String hash = found.map(AppUser::getPasswordHash).orElse(decoyHash);
        boolean matches = encoder.matches(password == null ? "" : password, hash);
        return found.filter(u -> matches && u.isEnabled());
    }
}
