package com.bhagyashrit.aigateway.provider;

import com.bhagyashrit.aigateway.model.ChatDtos.ChatCompletionRequest;
import com.bhagyashrit.aigateway.model.ChatDtos.ChatMessage;
import com.bhagyashrit.aigateway.util.TokenEstimator;

/**
 * Deterministic local provider used by default. Returns an extractive echo of
 * the last user message — no network, no API key — so tests, CI, and demos run
 * offline. Token counts are estimates (see {@link TokenEstimator}).
 */
public class StubProvider implements ChatProvider {

    private final String providerName;
    private final String model;

    public StubProvider(String providerName, String model) {
        this.providerName = providerName;
        this.model = model;
    }

    @Override
    public String name() { return providerName; }

    @Override
    public String model() { return model; }

    @Override
    public ProviderResult complete(ChatCompletionRequest request) {
        String lastUser = "";
        if (request.messages() != null) {
            for (int i = request.messages().size() - 1; i >= 0; i--) {
                ChatMessage m = request.messages().get(i);
                if ("user".equalsIgnoreCase(m.role())) {
                    lastUser = m.content() == null ? "" : m.content();
                    break;
                }
            }
        }
        String excerpt = lastUser.length() > 160 ? lastUser.substring(0, 160) + "…" : lastUser;
        String content = "[stub] Echoing your prompt (no upstream configured): \"" + excerpt + "\"";
        int promptTokens = TokenEstimator.estimateMessages(request.messages());
        int completionTokens = TokenEstimator.estimate(content);
        return new ProviderResult(content, model, promptTokens, completionTokens);
    }
}
