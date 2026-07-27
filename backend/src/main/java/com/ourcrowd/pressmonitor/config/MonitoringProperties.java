package com.ourcrowd.pressmonitor.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the collection and classification pipeline (see monitoring.* in
 * application.yml).
 *
 * No defaults here — application.yml sets every one of these, and keeping a second copy in
 * field initialisers just gives the two something to disagree about. A missing key fails at
 * startup instead of silently resolving to whatever this file happened to say.
 *
 * The wrapper types are what make that possible: a primitive int is always 0 when unset, so
 * "not configured" and "configured as zero" are indistinguishable. With Integer, @NotNull can
 * tell them apart — which matters most for concurrency, where a silent 0 would mean a thread
 * pool of no threads.
 */
@Validated
@ConfigurationProperties(prefix = "monitoring")
public class MonitoringProperties {

    // How many days back counts as "this quarter."
    @NotNull
    @Positive
    private Integer quarterDays;

    // Max news items we keep per company per run.
    @NotNull
    @Positive
    private Integer maxItemsPerCompany;

    // Politeness delay between per-company RSS requests, in ms, applied per worker thread.
    // Zero is allowed and means no delay, which GoogleNewsRssCollector treats as "skip the
    // sleep entirely" — hence PositiveOrZero rather than Positive.
    @NotNull
    @PositiveOrZero
    private Long requestDelayMs;

    /**
     * How many companies we process concurrently. Each worker fetches RSS and runs Ollama
     * classification for its company independently, so a full run's wall-clock time drops
     * roughly by this factor. Keep it at or below the DB connection pool size — Hikari's
     * default is 10 — or raise spring.datasource.hikari.maximum-pool-size if you push this
     * higher.
     */
    @NotNull
    @Positive
    private Integer concurrency;

    /**
     * Max concurrent HTTP requests to Google News RSS specifically, separate from
     * concurrency above. Google's endpoint is unauthenticated and actively blocks bursty
     * or bulk traffic with a 503 "automated queries" page, so keeping this low (1-2) makes
     * that less likely to trigger, while concurrency still parallelizes the unrelated
     * Ollama classification calls.
     */
    @NotNull
    @Positive
    private Integer rssConcurrency;

    /**
     * The cron expression for the daily monitoring and alert job, run through Quartz (see
     * config.QuartzConfig). This is Quartz cron syntax, not Spring's — exactly one of the
     * day-of-month and day-of-week fields has to be "?", not both "*".
     */
    @NotBlank
    private String dailyCron;

    public Integer getQuarterDays() {
        return quarterDays;
    }

    public void setQuarterDays(Integer quarterDays) {
        this.quarterDays = quarterDays;
    }

    public Integer getMaxItemsPerCompany() {
        return maxItemsPerCompany;
    }

    public void setMaxItemsPerCompany(Integer maxItemsPerCompany) {
        this.maxItemsPerCompany = maxItemsPerCompany;
    }

    public Long getRequestDelayMs() {
        return requestDelayMs;
    }

    public void setRequestDelayMs(Long requestDelayMs) {
        this.requestDelayMs = requestDelayMs;
    }

    public Integer getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(Integer concurrency) {
        this.concurrency = concurrency;
    }

    public Integer getRssConcurrency() {
        return rssConcurrency;
    }

    public void setRssConcurrency(Integer rssConcurrency) {
        this.rssConcurrency = rssConcurrency;
    }

    public String getDailyCron() {
        return dailyCron;
    }

    public void setDailyCron(String dailyCron) {
        this.dailyCron = dailyCron;
    }
}
