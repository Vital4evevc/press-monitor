package com.ourcrowd.pressmonitor.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ourcrowd.pressmonitor.collector.NewsItem;
import com.ourcrowd.pressmonitor.model.Company;
import com.ourcrowd.pressmonitor.model.Sentiment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Classifies a news item's relevance and sentiment using the local Ollama model.
 *
 * We do relevance filtering and sentiment scoring in a single prompt, which halves the
 * number of model calls. The model is asked to return strict JSON, something like:
 * { "relevant": true, "sentiment": "positive", "confidence": 0.82, "reason": "..." }
 *
 * Sentiment is judged from the tracked company's point of view — "raises $50M" is
 * positive, "faces lawsuit" is negative, "opens new office" is typically neutral.
 */
@Component
public class SentimentClassifier {

    private static final Logger log = LoggerFactory.getLogger(SentimentClassifier.class);

    private final OllamaClient ollama;
    private final ObjectMapper mapper;

    public SentimentClassifier(OllamaClient ollama, ObjectMapper mapper) {
        this.ollama = ollama;
        this.mapper = mapper;
    }

    public SentimentResult classify(Company company, NewsItem item) {
        String prompt = buildPrompt(company, item);
        String raw = ollama.generate(prompt, true);
        if (raw == null || raw.isBlank()) {
            return SentimentResult.unknown("no model response");
        }
        return parse(raw);
    }

    String buildPrompt(Company company, NewsItem item) {
        String context = company.getName();
        String snippet = item.snippet() == null ? "" : item.snippet();

        return """
                You are a financial press analyst. Analyze one news item about a company an
                investor is tracking, and reply with ONLY a JSON object (no prose, no markdown).

                Company being tracked: %s

                News headline: %s
                News snippet: %s

                Do two things:
                1. relevant: is this article actually about the tracked company (true/false)?
                   Set false if it is about a different company/person that shares the name.
                2. sentiment: from the tracked company's perspective, classify the coverage as
                   "positive", "negative", or "neutral".
                   - positive: funding, growth, partnerships, awards, strong results, launches praised
                   - negative: lawsuits, layoffs, breaches, recalls, losses, criticism, failures
                   - neutral: factual/announcement with no clear good-or-bad slant

                Respond with EXACTLY this JSON shape and nothing else:
                {"relevant": true, "sentiment": "positive|negative|neutral", "confidence": 0.0-1.0, "reason": "short reason"}
                """.formatted(context, item.title(), snippet);
    }

    private SentimentResult parse(String raw) {
        String json = extractJson(raw);
        if (json == null) {
            log.warn("No JSON in model output, storing as UNKNOWN: {}", truncate(raw));
            return SentimentResult.unknown("unparseable model output");
        }
        try {
            JsonNode node = mapper.readTree(json);
            boolean relevant = !node.has("relevant") || node.get("relevant").asBoolean(true);
            // Worth being loud about: a response that parses but carries no "sentiment" key
            // becomes UNKNOWN, which looks identical to a successful run from the outside.
            // That's exactly how a model-level problem stays hidden for a whole run.
            if (!node.hasNonNull("sentiment")) {
                log.warn("Model returned JSON with no 'sentiment' field, storing as UNKNOWN: {}",
                        truncate(json));
            }
            Sentiment sentiment = Sentiment.fromString(
                    node.hasNonNull("sentiment") ? node.get("sentiment").asText() : null);
            double confidence = node.hasNonNull("confidence") ? clamp(node.get("confidence").asDouble(0)) : 0.0;
            String reason = node.hasNonNull("reason") ? node.get("reason").asText() : null;
            return new SentimentResult(relevant, sentiment, confidence, reason);
        } catch (Exception e) {
            log.warn("Failed to parse model JSON, storing as UNKNOWN: '{}' ({})",
                    truncate(json), e.getMessage());
            return SentimentResult.unknown("json parse error");
        }
    }

    /**
     * Pulls the first complete JSON object out of the model's output, tolerating stray text
     * around it.
     *
     * This scans for a brace-balanced object rather than taking everything between the first
     * '{' and the last '}'. The naive version breaks badly on reasoning models: a qwen3-style
     * <think> block that happens to mention a brace turns the "JSON" into a span starting
     * mid-thought, which then fails to parse and quietly becomes UNKNOWN. Any leading think
     * block is stripped first for the same reason.
     *
     * Braces inside string literals are skipped, so a reason like "raised {sic} $50M" doesn't
     * throw the balance off.
     */
    public static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        String s = stripThinking(raw);

        int start = s.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = !inString;
                } else if (!inString && c == '{') {
                    depth++;
                } else if (!inString && c == '}') {
                    depth--;
                    if (depth == 0) {
                        return s.substring(start, i + 1);
                    }
                }
            }
            // Unbalanced from here — try the next '{' along.
            start = s.indexOf('{', start + 1);
        }
        return null;
    }

    // Drops <think>...</think> blocks that reasoning models emit ahead of their real answer,
    // including an unclosed one left behind when generation hit the token limit mid-thought.
    static String stripThinking(String raw) {
        String s = raw.replaceAll("(?is)<think>.*?</think>", " ");
        int open = s.toLowerCase().indexOf("<think>");
        if (open >= 0) {
            s = s.substring(0, open);
        }
        return s;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "null";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
