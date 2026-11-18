package com.thermogrid.auth.api;

import com.thermogrid.auth.domain.AppUser;
import com.thermogrid.auth.repo.AppUserRepository;
import com.thermogrid.auth.service.AuthService;
import com.thermogrid.auth.service.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 200) String password) {
        /** Never print the password. */
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + "]";
        }
    }

    public record UserView(String username, String role) {
    }

    public record LoginResponse(String token, String tokenType, long expiresInSeconds, UserView user) {
    }

    private final AuthService auth;
    private final JwtService jwt;
    private final AppUserRepository users;

    public AuthController(AuthService auth, JwtService jwt, AppUserRepository users) {
        this.auth = auth;
        this.jwt = jwt;
        this.users = users;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest req) {
        // The same 401 body for an unknown user and a wrong password, so the response never reveals which.
        return auth.authenticate(req.username(), req.password())
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(new LoginResponse(
                        jwt.issue(u.getUsername(), u.getRole()), "Bearer", jwt.ttlSeconds(),
                        new UserView(u.getUsername(), u.getRole().name()))))
                .orElseGet(() -> error("Invalid username or password"));
    }

    /** The token is verified here as well as at the gateway; the account must still exist and be enabled. */
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return error("Missing or invalid token");
        }
        return jwt.verify(authorization.substring(7).trim())
                .flatMap(v -> users.findByUsername(v.username()))
                .filter(AppUser::isEnabled)
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(new UserView(u.getUsername(), u.getRole().name())))
                .orElseGet(() -> error("Missing or invalid token"));
    }

    private static ResponseEntity<?> error(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("status", 401, "error", "UNAUTHORIZED", "message", message));
    }
}
