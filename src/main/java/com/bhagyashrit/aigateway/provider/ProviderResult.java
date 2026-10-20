package com.bhagyashrit.aigateway.provider;

/** Result of a single provider call. */
public record ProviderResult(
        String content,
        String modelUsed,
        int promptTokens,
        int completionTokens) {}
