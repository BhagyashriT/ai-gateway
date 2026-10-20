package com.bhagyashrit.aigateway.cache;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ResponseCacheTest {

    @Test
    void missThenHit() {
        ResponseCache cache = new ResponseCache();
        String key = cache.keyFor("m", "hello");
        assertTrue(cache.get(key).isEmpty());
        cache.put(key, "{\"ok\":true}", 60);
        assertEquals("{\"ok\":true}", cache.get(key).orElseThrow());
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void zeroTtlExpiresImmediately() {
        ResponseCache cache = new ResponseCache();
        String key = cache.keyFor("m", "hello");
        cache.put(key, "x", 0);
        assertTrue(cache.get(key).isEmpty());
    }

    @Test
    void keyIsStableAndModelScoped() {
        ResponseCache cache = new ResponseCache();
        assertEquals(cache.keyFor("m", "p"), cache.keyFor("m", "p"));
        assertNotEquals(cache.keyFor("m1", "p"), cache.keyFor("m2", "p"));
        assertNotEquals(cache.keyFor("m", "p1"), cache.keyFor("m", "p2"));
        assertEquals(64, cache.keyFor("m", "p").length()); // SHA-256 hex
    }
}
