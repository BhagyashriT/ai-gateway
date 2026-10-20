package com.bhagyashrit.aigateway.provider;

import com.bhagyashrit.aigateway.model.ChatDtos.ChatCompletionRequest;
import com.bhagyashrit.aigateway.model.ChatDtos.ChatCompletionResponse;
import com.bhagyashrit.aigateway.util.TokenEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Calls any OpenAI-compatible HTTP API ({@code POST {baseUrl}/v1/chat/completions}).
 *
 * <p>Uses {@link java.net.http.HttpClient} directly (rather than Spring's
 * RestClient) so the provider stays a plain, easily unit-testable class with
 * explicit timeout handling. Timeouts and 5xx are retryable; 4xx are not.
 */
public class OpenAiCompatibleProvider implements ChatProvider {

    private final String providerName;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiCompatibleProvider(String providerName, String baseUrl, String apiKey, String model) {
        this(providerName, baseUrl, apiKey, model,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .build());
    }

    /** Package-visible for tests to inject a stubbed client. */
    OpenAiCompatibleProvider(String providerName, String baseUrl, String apiKey, String model, HttpClient httpClient) {
        this.providerName = providerName;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = httpClient;
    }

    @Override
    public String name() { return providerName; }

    @Override
    public String model() { return model; }

    @Override
    public ProviderResult complete(ChatCompletionRequest request) throws ProviderException {
        String requestModel = request.model() != null ? request.model() : model;
        String body;
        try {
            // Forward the request with our configured model so tenants can't pick arbitrary models.
            var forward = new ChatCompletionRequest(requestModel, request.messages(),
                    request.temperature(), request.maxTokens(), false);
            body = mapper.writeValueAsString(forward);
        } catch (IOException e) {
            throw new ProviderException("failed to serialize request", 500, false, e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ProviderException("upstream timed out", 504, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("upstream call interrupted", 502, true, e);
        } catch (IOException e) {
            throw new ProviderException("upstream unreachable: " + e.getMessage(), 502, true, e);
        }

        int status = response.statusCode();
        if (status >= 500) {
            throw new ProviderException("upstream returned " + status, 502, true);
        }
        if (status == 429) {
            throw new ProviderException("upstream rate limited", 502, true);
        }
        if (status >= 400) {
            throw new ProviderException("upstream rejected request (" + status + ")", 502, false);
        }

        try {
            ChatCompletionResponse parsed = mapper.readValue(response.body(), ChatCompletionResponse.class);
            if (parsed.choices() == null || parsed.choices().isEmpty()) {
                throw new ProviderException("upstream returned no choices", 502, true);
            }
            String content = parsed.choices().get(0).message().content();
            int promptTokens = parsed.usage() != null
                    ? parsed.usage().promptTokens()
                    : TokenEstimator.estimateMessages(request.messages());
            int completionTokens = parsed.usage() != null
                    ? parsed.usage().completionTokens()
                    : TokenEstimator.estimate(content);
            return new ProviderResult(content, requestModel, promptTokens, completionTokens);
        } catch (IOException e) {
            throw new ProviderException("could not parse upstream response", 502, true, e);
        }
    }
}
