package com.moveinsync.config.server.security;

import com.moveinsync.config.server.web.dto.TokenRequest;
import com.moveinsync.config.server.web.dto.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private record AppUser(String passwordHash, Role role, String tenantId) {
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody TokenRequest request) {
        List<AppUser> users = jdbc.query("select password_hash, role, tenant_id from app_user where username = ?",
                (row, n) -> new AppUser(row.getString(1), Role.valueOf(row.getString(2)), row.getString(3)),
                request.username());
        if (users.isEmpty() || !passwordEncoder.matches(request.password(), users.get(0).passwordHash())) {
            throw new BadCredentialsException("unknown user or wrong password");
        }
        AppUser user = users.get(0);
        String token = jwtService.issue(request.username(), user.role(), user.tenantId());
        return new TokenResponse(token, user.role().name(), user.tenantId());
    }
}
