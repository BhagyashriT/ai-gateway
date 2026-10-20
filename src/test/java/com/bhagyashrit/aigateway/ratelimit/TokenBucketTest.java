package com.bhagyashrit.aigateway.ratelimit;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class TokenBucketTest {

    @Test
    void allowsUpToCapacityThenDenies() {
        TokenBucket bucket = new TokenBucket(3);
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertTrue(bucket.tryAcquire());
        assertFalse(bucket.tryAcquire());
    }

    @Test
    void retryAfterIsPositiveWhenEmpty() {
        TokenBucket bucket = new TokenBucket(60);
        for (int i = 0; i < 60; i++) {
            assertTrue(bucket.tryAcquire());
        }
        assertFalse(bucket.tryAcquire());
        assertTrue(bucket.secondsUntilNextToken() >= 1);
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        TokenBucket bucket = new TokenBucket(60); // capacity 60, refills 1/second
        for (int i = 0; i < 60; i++) {
            assertTrue(bucket.tryAcquire());
        }
        assertFalse(bucket.tryAcquire());
        Thread.sleep(1200); // ~1.2 tokens refill
        assertTrue(bucket.tryAcquire());
    }

    @Test
    void tinyQuotaStillAllowsOne() {
        TokenBucket bucket = new TokenBucket(0);
        assertTrue(bucket.tryAcquire());
    }
}
