package com.bluestaq.notes.config;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Atomically reserves per-source, per-account, and instance-wide request budgets before authentication.
 * Counters expire together, memory is bounded, and a full counter table rejects new identities rather
 * than evicting live counters. This limiter protects one process; replicas need a shared edge limiter.
 */
public final class RequestRateLimiter {
    private final SecurityLimits limits;
    private final Clock clock;
    private final Map<String, Integer> sources = new HashMap<>();
    private final Map<String, Integer> accounts = new HashMap<>();
    private long windowStarted;
    private int requests;

    /**
     * Creates independent counters using an injectable clock so expiration can be tested without sleeping.
     *
     * @param limits validated request and memory budgets
     * @param clock time source used to expire counters
     */
    public RequestRateLimiter(SecurityLimits limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
        windowStarted = clock.millis();
    }

    /**
     * Reserves all applicable budgets together; rejected requests never reset or increment account counters.
     * A successful login does not erase the budget, and blocks expire without permanently locking accounts.
     *
     * @param source actual socket peer address, independent of caller-controlled forwarding headers
     * @param username Basic username, or null if no valid Basic header was supplied
     * @return zero if admitted, otherwise the number of seconds until the current window expires
     */
    public synchronized long retryAfterSeconds(String source, String username) {
        long now = clock.millis();
        long windowMillis = limits.windowSeconds() * 1000L;
        if (now < windowStarted || now - windowStarted >= windowMillis) {
            sources.clear();
            accounts.clear();
            requests = 0;
            windowStarted = now;
        }
        int newIdentities = sources.containsKey(source) ? 0 : 1;
        if (username != null && !accounts.containsKey(username)) {
            newIdentities++;
        }
        if (requests >= limits.requestsPerWindow()
                || sources.getOrDefault(source, 0) >= limits.requestsPerIp()
                || (username != null && accounts.getOrDefault(username, 0) >= limits.requestsPerAccount())
                || sources.size() + accounts.size() + newIdentities > limits.maxTrackedIdentities()) {
            return Math.max(1, (windowMillis - (now - windowStarted) + 999) / 1000);
        }
        sources.merge(source, 1, Integer::sum);
        if (username != null) {
            accounts.merge(username, 1, Integer::sum);
        }
        requests++;
        return 0;
    }
}
