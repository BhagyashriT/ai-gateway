package com.bhagyashrit.aigateway.provider;

/**
 * A provider-side failure.
 *
 * <p>{@code retryable=true} covers 5xx, timeouts, and connection errors —
 * failures where trying the next provider may help. 4xx responses are
 * non-retryable: another provider would reject the same request.
 */
public class ProviderException extends Exception {

    private final int statusCode;
    private final boolean retryable;

    public ProviderException(String message, int statusCode, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public ProviderException(String message, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int getStatusCode() { return statusCode; }
    public boolean isRetryable() { return retryable; }
}
