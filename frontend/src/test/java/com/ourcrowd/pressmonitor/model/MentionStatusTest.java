package com.ourcrowd.pressmonitor.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MentionStatusTest {

    @Test
    void bucketsByDays() {
        assertEquals(MentionStatus.FRESH, MentionStatus.fromDays(3L));
        assertEquals(MentionStatus.RECENT, MentionStatus.fromDays(20L));
        assertEquals(MentionStatus.COOLING, MentionStatus.fromDays(60L));
        assertEquals(MentionStatus.DORMANT, MentionStatus.fromDays(200L));
        assertEquals(MentionStatus.NO_COVERAGE, MentionStatus.fromDays(null));
    }

    // Each bucket is inclusive of its upper bound, so the day after it should tip over.
    @Test
    void boundariesAreInclusive() {
        assertEquals(MentionStatus.FRESH, MentionStatus.fromDays(3L));
        assertEquals(MentionStatus.RECENT, MentionStatus.fromDays(4L));

        assertEquals(MentionStatus.RECENT, MentionStatus.fromDays(45L));
        assertEquals(MentionStatus.COOLING, MentionStatus.fromDays(46L));

        assertEquals(MentionStatus.COOLING, MentionStatus.fromDays(90L));
        assertEquals(MentionStatus.DORMANT, MentionStatus.fromDays(91L));
    }

    // The distinction the DORMANT bucket exists for: "went quiet" is not "never found".
    @Test
    void dormantIsForCompaniesThatWereCoveredAndStopped() {
        assertEquals(MentionStatus.DORMANT, MentionStatus.fromDays(91L));
        assertEquals(MentionStatus.DORMANT, MentionStatus.fromDays(3650L));
        assertEquals(MentionStatus.NO_COVERAGE, MentionStatus.fromDays(null));
    }

    @Test
    void sameDayCountsAsFresh() {
        assertEquals(MentionStatus.FRESH, MentionStatus.fromDays(0L));
    }
}
