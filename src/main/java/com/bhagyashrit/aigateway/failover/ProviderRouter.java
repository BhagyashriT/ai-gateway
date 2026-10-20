package com.bhagyashrit.aigateway.failover;

import com.bhagyashrit.aigateway.config.GatewayProperties;
import com.bhagyashrit.aigateway.model.ChatDtos.ChatCompletionRequest;
import com.bhagyashrit.aigateway.provider.ChatProvider;
import com.bhagyashrit.aigateway.provider.OpenAiCompatibleProvider;
import com.bhagyashrit.aigateway.provider.ProviderException;
import com.bhagyashrit.aigateway.provider.ProviderResult;
import com.bhagyashrit.aigateway.provider.StubProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Routes each request across the ordered provider list with retry and failover.
 *
 * <p>Per provider: up to {@value #MAX_ATTEMPTS} attempts with exponential
 * backoff plus jitter. Retryable failures (5xx/timeout/IO) move to the next
 * provider; non-retryable failures (4xx) fail fast. A circuit-breaker-lite
 * opens a provider's circuit after {@code circuitBreakerFailureThreshold}
 * consecutive failures for {@code circuitBreakerCooldownSeconds}, then allows
 * one trial request (half-open).
 */
@Component
public class ProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouter.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final long BASE_BACKOFF_MILLIS = 200;

    private final List<ChatProvider> providers;
    private final Map<String, CircuitState> circuits = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final long cooldownMillis;

    @org.springframework.beans.factory.annotation.Autowired
    public ProviderRouter(GatewayProperties properties) {
        this(buildProviders(properties),
                properties.getCircuitBreakerFailureThreshold(),
                properties.getCircuitBreakerCooldownSeconds() * 1000);
    }

    /** Package-visible for tests. */
    ProviderRouter(List<ChatProvider> providers, int failureThreshold, long cooldownMillis) {
        this.providers = List.copyOf(providers);
        this.failureThreshold = failureThreshold;
        this.cooldownMillis = cooldownMillis;
        for (ChatProvider p : providers) {
            circuits.put(p.name(), new CircuitState());
        }
    }

    private static List<ChatProvider> buildProviders(GatewayProperties properties) {
        List<ChatProvider> list = new ArrayList<>();
        for (GatewayProperties.ProviderConfig c : properties.getProviders()) {
            String name = c.getName() != null ? c.getName() : c.getType();
            if ("openai".equalsIgnoreCase(c.getType())) {
                list.add(new OpenAiCompatibleProvider(name, c.getBaseUrl(), c.getApiKey(), c.getModel()));
            } else {
                list.add(new StubProvider(name, c.getModel() != null ? c.getModel() : "stub-model"));
            }
        }
        if (list.isEmpty()) {
            list.add(new StubProvider("stub", "stub-model"));
        }
        return list;
    }

    public record RoutedResult(ProviderResult result, String providerName, int attempts) {}

    /** Thrown when every provider failed; carries the last error for the 502 body. */
    public static class AllProvidersFailedException extends Exception {
        private final String lastError;
        public AllProvidersFailedException(String lastError) {
            super("all providers failed: " + lastError);
            this.lastError = lastError;
        }
        public String getLastError() { return lastError; }
    }

    public RoutedResult route(ChatCompletionRequest request) throws AllProvidersFailedException, ProviderException {
        String lastError = "no providers configured";
        int attempts = 0;
        for (ChatProvider provider : providers) {
            CircuitState circuit = circuits.get(provider.name());
            if (circuit.isOpen(System.currentTimeMillis(), cooldownMillis)) {
                log.warn("provider_circuit_open provider={}", provider.name());
                lastError = provider.name() + ": circuit open";
                continue;
            }
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                attempts++;
                long start = System.nanoTime();
                try {
                    ProviderResult result = provider.complete(request);
                    circuit.recordSuccess();
                    return new RoutedResult(result, provider.name(), attempts);
                } catch (ProviderException e) {
                    long latencyMs = (System.nanoTime() - start) / 1_000_000;
                    log.warn("provider_call_failed provider={} attempt={} latencyMs={} retryable={} error={}",
                            provider.name(), attempt, latencyMs, e.isRetryable(), e.getMessage());
                    if (!e.isRetryable()) {
                        throw e; // 4xx: another provider would reject the same request
                    }
                    lastError = provider.name() + ": " + e.getMessage();
                    if (attempt < MAX_ATTEMPTS) {
                        sleepWithBackoff(attempt);
                    }
                }
            }
            circuit.recordFailure(failureThreshold);
        }
        throw new AllProvidersFailedException(lastError);
    }

    private static void sleepWithBackoff(int attempt) {
        long backoff = BASE_BACKOFF_MILLIS * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(0, 100);
        try {
            Thread.sleep(backoff + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Minimal circuit state: closed until {@code failureThreshold} consecutive failures. */
    static final class CircuitState {
        private int consecutiveFailures;
        private long openedAtMillis = -1;

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            openedAtMillis = -1;
        }

        synchronized void recordFailure(int threshold) {
            consecutiveFailures++;
            if (consecutiveFailures >= threshold) {
                openedAtMillis = System.currentTimeMillis();
            }
        }

        synchronized boolean isOpen(long nowMillis, long cooldownMillis) {
            if (openedAtMillis < 0) {
                return false;
            }
            if (nowMillis - openedAtMillis >= cooldownMillis) {
                // Half-open: allow one trial; a success closes it, a failure re-opens.
                openedAtMillis = -1;
                consecutiveFailures = Math.max(0, consecutiveFailures - 1);
                return false;
            }
            return true;
        }

        synchronized boolean isOpenForTest() { return openedAtMillis >= 0; }
    }
}
