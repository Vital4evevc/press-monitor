package com.ourcrowd.pressmonitor;

import com.ourcrowd.pressmonitor.llm.SentimentClassifier;
import com.ourcrowd.pressmonitor.model.Sentiment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SentimentClassifierTest {

    @Test
    void extractsJsonFromNoisyOutput() {
        String noisy = "Sure! Here is the result:\n{\"relevant\": true, \"sentiment\": \"positive\"} \nHope that helps.";
        assertEquals("{\"relevant\": true, \"sentiment\": \"positive\"}",
                SentimentClassifier.extractJson(noisy));
    }

    @Test
    void returnsNullWhenNoJsonPresent() {
        assertNull(SentimentClassifier.extractJson("no json here"));
    }

    // Reasoning models (qwen3, our default) emit a <think> block before the answer. The
    // earlier "first { to last }" scan started mid-thought whenever that block contained a
    // brace, so the result never parsed and every mention silently landed as UNKNOWN.
    @Test
    void ignoresReasoningBlockBeforeTheAnswer() {
        String json = "{\"relevant\": true, \"sentiment\": \"positive\"}";
        String raw = "<think>\nThe shape is {relevant, sentiment}. Funding is good news.\n</think>\n" + json;
        assertEquals(json, SentimentClassifier.extractJson(raw));
    }

    @Test
    void ignoresReasoningBlockRegardlessOfTagCase() {
        String json = "{\"sentiment\": \"neutral\"}";
        assertEquals(json, SentimentClassifier.extractJson("<THINK>maybe {x}?</THINK>" + json));
    }

    // Generation can stop mid-thought when it runs out of tokens, leaving <think> unclosed
    // and no answer at all. Better to report nothing than to parse the reasoning as a result.
    @Test
    void returnsNullWhenReasoningIsUnclosedAndNoAnswerFollows() {
        assertNull(SentimentClassifier.extractJson("<think>\nThe object {a:1} could..."));
    }

    @Test
    void bracesInsideStringValuesDoNotBreakBalancing() {
        String json = "{\"sentiment\": \"neutral\", \"reason\": \"raised {sic} $50M\"}";
        assertEquals(json, SentimentClassifier.extractJson("noise " + json + " more noise"));
    }

    @Test
    void escapedQuotesInStringValuesDoNotBreakBalancing() {
        String json = "{\"sentiment\": \"negative\", \"reason\": \"the \\\"deal\\\" collapsed\"}";
        assertEquals(json, SentimentClassifier.extractJson(json));
    }

    @Test
    void takesTheFirstCompleteObjectNotEverythingUpToTheLastBrace() {
        String raw = "{\"sentiment\": \"positive\"} trailing {\"other\": 1}";
        assertEquals("{\"sentiment\": \"positive\"}", SentimentClassifier.extractJson(raw));
    }

    @Test
    void sentimentParsingIsCaseInsensitiveAndSafe() {
        assertEquals(Sentiment.POSITIVE, Sentiment.fromString("Positive"));
        assertEquals(Sentiment.NEGATIVE, Sentiment.fromString(" NEG "));
        assertEquals(Sentiment.NEUTRAL, Sentiment.fromString("neutral"));
        assertEquals(Sentiment.UNKNOWN, Sentiment.fromString("banana"));
        assertEquals(Sentiment.UNKNOWN, Sentiment.fromString(null));
    }
}
