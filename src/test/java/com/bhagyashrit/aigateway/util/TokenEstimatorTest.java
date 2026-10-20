package com.bhagyashrit.aigateway.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import com.bhagyashrit.aigateway.model.ChatDtos.ChatMessage;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void emptyAndNullAreZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void roughlyFourCharsPerToken() {
        assertEquals(1, TokenEstimator.estimate("a"));
        assertEquals(1, TokenEstimator.estimate("abcd"));
        assertEquals(2, TokenEstimator.estimate("abcde"));
        assertEquals(25, TokenEstimator.estimate("x".repeat(100)));
    }

    @Test
    void messagesIncludeRoleAndFramingOverhead() {
        var messages = List.of(new ChatMessage("user", "hello"));
        int expected = TokenEstimator.estimate("user") + TokenEstimator.estimate("hello") + 3;
        assertEquals(expected, TokenEstimator.estimateMessages(messages));
        assertEquals(0, TokenEstimator.estimateMessages(null));
    }
}
