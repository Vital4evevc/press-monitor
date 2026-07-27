package com.ourcrowd.pressmonitor;

import com.ourcrowd.pressmonitor.config.BackendProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the OurCrowd Press Monitor frontend service.
 *
 * This serves the static dashboard (src/main/resources/static) and its own REST API:
 * companies, mentions, summary, timeline (DashboardController/DashboardService), built by
 * querying MySQL directly through a separate read-only DB user (see application.yml). The
 * Company/Mention entities and repositories underneath come from press-monitor-shared, so
 * this service reads the exact same tables press-monitor-backend writes to — but backend
 * doesn't have this dashboard-serving layer at all, since nothing there needs it. Only
 * /api/run (the pipeline trigger) and /api/health (Ollama reachability) are backend-only
 * concerns this service can't satisfy on its own, so those two stay proxied over HTTP —
 * see web/RunProxyController and web/HealthProxyController.
 *
 * The browser only ever talks to this service; it never calls the backend directly.
 */
@SpringBootApplication
@EnableConfigurationProperties(BackendProperties.class)
public class FrontendApplication {

    public static void main(String[] args) {
        SpringApplication.run(FrontendApplication.class, args);
    }
}
