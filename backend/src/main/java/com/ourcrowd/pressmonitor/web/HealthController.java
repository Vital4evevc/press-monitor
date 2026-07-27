package com.ourcrowd.pressmonitor.web;

import com.ourcrowd.pressmonitor.llm.OllamaClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A reachability check for Ollama. This is backend-only, since Ollama only runs here —
 * press-monitor-frontend has no direct Ollama connection, so it proxies this one endpoint
 * (see the frontend's HealthProxyController) instead of serving it itself. This is also the
 * only dashboard-facing endpoint the backend exposes at all — everything else
 * (companies, mentions, summary, timeline) is served by the frontend's own
 * DashboardController, which queries MySQL directly instead of asking the backend for it.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final OllamaClient ollama;

    public HealthController(OllamaClient ollama) {
        this.ollama = ollama;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean up = ollama.isReachable();
        return Map.of(
                "ollamaReachable", up,
                "model", ollama.model(),
                "status", up ? "ok" : "ollama-unreachable"
        );
    }
}
