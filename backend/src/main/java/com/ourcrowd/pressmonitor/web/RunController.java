package com.ourcrowd.pressmonitor.web;

import com.ourcrowd.pressmonitor.llm.OllamaClient;
import com.ourcrowd.pressmonitor.service.PipelineRunner;
import com.ourcrowd.pressmonitor.service.RunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets a reviewer trigger the collect-classify-alert pipeline on demand (POST /api/run)
 * instead of waiting for the daily cron. It runs in the background so the HTTP call
 * returns immediately — poll GET /api/run/status to see how it went.
 */
@RestController
@RequestMapping("/api/run")
public class RunController {

    private static final Logger log = LoggerFactory.getLogger(RunController.class);

    private final PipelineRunner pipeline;
    private final OllamaClient ollama;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pipeline-runner");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile RunResult lastResult;
    private volatile Instant lastStartedAt;

    public RunController(PipelineRunner pipeline, OllamaClient ollama) {
        this.pipeline = pipeline;
        this.ollama = ollama;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> trigger() {
        if (!ollama.isReachable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "error",
                            "message", "Ollama is not reachable. Start it with `ollama serve` and pull the model."));
        }
        if (!running.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "busy", "message", "A run is already in progress."));
        }
        lastStartedAt = Instant.now();
        executor.submit(() -> {
            try {
                lastResult = pipeline.runFullPipeline();
            } catch (Exception e) {
                log.error("Manual run failed: {}", e.getMessage(), e);
            } finally {
                running.set(false);
            }
        });
        return ResponseEntity.accepted()
                .body(Map.of("status", "started", "startedAt", lastStartedAt.toString()));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        boolean isRunning = running.get();
        if (lastResult == null) {
            return Map.of("running", isRunning,
                    "message", isRunning ? "Run in progress…" : "No run has completed yet.");
        }
        RunResult r = lastResult;
        return Map.of(
                "running", isRunning,
                "lastRun", Map.of(
                        "startedAt", r.startedAt().toString(),
                        "finishedAt", r.finishedAt().toString(),
                        "companiesScanned", r.companiesScanned(),
                        "itemsFetched", r.itemsFetched(),
                        "itemsClassified", r.itemsClassified(),
                        "newMentions", r.newMentionCount(),
                        "skippedIrrelevant", r.skippedIrrelevant()
                )
        );
    }
}
