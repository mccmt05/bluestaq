package com.bluestaq.notes.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validates the bounded resource budgets used before request parsing and password verification.
 * Rate limits count successful and unsuccessful requests in the same fixed time window.
 *
 * @param maxRequestBytes largest accepted HTTP request body, in bytes
 * @param requestsPerIp requests allowed from one socket peer per window
 * @param requestsPerAccount Basic authentication attempts allowed for one username per window
 * @param requestsPerWindow total requests allowed by this application instance per window
 * @param windowSeconds duration of a rate-limit window
 * @param maxTrackedIdentities maximum combined source and account counters retained in memory
 */
@Validated
@ConfigurationProperties("notes.security")
public record SecurityLimits(
        @Min(1024) @Max(1048576) int maxRequestBytes,
        @Min(1) int requestsPerIp,
        @Min(1) int requestsPerAccount,
        @Min(1) int requestsPerWindow,
        @Min(1) @Max(3600) int windowSeconds,
        @Min(2) @Max(100000) int maxTrackedIdentities) {
}
