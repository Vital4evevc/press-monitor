package com.ourcrowd.pressmonitor.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for reaching the backend service (see backend.* in application.yml).
 *
 * No defaults here — application.yml sets all three, and a duplicate copy in field
 * initialisers only creates something to drift. A missing key fails at startup rather than
 * quietly falling back, which matters for baseUrl in particular: a stale localhost default
 * inside a container points at the container itself, and every proxied call fails with a
 * connection refused that looks like the backend being down.
 */
@Validated
@ConfigurationProperties(prefix = "backend")
public class BackendProperties {

    // Base URL of press-monitor-backend's REST API.
    @NotBlank
    private String baseUrl;

    // Connect timeout, in seconds.
    @NotNull
    @Positive
    private Integer connectTimeoutSeconds;

    // Read timeout, in seconds. Runs (POST /api/run) return immediately, so this can stay short.
    @NotNull
    @Positive
    private Integer readTimeoutSeconds;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Integer getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(Integer connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public Integer getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(Integer readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }
}
