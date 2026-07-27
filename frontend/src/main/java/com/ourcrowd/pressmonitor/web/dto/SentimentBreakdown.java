package com.ourcrowd.pressmonitor.web.dto;

/** How many mentions fall under each sentiment label. */
public record SentimentBreakdown(long positive, long negative, long neutral, long unknown) {

    public long total() {
        return positive + negative + neutral + unknown;
    }
}
