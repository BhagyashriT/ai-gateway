package com.bhagyashrit.aigateway.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.bhagyashrit.aigateway.model.ChatDtos.ChatCompletionRequest;
import com.bhagyashrit.aigateway.model.ChatDtos.ChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class StubProviderTest {

    @Test
    void echoesLastUserMessageDeterministically() throws Exception {
        var p = new StubProvider("stub", "stub-model");
        var req = new ChatCompletionRequest("stub-model", List.of(
                new ChatMessage("system", "be helpful"),
                new ChatMessage("user", "first"),
                new ChatMessage("user", "second question")), null, null, false);
        var r1 = p.complete(req);
        var r2 = p.complete(req);
        assertEquals(r1.content(), r2.content());
        assertTrue(r1.content().contains("second question"));
        assertTrue(r1.promptTokens() > 0);
        assertTrue(r1.completionTokens() > 0);
    }

    @Test
    void handlesEmptyMessages() throws Exception {
        var p = new StubProvider("stub", "stub-model");
        var r = p.complete(new ChatCompletionRequest("m", List.of(), null, null, false));
        assertNotNull(r.content());
    }
}
