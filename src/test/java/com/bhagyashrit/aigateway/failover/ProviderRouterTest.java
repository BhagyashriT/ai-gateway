package com.bhagyashrit.aigateway.failover;

import static org.junit.jupiter.api.Assertions.*;
import com.bhagyashrit.aigateway.model.ChatDtos.ChatCompletionRequest;
import com.bhagyashrit.aigateway.provider.ChatProvider;
import com.bhagyashrit.aigateway.provider.ProviderException;
import com.bhagyashrit.aigateway.provider.ProviderResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Router behavior with scripted fake providers — no network. */
class ProviderRouterTest {

    private static final ChatCompletionRequest REQ =
            new ChatCompletionRequest("m", List.of(), null, null, false);

    /** Fails {@code failuresBeforeSuccess} times with a retryable error, then succeeds. */
    static class ScriptedProvider implements ChatProvider {
        final String name;
        final AtomicInteger calls = new AtomicInteger();
        final int failuresBeforeSuccess;
        final boolean retryable;

        ScriptedProvider(String name, int failuresBeforeSuccess, boolean retryable) {
            this.name = name;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.retryable = retryable;
        }

        @Override public String name() { return name; }
        @Override public String model() { return "m"; }

        @Override
        public ProviderResult complete(ChatCompletionRequest request) throws ProviderException {
            calls.incrementAndGet();
            if (calls.get() <= failuresBeforeSuccess) {
                throw new ProviderException("boom", retryable ? 500 : 400, retryable);
            }
            return new ProviderResult("ok-from-" + name, "m", 1, 1);
        }
    }

    @Test
    void usesFirstHealthyProvider() throws Exception {
        var router = new ProviderRouter(List.of(
                new ScriptedProvider("p1", 0, true),
                new ScriptedProvider("p2", 0, true)), 5, 60_000);
        var routed = router.route(REQ);
        assertEquals("p1", routed.providerName());
        assertEquals("ok-from-p1", routed.result().content());
    }

    @Test
    void retriesThenFailsOver() throws Exception {
        var p1 = new ScriptedProvider("p1", 10, true); // always fails (retryable)
        var p2 = new ScriptedProvider("p2", 0, true);
        var router = new ProviderRouter(List.of(p1, p2), 5, 60_000);
        var routed = router.route(REQ);
        assertEquals("p2", routed.providerName());
        assertEquals(2, p1.calls.get()); // MAX_ATTEMPTS retries on p1
        assertEquals(1, p2.calls.get());
    }

    @Test
    void nonRetryableErrorFailsFastWithoutFailover() {
        var p1 = new ScriptedProvider("p1", 1, false); // 4xx-style
        var p2 = new ScriptedProvider("p2", 0, true);
        var router = new ProviderRouter(List.of(p1, p2), 5, 60_000);
        assertThrows(ProviderException.class, () -> router.route(REQ));
        assertEquals(1, p1.calls.get());
        assertEquals(0, p2.calls.get()); // never tried
    }

    @Test
    void allFailingThrowsAllProvidersFailed() {
        var router = new ProviderRouter(List.of(
                new ScriptedProvider("p1", 10, true),
                new ScriptedProvider("p2", 10, true)), 5, 60_000);
        assertThrows(ProviderRouter.AllProvidersFailedException.class, () -> router.route(REQ));
    }

    @Test
    void circuitOpensAfterThresholdAndSkipsProvider() throws Exception {
        var p1 = new ScriptedProvider("p1", 100, true);
        var p2 = new ScriptedProvider("p2", 0, true);
        var router = new ProviderRouter(List.of(p1, p2), 2, 60_000);
        // Two route() calls, each exhausting p1's attempts -> circuit opens.
        router.route(REQ);
        router.route(REQ);
        int callsAfterOpen = p1.calls.get();
        // Third call must skip p1 entirely (circuit open).
        var routed = router.route(REQ);
        assertEquals("p2", routed.providerName());
        assertEquals(callsAfterOpen, p1.calls.get());
    }
}
