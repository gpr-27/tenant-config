package com.moveinsync.config.server.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    public record Caller(String subject, Role role, String tenantId) {
    }

    // services re-authenticate on a 401 through their tokenSupplier, so a long life costs them nothing
    private static final Duration HUMAN_TTL = Duration.ofHours(12);
    private static final Duration SERVICE_TTL = Duration.ofDays(30);

    private final SecretKey key;

    public JwtService(@Value("${config.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(String subject, Role role, String tenantId) {
        Instant now = Instant.now();
        Duration ttl = role == Role.SERVICE ? SERVICE_TTL : HUMAN_TTL;
        return Jwts.builder()
                .subject(subject)
                .claim("role", role.name())
                .claim("tenantId", tenantId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    // throws JwtException when the signature, expiry or claims are wrong
    public Caller parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        String role = claims.get("role", String.class);
        if (role == null) {
            throw new JwtException("token has no role");
        }
        return new Caller(claims.getSubject(), Role.valueOf(role), claims.get("tenantId", String.class));
    }
}
