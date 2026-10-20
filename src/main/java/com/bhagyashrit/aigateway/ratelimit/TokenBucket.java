package com.bhagyashrit.aigateway.ratelimit;

/**
 * Classic token-bucket limiter for per-minute request quotas.
 *
 * <p>Thread-safe via a single intrinsic lock per bucket; buckets are cheap and
 * short-lived critical sections, so a lock is simpler and easier to reason
 * about than lock-free CAS loops here.
 */
public class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(int requestsPerMinute) {
        this.capacity = Math.max(1, requestsPerMinute);
        this.refillPerSecond = this.capacity / 60.0;
        this.tokens = this.capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Tries to consume one token. Returns true if allowed. */
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /**
     * Seconds until at least one token is available. Used for the
     * {@code Retry-After} header on 429 responses.
     */
    public synchronized long secondsUntilNextToken() {
        refill();
        if (tokens >= 1.0) {
            return 0;
        }
        return (long) Math.ceil((1.0 - tokens) / refillPerSecond);
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefillNanos = now;
        }
    }
}
