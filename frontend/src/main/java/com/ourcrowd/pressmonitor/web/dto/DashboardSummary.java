package com.ourcrowd.pressmonitor.web.dto;

import java.time.Instant;

/** The headline numbers shown at the top of the dashboard for the current quarter. */
public record DashboardSummary(
        int quarterDays,
        long totalCompanies,
        long companiesWithCoverage,
        long companiesWithNoCoverage,
        long totalMentionsInQuarter,
        SentimentBreakdown breakdown,
        Instant generatedAt
) {
}
