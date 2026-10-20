package com.bhagyashrit.aigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** OpenAI-compatible request/response DTOs. Only the fields the gateway needs are modeled. */
public final class ChatDtos {

    private ChatDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessage(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatCompletionRequest(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens,
            Boolean stream) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatCompletionResponse(
            String id,
            String object,
            long created,
            String model,
            List<Choice> choices,
            TokenUsage usage) {

        public record Choice(int index, ChatMessage message, @JsonProperty("finish_reason") String finishReason) {}
    }

    public record TokenUsage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {}

    public record ErrorBody(ErrorDetail error) {
        public record ErrorDetail(String message, String type, String code) {}
    }
}
