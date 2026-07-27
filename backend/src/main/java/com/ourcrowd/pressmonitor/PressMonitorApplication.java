package com.ourcrowd.pressmonitor;

import com.ourcrowd.pressmonitor.config.MonitoringProperties;
import com.ourcrowd.pressmonitor.config.OllamaProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the OurCrowd Press Mentions Monitor backend service.
 *
 * This service does three things: collects recent news items for each tracked portfolio
 * company through Google News RSS, classifies each item's sentiment with a locally hosted
 * Ollama model, and exposes a REST API plus a daily job that alerts on new coverage,
 * scheduled through Quartz (see config.QuartzConfig).
 *
 * It has no UI — press-monitor-frontend is the public-facing service that serves the
 * dashboard, including the read endpoints (/api/companies, /api/summary, and so on), which
 * it builds itself by querying MySQL directly rather than calling this service. This
 * service's own REST surface is just /api/run (it owns the pipeline) and /api/health (it
 * owns the Ollama connection) — those are the only two things the frontend can't do
 * locally, so it proxies just those.
 *
 * There's no @EnableScheduling here — Quartz, via spring-boot-starter-quartz, handles the
 * daily job now instead of Spring's @Scheduled.
 */
@SpringBootApplication
@EnableConfigurationProperties({OllamaProperties.class, MonitoringProperties.class})
public class PressMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PressMonitorApplication.class, args);
    }
}
