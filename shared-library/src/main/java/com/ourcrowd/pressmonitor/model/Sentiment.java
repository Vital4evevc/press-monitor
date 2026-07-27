package com.ourcrowd.pressmonitor.model;

/**
 * The sentiment label the local LLM assigns to a press mention.
 *
 * UNKNOWN is our fallback for when classification fails or the model returns something we
 * can't parse — it keeps the pipeline going instead of just dropping the mention.
 */
public enum Sentiment {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
    UNKNOWN;

    public static Sentiment fromString(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String v = raw.trim().toUpperCase();
        return switch (v) {
            case "POSITIVE", "POS" -> POSITIVE;
            case "NEGATIVE", "NEG" -> NEGATIVE;
            case "NEUTRAL", "NEU" -> NEUTRAL;
            default -> UNKNOWN;
        };
    }
}
