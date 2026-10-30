package com.moveinsync.config.server.security;

import com.moveinsync.config.server.web.ApiExceptionHandler;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.HeaderWriterFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService, ApiExceptionHandler errors)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // by default the security headers are written when the filter chain unwinds. For the SSE stream
                // that is after the handler returned, while a push thread may already be writing the body, and
                // Tomcat does not take two writers on one response. Writing them up front removes the race.
                .headers(headers -> headers.withObjectPostProcessor(new ObjectPostProcessor<HeaderWriterFilter>() {
                    @Override
                    public <O extends HeaderWriterFilter> O postProcess(O filter) {
                        filter.setShouldWriteHeadersEagerly(true);
                        return filter;
                    }
                }))
                .authorizeHttpRequests(requests -> requests
                        // the SSE stream finishes on an async dispatch that carries no token of its own
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) ->
                                errors.writeProblem(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                                        "a valid bearer token is required"))
                        .accessDeniedHandler((request, response, e) ->
                                errors.writeProblem(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                        "your role may not do this")))
                .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
