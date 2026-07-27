package com.ourcrowd.pressmonitor.web.dto;

import java.time.Instant;

/**
 * A per-company summary for the dashboard: quarterly mention counts, sentiment split,
 * and the current "last mentioned" status.
 */
public record CompanyStatusDto(
        String id,
        String name,
        long mentionsInQuarter,
        SentimentBreakdown breakdown,
        Instant lastMentionedAt,
        Long daysSinceLastMention,
        String status,
        String statusDescription
) {
}
