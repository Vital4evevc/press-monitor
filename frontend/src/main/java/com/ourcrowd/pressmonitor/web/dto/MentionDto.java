package com.ourcrowd.pressmonitor.web.dto;

import java.time.Instant;

/**
 * A press mention as exposed over the API.
 *
 * This is pure data, deliberately kept free of any dependency on the Mention JPA entity —
 * DashboardService builds one of these straight from its own Mention rows.
 */
public record MentionDto(
        Long id,
        String companyId,
        String companyName,
        String title,
        String snippet,
        String url,
        String source,
        Instant publishedAt,
        String sentiment,
        Double confidence,
        String reason
) {
}
