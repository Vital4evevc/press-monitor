package com.ourcrowd.pressmonitor.collector;

import java.time.Instant;

public record NewsItem(
        String title,
        String snippet,
        String url,
        String source,
        Instant publishedAt
) {
}
