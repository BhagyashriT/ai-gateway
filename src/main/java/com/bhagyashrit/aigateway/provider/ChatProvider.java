package com.bhagyashrit.aigateway.provider;

import com.bhagyashrit.aigateway.model.ChatDtos.ChatCompletionRequest;

/** A backend LLM that can serve a chat completion. */
public interface ChatProvider {

    /** Stable name used in metrics, the X-Provider header, and the usage ledger. */
    String name();

    /**
     * Serves one completion.
     *
     * @throws ProviderException on any upstream failure; {@link ProviderException#isRetryable()}
     *         tells the router whether failing over to the next provider makes sense.
     */
    ProviderResult complete(ChatCompletionRequest request) throws ProviderException;

    /** The model identifier this provider serves. */
    String model();
}
