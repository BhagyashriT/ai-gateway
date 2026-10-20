package com.bhagyashrit.aigateway.ratelimit;

import com.bhagyashrit.aigateway.tenant.Tenant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Enforces both quota dimensions per tenant:
 * <ul>
 *   <li>requests/minute via a {@link TokenBucket}, and</li>
 *   <li>estimated tokens/day via a counter that resets on UTC day rollover.</li>
 * </ul>
 *
 * <p>Token counts are estimates (see {@code TokenEstimator}); the daily cap is
 * checked against the estimated prompt tokens <em>before</em> the upstream
 * call and reconciled afterwards with the real usage.
 */
@Component
public class QuotaManager {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DailyCounter> dailyCounters = new ConcurrentHashMap<>();

    /** Checks the per-minute request quota. Throws if exceeded. */
    public void checkRequestQuota(Tenant tenant) {
        TokenBucket bucket = buckets.computeIfAbsent(tenant.apiKey(),
                k -> new TokenBucket(tenant.requestsPerMinute()));
        if (!bucket.tryAcquire()) {
            throw new QuotaExceededException(
                    "request quota exceeded for tenant '" + tenant.name() + "'",
                    Math.max(1, bucket.secondsUntilNextToken()));
        }
    }

    /**
     * Checks the daily token quota against estimated prompt tokens.
     * Throws if the estimate alone would exceed the cap.
     */
    public void checkTokenQuota(Tenant tenant, int estimatedPromptTokens) {
        DailyCounter counter = dailyCounters.computeIfAbsent(tenant.apiKey(), k -> new DailyCounter());
        if (!counter.tryReserve(tenant.tokensPerDay(), estimatedPromptTokens)) {
            long resetIn = secondsUntilUtcMidnight();
            throw new QuotaExceededException(
                    "daily token quota exceeded for tenant '" + tenant.name() + "'", resetIn);
        }
    }

    /**
     * Reconciles the reservation with actual usage after the call: the
     * estimate is replaced by prompt+completion tokens.
     */
    public void reconcile(Tenant tenant, int estimatedPromptTokens, int actualTotalTokens) {
        DailyCounter counter = dailyCounters.computeIfAbsent(tenant.apiKey(), k -> new DailyCounter());
        counter.reconcile(estimatedPromptTokens, actualTotalTokens);
    }

    private static long secondsUntilUtcMidnight() {
        var now = java.time.ZonedDateTime.now(ZoneOffset.UTC);
        var midnight = now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC);
        return java.time.Duration.between(now, midnight).getSeconds();
    }

    private static final class DailyCounter {
        private volatile LocalDate day = LocalDate.now(ZoneOffset.UTC);
        private final AtomicLong used = new AtomicLong();

        synchronized boolean tryReserve(long cap, int estimate) {
            rolloverIfNeeded();
            if (used.get() + estimate > cap) {
                return false;
            }
            used.addAndGet(estimate);
            return true;
        }

        synchronized void reconcile(int estimate, int actual) {
            rolloverIfNeeded();
            used.addAndGet((long) actual - estimate);
        }

        private void rolloverIfNeeded() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            if (!today.equals(day)) {
                day = today;
                used.set(0);
            }
        }
    }
}
