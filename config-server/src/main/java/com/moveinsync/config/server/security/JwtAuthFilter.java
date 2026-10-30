package com.moveinsync.config.server.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// not a @Component: Boot would register it a second time as a plain servlet filter
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            JwtService.Caller caller = jwtService.parse(header.substring("Bearer ".length()));
            var authentication = new UsernamePasswordAuthenticationToken(caller.subject(), null,
                    List.of(new SimpleGrantedAuthority(caller.role().authority())));
            authentication.setDetails(caller.tenantId());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            MDC.put("actor", caller.subject());
            if (caller.tenantId() != null) {
                MDC.put("tenantId", caller.tenantId());
            }
        } catch (JwtException | IllegalArgumentException e) {
            // left unauthenticated: the entry point answers 401
            SecurityContextHolder.clearContext();
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("actor");
            MDC.remove("tenantId");
        }
    }
}
