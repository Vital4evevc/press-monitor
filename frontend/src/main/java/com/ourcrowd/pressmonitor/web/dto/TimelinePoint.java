package com.ourcrowd.pressmonitor.web.dto;

import java.time.LocalDate;

/** One week's worth of mentions, broken down by sentiment, for the dashboard trend chart. */
public record TimelinePoint(
        LocalDate weekStart,
        long positive,
        long negative,
        long neutral
) {
}
