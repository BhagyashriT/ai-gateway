package com.bhagyashrit.aigateway.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Exact-prompt response cache with TTL.
 *
 * <p>The cache key is SHA-256 over (model + canonical message list), so only
 * byte-identical prompts hit. This is deliberately conservative: semantic
 * caching is a stated non-goal (see README limitations). In-memory; the README
 * documents Redis as the production swap.
 */
@Component
public class ResponseCache {

    private record Entry(String body, long expiresAtMillis) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.LongAdder hits = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder misses = new java.util.concurrent.atomic.LongAdder();

    public String keyFor(String model, String canonicalMessages) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(model.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(canonicalMessages.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Returns the cached body, or empty on miss/expiry. */
    public java.util.Optional<String> get(String key) {
        Entry e = store.get(key);
        if (e == null) {
            misses.increment();
            return java.util.Optional.empty();
        }
        if (System.currentTimeMillis() > e.expiresAtMillis()) {
            store.remove(key, e);
            misses.increment();
            return java.util.Optional.empty();
        }
        hits.increment();
        return java.util.Optional.of(e.body());
    }

    public void put(String key, String body, long ttlSeconds) {
        long now = System.currentTimeMillis();
        // A non-positive TTL means "already expired" — stored only so the
        // expiry path (not a silent no-op) is exercised.
        long expiresAt = ttlSeconds <= 0 ? now - 1 : now + ttlSeconds * 1000;
        store.put(key, new Entry(body, expiresAt));
        // Opportunistic cleanup so the map can't grow forever on a long-lived instance.
        if (store.size() % 1024 == 0) {
            evictExpired();
        }
    }

    public void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(en -> now > en.getValue().expiresAtMillis());
    }

    public long hits() { return hits.sum(); }
    public long misses() { return misses.sum(); }
    public int size() { return store.size(); }
}
