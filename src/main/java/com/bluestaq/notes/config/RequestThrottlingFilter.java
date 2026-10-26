package com.bluestaq.notes.config;

import com.bluestaq.notes.api.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies bounded request budgets before HTTP Basic can look up credentials or run BCrypt.
 * Only anonymous health checks bypass throttling; supplying credentials still consumes a budget.
 */
public final class RequestThrottlingFilter extends OncePerRequestFilter {
    private final RequestRateLimiter limiter;
    private final ObjectMapper mapper;
    private final BasicAuthenticationConverter converter = new BasicAuthenticationConverter();

    /**
     * Connects the security-chain filter to shared counters and the application's JSON error format.
     *
     * @param limiter instance-local request budgets
     * @param mapper serializer for client-safe errors
     */
    public RequestThrottlingFilter(RequestRateLimiter limiter, ObjectMapper mapper) {
        this.limiter = limiter;
        this.mapper = mapper;
    }

    /**
     * Keeps anonymous database-backed health probes available when API budgets are exhausted.
     *
     * @param request current HTTP request
     * @return true only for a credential-free GET to the health endpoint
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "GET".equals(request.getMethod())
                && (request.getContextPath() + "/health").equals(request.getRequestURI())
                && request.getHeader(HttpHeaders.AUTHORIZATION) == null;
    }

    /**
     * Resolves a Basic username without authenticating, then admits the request or returns JSON with Retry-After.
     * Malformed credentials still consume the source/global budgets and are rejected by the Basic filter.
     *
     * @param request current HTTP request
     * @param response HTTP response used for a throttling error
     * @param chain remaining filters, including actual password verification
     * @throws IOException if the response or downstream request cannot be read or written
     * @throws ServletException if downstream servlet processing fails
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws IOException, ServletException {
        String username = null;
        try {
            var credentials = converter.convert(request);
            if (credentials != null) {
                username = credentials.getName();
                credentials.eraseCredentials();
            }
        } catch (AuthenticationException ignored) {
            // BasicAuthenticationFilter supplies the existing generic 401 response for malformed credentials.
        }
        long retryAfter = limiter.retryAfterSeconds(request.getRemoteAddr(), username);
        if (retryAfter > 0) {
            response.setStatus(429);
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(),
                    new ApiError(429, "TOO_MANY_REQUESTS", "Request limit reached; retry later"));
            return;
        }
        chain.doFilter(request, response);
    }
}
