package com.bluestaq.notes.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks limiter expiration, bounded memory, atomic reservations, and admission before authentication work.
 * An injected clock makes boundary and recovery checks deterministic without delaying the test suite.
 */
class RequestRateLimiterTest {
    private final Clock clock = mock(Clock.class);

    /**
     * Makes the account budget follow a username across source changes without affecting other accounts.
     */
    @Test
    void accountBudgetCannotBeResetByChangingSources() {
        var limiter = new RequestRateLimiter(new SecurityLimits(1024, 10, 2, 100, 60, 100), clock);
        assertThat(limiter.retryAfterSeconds("one", "alice")).isZero();
        assertThat(limiter.retryAfterSeconds("two", "alice")).isZero();
        assertThat(limiter.retryAfterSeconds("three", "alice")).isEqualTo(60);
        assertThat(limiter.retryAfterSeconds("one", "bob")).isZero();
    }

    /**
     * Makes source limits cover credential-free requests and rotating usernames.
     */
    @Test
    void sourceBudgetCannotBeResetByChangingAccounts() {
        var limiter = new RequestRateLimiter(new SecurityLimits(1024, 2, 10, 100, 60, 100), clock);
        assertThat(limiter.retryAfterSeconds("one", null)).isZero();
        assertThat(limiter.retryAfterSeconds("one", "alice")).isZero();
        assertThat(limiter.retryAfterSeconds("one", "bob")).isEqualTo(60);
        assertThat(limiter.retryAfterSeconds("two", "bob")).isZero();
    }

    /**
     * Keeps a saturated identity table bounded without letting new identities evict active counters.
     */
    @Test
    void fullIdentityTableFailsClosedAndExpires() {
        var limiter = new RequestRateLimiter(new SecurityLimits(1024, 10, 10, 100, 60, 2), clock);
        assertThat(limiter.retryAfterSeconds("one", "alice")).isZero();
        assertThat(limiter.retryAfterSeconds("two", "bob")).isEqualTo(60);
        assertThat(limiter.retryAfterSeconds("one", "alice")).isZero();
        when(clock.millis()).thenReturn(60000L);
        assertThat(limiter.retryAfterSeconds("two", "bob")).isZero();
    }

    /**
     * Rounds Retry-After upward and restores access at the window boundary without a permanent lockout.
     */
    @Test
    void retryAfterCountsDownAndTheWindowRecovers() {
        var limiter = new RequestRateLimiter(new SecurityLimits(1024, 1, 1, 1, 60, 10), clock);
        assertThat(limiter.retryAfterSeconds("one", "alice")).isZero();
        when(clock.millis()).thenReturn(59999L);
        assertThat(limiter.retryAfterSeconds("two", "bob")).isEqualTo(1);
        when(clock.millis()).thenReturn(60000L);
        assertThat(limiter.retryAfterSeconds("two", "bob")).isZero();
    }

    /**
     * Prevents parallel requests with different identities from overspending the instance-wide budget.
     *
     * @throws Exception if a worker fails or synchronization exceeds its time limit
     */
    @Test
    void concurrentReservationsCannotOverspendTheGlobalBudget() throws Exception {
        var limiter = new RequestRateLimiter(new SecurityLimits(1024, 100, 100, 7, 60, 100), clock);
        var start = new CountDownLatch(1);
        var admitted = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<?>>();
            for (int index = 0; index < 30; index++) {
                String identity = Integer.toString(index);
                results.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    if (limiter.retryAfterSeconds(identity, identity) == 0) {
                        admitted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(10, TimeUnit.SECONDS);
            }
        }
        assertThat(admitted.get()).isEqualTo(7);
    }

    /**
     * Shows that throttled requests never enter the downstream chain containing password verification.
     * Malformed Basic headers consume the same source budget as other attempts.
     *
     * @throws Exception if the filter cannot process a simulated request
     */
    @Test
    void throttleStopsProcessingBeforeCredentialVerification() throws Exception {
        var limiter = new RequestRateLimiter(new SecurityLimits(1024, 1, 1, 10, 60, 100), clock);
        var filter = new RequestThrottlingFilter(limiter, new ObjectMapper());
        var downstream = new AtomicInteger();
        var malformed = request("/api/teams", "Basic !!!");
        filter.doFilter(malformed, new MockHttpServletResponse(), (req, res) -> downstream.incrementAndGet());
        var rejected = new MockHttpServletResponse();
        filter.doFilter(request("/api/teams", "Basic " + Base64.getEncoder().encodeToString(
                        "alice:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8))), rejected,
                (req, res) -> downstream.incrementAndGet());
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejected.getContentAsString()).doesNotContain("alice", "secret");
        assertThat(downstream.get()).isEqualTo(1);
    }

    /**
     * Exempts anonymous health checks while ensuring credentials sent to health cannot bypass the budget.
     *
     * @throws Exception if filter execution fails
     */
    @Test
    void healthWithCredentialsStillConsumesAuthenticationBudgets() throws Exception {
        var limiter = new RequestRateLimiter(new SecurityLimits(1024, 1, 1, 1, 60, 100), clock);
        var filter = new RequestThrottlingFilter(limiter, new ObjectMapper());
        var downstream = new AtomicInteger();
        filter.doFilter(request("/health", null), new MockHttpServletResponse(), (req, res) -> downstream.incrementAndGet());
        filter.doFilter(request("/api/teams", null), new MockHttpServletResponse(), (req, res) -> downstream.incrementAndGet());
        var rejected = new MockHttpServletResponse();
        filter.doFilter(request("/health", "Basic !!!"), rejected, (req, res) -> downstream.incrementAndGet());
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(downstream.get()).isEqualTo(2);
    }

    /**
     * Creates a socket-identified GET request without trusting forwarding headers.
     *
     * @param path exact servlet request URI
     * @param authorization optional authentication header
     * @return a request suitable for exercising the security filter directly
     */
    private MockHttpServletRequest request(String path, String authorization) {
        var request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("192.0.2.1");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }
}
