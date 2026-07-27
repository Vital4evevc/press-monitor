package com.ourcrowd.pressmonitor.model;

/**
 * A company's "current mention status," bucketed by how many days it's been since it last
 * showed up in the news.
 *
 * DORMANT and NO_COVERAGE are deliberately not the same thing, though they used to be lumped
 * together. DORMANT means we have coverage on file and it has simply gone quiet — the company
 * was in the news, just not for a while. NO_COVERAGE means we have never found anything at
 * all, which usually says more about the search than the company: an ambiguous name, a missing
 * search hint, or a company that genuinely gets no English-language press. They want looking
 * at in completely different ways, so they get their own buckets.
 *
 * Worth knowing why DORMANT is reachable at all, given the collector drops anything older than
 * monitoring.quarter-days before it ever reaches the database. It happens when coverage we
 * stored while it was fresh simply ages past 90 days with nothing newer arriving. So a company
 * drifts FRESH to RECENT to COOLING to DORMANT on its own, without any new data.
 *
 * This lives in the frontend, not shared-library, because it's only ever used by
 * DashboardService when building the per-company status view — the backend's own pipeline
 * never needs to know about it.
 */
public enum MentionStatus {
    FRESH("Mentioned in the last 3 days"),
    RECENT("Mentioned in the last 45 days"),
    COOLING("Mentioned in the last quarter (90 days)"),
    DORMANT("Last mentioned over 90 days ago"),
    NO_COVERAGE("No coverage ever found");

    private final String description;

    MentionStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    // daysSince is how many days ago the company was last mentioned, or null if it's never
    // had any coverage at all.
    public static MentionStatus fromDays(Long daysSince) {
        if (daysSince == null) {
            return NO_COVERAGE;
        }
        if (daysSince <= 3) {
            return FRESH;
        }
        if (daysSince <= 45) {
            return RECENT;
        }
        if (daysSince <= 90) {
            return COOLING;
        }
        return DORMANT;
    }
}
