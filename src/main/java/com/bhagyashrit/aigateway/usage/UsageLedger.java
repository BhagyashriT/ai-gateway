package com.bhagyashrit.aigateway.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * Per-tenant, per-model token and cost ledger.
 *
 * <p>Costs are <em>estimates</em> from configured per-1k-token rates; token
 * counts are exact when the upstream reports usage and estimated otherwise.
 */
@Component
public class UsageLedger {

    public record ModelUsage(
            long requests,
            long promptTokens,
            long completionTokens,
            BigDecimal estimatedCostUsd) {}

    private static final class Counters {
        final LongAdder requests = new LongAdder();
        final LongAdder promptTokens = new LongAdder();
        final LongAdder completionTokens = new LongAdder();
    }

    /** tenant -> model -> counters */
    private final Map<String, Map<String, Counters>> ledger = new ConcurrentHashMap<>();
    /** "tenant|model" -> rate pair, for cost math */
    private final Map<String, BigDecimal[]> rates = new ConcurrentHashMap<>();

    public void record(String tenant, String model, int promptTokens, int completionTokens,
                       BigDecimal costPer1kPrompt, BigDecimal costPer1kCompletion) {
        Counters c = ledger.computeIfAbsent(tenant, t -> new ConcurrentHashMap<>())
                .computeIfAbsent(model, m -> new Counters());
        c.requests.increment();
        c.promptTokens.add(promptTokens);
        c.completionTokens.add(completionTokens);
        rates.put(tenant + "|" + model,
                new BigDecimal[]{costPer1kPrompt, costPer1kCompletion});
    }

    /** Usage for one tenant across all models it has used. */
    public Map<String, ModelUsage> forTenant(String tenant) {
        Map<String, ModelUsage> out = new ConcurrentHashMap<>();
        Map<String, Counters> perModel = ledger.getOrDefault(tenant, Map.of());
        for (var e : perModel.entrySet()) {
            String model = e.getKey();
            Counters c = e.getValue();
            BigDecimal[] rate = rates.getOrDefault(tenant + "|" + model,
                    new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal cost = rate[0].multiply(BigDecimal.valueOf(c.promptTokens.sum()))
                    .add(rate[1].multiply(BigDecimal.valueOf(c.completionTokens.sum())))
                    .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            out.put(model, new ModelUsage(c.requests.sum(), c.promptTokens.sum(),
                    c.completionTokens.sum(), cost));
        }
        return out;
    }
}
