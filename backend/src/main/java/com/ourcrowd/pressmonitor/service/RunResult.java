package com.ourcrowd.pressmonitor.service;

import com.ourcrowd.pressmonitor.model.Mention;

import java.time.Instant;
import java.util.List;

// A summary of a single collection-and-classification run: when it started and finished,
// how many companies got queried, how many raw news items came back, how many of those
// were new and actually sent to the LLM, how many mentions got newly stored after the
// relevance filter, and how many items the LLM judged off-topic.
public record RunResult(
        Instant startedAt,
        Instant finishedAt,
        int companiesScanned,
        int itemsFetched,
        int itemsClassified,
        List<Mention> newMentions,
        int skippedIrrelevant
) {
    public int newMentionCount() {
        return newMentions.size();
    }
}
