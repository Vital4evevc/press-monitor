package com.ourcrowd.pressmonitor.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.ourcrowd.pressmonitor.config.OllamaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A thin client over the local Ollama HTTP API (https://ollama.com).
 *
 * We only ever call POST /api/generate. We ask for non-streaming responses, and when we
 * need it, we constrain the output to JSON with Ollama's format: "json" option so parsing
 * downstream is reliable.
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private final RestClient client;
    private final OllamaProperties props;

    public OllamaClient(@Qualifier("ollamaRestClient") RestClient client, OllamaProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * Runs a single generation. When jsonMode is true, we constrain the output to JSON.
     * Returns the model's raw text output (the "response" field), or null on failure.
     *
     * The "think" flag matters more than it looks. Hybrid reasoning models — qwen3 among them,
     * which is our default — emit a chain-of-thought block before their actual answer unless
     * told not to. That fights with format: "json" (the grammar won't let the model open a
     * <think> tag), and what comes back tends to be a truncated object missing the fields we
     * asked for. Since a missing "sentiment" key silently becomes UNKNOWN downstream, the
     * failure is quiet and looks like a successful run. So we ask for thinking to be off.
     *
     * Not every model understands that flag, though, and older Ollama builds reject it
     * outright — hence the retry without it, so switching to a non-reasoning model like
     * llama3.2 doesn't start failing every call.
     */
    public String generate(String prompt, boolean jsonMode) {
        Boolean think = props.getThink();
        String out = post(prompt, jsonMode, think);
        if (out == null && think != null) {
            log.debug("Retrying generate without the 'think' flag (model={})", props.getModel());
            return post(prompt, jsonMode, null);
        }
        return out;
    }

    private String post(String prompt, boolean jsonMode, Boolean think) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", props.getTemperature());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("options", options);
        if (jsonMode) {
            body.put("format", "json");
        }
        if (think != null) {
            body.put("think", think);
        }

        try {
            JsonNode resp = client.post()
                    .uri("/api/generate")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (resp == null || !resp.hasNonNull("response")) {
                log.warn("Ollama returned no 'response' field (model={})", props.getModel());
                return null;
            }
            String text = resp.get("response").asText();
            // Newer Ollama splits reasoning into its own field. If the answer landed there and
            // "response" came back empty, we'd otherwise report a blank result for no reason.
            if (text.isBlank() && resp.hasNonNull("thinking")) {
                log.warn("Model {} returned only reasoning and an empty response — "
                        + "set ollama.think=false or use a non-reasoning model.", props.getModel());
            }
            return text;
        } catch (Exception e) {
            log.warn("Ollama generate call failed (model={}): {}", props.getModel(), e.getMessage());
            return null;
        }
    }

    // A best-effort health check — true if the Ollama server responds to /api/tags.
    public boolean isReachable() {
        try {
            client.get().uri("/api/tags").retrieve().body(JsonNode.class);
            return true;
        } catch (Exception e) {
            log.warn("Ollama not reachable at {}: {}", props.getBaseUrl(), e.getMessage());
            return false;
        }
    }

    public String model() {
        return props.getModel();
    }
}
