package com.bhagyashrit.aigateway.ratelimit;

/** Thrown when a tenant exceeds a quota; carries the Retry-After value. */
public class QuotaExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public QuotaExceededException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
