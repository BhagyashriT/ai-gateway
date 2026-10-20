package com.bhagyashrit.aigateway.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import com.bhagyashrit.aigateway.tenant.Tenant;
import org.junit.jupiter.api.Test;

class QuotaManagerTest {

    private final Tenant tenant = new Tenant("t", "key", 1000, 100);

    @Test
    void requestQuotaEnforced() {
        QuotaManager qm = new QuotaManager();
        Tenant small = new Tenant("s", "k2", 2, 1_000_000);
        qm.checkRequestQuota(small);
        qm.checkRequestQuota(small);
        QuotaExceededException e = assertThrows(QuotaExceededException.class,
                () -> qm.checkRequestQuota(small));
        assertTrue(e.getRetryAfterSeconds() >= 1);
    }

    @Test
    void tokenQuotaReservedThenReconciled() {
        QuotaManager qm = new QuotaManager();
        qm.checkTokenQuota(tenant, 60); // 60 of 100 used
        QuotaExceededException e = assertThrows(QuotaExceededException.class,
                () -> qm.checkTokenQuota(tenant, 50)); // 60 + 50 > 100
        assertTrue(e.getRetryAfterSeconds() > 0);
    }

    @Test
    void reconcileFreesOverestimatedReservation() {
        QuotaManager qm = new QuotaManager();
        qm.checkTokenQuota(tenant, 60);
        qm.reconcile(tenant, 60, 10); // actual was only 10
        // 10 used; 90 more fits
        qm.checkTokenQuota(tenant, 90);
    }

    @Test
    void reconcileAddsUnderestimatedUsage() {
        QuotaManager qm = new QuotaManager();
        qm.checkTokenQuota(tenant, 10);
        qm.reconcile(tenant, 10, 95); // actual 95
        assertThrows(QuotaExceededException.class, () -> qm.checkTokenQuota(tenant, 10));
    }
}
