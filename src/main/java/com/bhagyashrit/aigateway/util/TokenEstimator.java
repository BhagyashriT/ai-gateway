package com.bhagyashrit.aigateway.util;

/**
 * Token counting for quotas and cost accounting.
 *
 * <p>Uses the common heuristic of ~4 characters per token. This is an
 * <em>estimate</em>: real tokenizers (tiktoken, SentencePiece) split text
 * differently. When an upstream provider reports real usage, the gateway
 * prefers that and only falls back to this estimate.
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    public static int estimateMessages(java.util.List<com.bhagyashrit.aigateway.model.ChatDtos.ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        int total = 0;
        for (var m : messages) {
            total += estimate(m.role()) + estimate(m.content());
        }
        // Small per-message framing overhead, mirroring the OpenAI cookbook's +3/message.
        return total + 3 * messages.size();
    }
}
