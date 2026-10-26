package com.bluestaq.notes.config;

import com.bluestaq.notes.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configures database-backed HTTP Basic authentication for the stateless JSON API.
 * Only the health endpoint permits anonymous access; services enforce team membership separately.
 * This setup targets explicit Authorization-header clients and requires HTTPS outside the local demo.
 */
@Configuration
public class SecurityConfiguration {
    /**
     * Supplies BCrypt verification for the password hashes stored in {@code app_user}.
     *
     * @return the encoder used by Spring Security to check presented passwords
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Looks up authentication credentials by username without exposing password hashes through the API.
     * Every known account receives the same authority; team permissions come from membership checks.
     *
     * @param jdbc client used to read the stored username and password hash
     * @return an account lookup that rejects unknown usernames with a generic credentials error
     */
    @Bean
    UserDetailsService userDetailsService(JdbcClient jdbc) {
        return username -> jdbc.sql("SELECT username, password_hash FROM app_user WHERE username = :username")
                .param("username", username)
                .query((row, index) -> User.withUsername(row.getString("username"))
                        .password(row.getString("password_hash"))
                        .authorities("USER").build())
                .optional()
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    /**
     * Requires Basic credentials for API requests and writes JSON authentication and access errors.
     * Sessions, request caching, browser form login, logout, and CSRF checks are disabled for this
     * explicit-header client model; a future browser frontend must revisit that configuration.
     *
     * @param http Spring Security's filter-chain builder
     * @param mapper shared JSON mapper for security error responses
     * @return the filter chain guarding application requests
     * @throws Exception if Spring Security cannot construct the filter chain
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setStatus(401);
            response.setHeader("WWW-Authenticate", "Basic realm=\"team-notes\"");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),
                    new ApiError(401, "UNAUTHORIZED", "Valid credentials are required"));
        };

        return http
                // This is a stateless API for explicit Authorization-header clients, not browser forms.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthorized))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            mapper.writeValue(response.getOutputStream(),
                                    new ApiError(403, "FORBIDDEN", "Access denied"));
                        }))
                .build();
    }
}
