package com.ourcrowd.pressmonitor.llm;

import com.ourcrowd.pressmonitor.model.Sentiment;

// The outcome of classifying one news item: whether it's actually about the target
// company (relevant), the sentiment label — positive, negative, neutral, or UNKNOWN on
// failure — the model's self-reported confidence from 0.0 to 1.0 (best effort, not
// gospel), and a one-line rationale that's handy for spot-checking quality.
public record SentimentResult(
        boolean relevant,
        Sentiment sentiment,
        double confidence,
        String reason
) {
    public static SentimentResult unknown(String reason) {
        return new SentimentResult(true, Sentiment.UNKNOWN, 0.0, reason);
    }
}
