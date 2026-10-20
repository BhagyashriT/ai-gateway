package com.bhagyashrit.aigateway.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Hand-rolled operational metrics: dependency-free counters plus latency
 * sum/max for averages. p50/p95 are intentionally not tracked — that is what
 * Micrometer/Prometheus is for in production (see README trade-offs).
 */
@Component
public class MetricsRegistry {

    private final LongAdder requests = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder upstreamErrors = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder guardrailBlocks = new LongAdder();
    private final LongAdder authFailures = new LongAdder();
    private final LongAdder upstreamLatencyTotalMs = new LongAdder();
    private final LongAdder upstreamCalls = new LongAdder();
    private final java.util.concurrent.atomic.AtomicLong maxUpstreamLatencyMs = new java.util.concurrent.atomic.AtomicLong();
    private final Map<String, LongAdder> requestsByProvider = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> errorsByProvider = new ConcurrentHashMap<>();

    public void request() { requests.increment(); }
    public void cacheHit() { cacheHits.increment(); }
    public void upstreamError(String provider) {
        upstreamErrors.increment();
        errorsByProvider.computeIfAbsent(provider, k -> new LongAdder()).increment();
    }
    public void rateLimited() { rateLimited.increment(); }
    public void guardrailBlock() { guardrailBlocks.increment(); }
    public void authFailure() { authFailures.increment(); }
    public void providerCall(String provider, long latencyMs) {
        upstreamCalls.increment();
        upstreamLatencyTotalMs.add(latencyMs);
        maxUpstreamLatencyMs.accumulateAndGet(latencyMs, Math::max);
        requestsByProvider.computeIfAbsent(provider, k -> new LongAdder()).increment();
    }

    public Map<String, Object> snapshot() {
        long calls = upstreamCalls.sum();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("requests_total", requests.sum());
        m.put("cache_hits", cacheHits.sum());
        double hitRate = requests.sum() == 0 ? 0.0 : (double) cacheHits.sum() / requests.sum();
        m.put("cache_hit_rate", Math.round(hitRate * 10000.0) / 10000.0);
        m.put("upstream_errors_total", upstreamErrors.sum());
        m.put("rate_limited_429_total", rateLimited.sum());
        m.put("guardrail_blocks_total", guardrailBlocks.sum());
        m.put("auth_failures_total", authFailures.sum());
        m.put("avg_upstream_latency_ms", calls == 0 ? 0 : upstreamLatencyTotalMs.sum() / calls);
        m.put("max_upstream_latency_ms", maxUpstreamLatencyMs.get());
        m.put("requests_by_provider", sumMap(requestsByProvider));
        m.put("errors_by_provider", sumMap(errorsByProvider));
        return m;
    }

    private static Map<String, Long> sumMap(Map<String, LongAdder> in) {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }
}
