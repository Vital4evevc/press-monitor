package com.ourcrowd.pressmonitor.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Proxies the pipeline-trigger API over to press-monitor-backend, forwarding its status
 * code and body verbatim. The dashboard's existing run/poll logic in app.js checks for
 * specific statuses — 202 started, 409 already running, 503 Ollama unreachable — so we
 * have to pass those through as-is instead of translating them into something else.
 */
@RestController
@RequestMapping("/api/run")
public class RunProxyController {

    private static final Logger log = LoggerFactory.getLogger(RunProxyController.class);
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP =
            new ParameterizedTypeReference<>() { };

    private final RestClient backend;

    public RunProxyController(RestClient backendRestClient) {
        this.backend = backendRestClient;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> trigger() {
        return forward(() -> backend.post().uri("/api/run")
                .exchange((request, response) ->
                        ResponseEntity.status(response.getStatusCode()).body(response.bodyTo(JSON_MAP))));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return forward(() -> backend.get().uri("/api/run/status")
                .exchange((request, response) ->
                        ResponseEntity.status(response.getStatusCode()).body(response.bodyTo(JSON_MAP))));
    }

    // exchange() only handles responses the backend actually sent — a connection failure
    // (say, the backend process is down) throws instead, so that case still needs its own
    // fallback here.
    private ResponseEntity<Map<String, Object>> forward(java.util.function.Supplier<ResponseEntity<Map<String, Object>>> call) {
        try {
            return call.get();
        } catch (Exception e) {
            log.warn("Could not reach backend for /api/run: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "error",
                    "message", "press-monitor-backend is unreachable: " + e.getMessage()
            ));
        }
    }
}
