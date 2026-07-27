package com.ourcrowd.pressmonitor.web;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Proxies the Ollama reachability check over to press-monitor-backend, the only service
 * with an Ollama connection. Every other read — companies, mentions, summary, timeline —
 * is served directly from this service's own MySQL connection by this service's own
 * DashboardController. This is the one endpoint that still has to leave the process.
 */
@RestController
@RequestMapping("/api")
public class HealthProxyController {

    private final RestClient backend;

    public HealthProxyController(RestClient backendRestClient) {
        this.backend = backendRestClient;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        try {
            Map<String, Object> backendHealth = backend.get().uri("/api/health").retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            return Map.of("frontendStatus", "ok", "backend", backendHealth);
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return Map.of("frontendStatus", "ok", "backend", Map.of(
                    "status", "unreachable",
                    "message", message
            ));
        }
    }
}
