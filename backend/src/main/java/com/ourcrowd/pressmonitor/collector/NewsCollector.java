package com.ourcrowd.pressmonitor.collector;

import com.ourcrowd.pressmonitor.model.Company;

import java.util.List;

/**
 * Sources recent news items for a company.
 *
 * Implementations shouldn't throw on transient network trouble — log it and hand back an
 * empty list instead, so one flaky company can't take down a whole run.
 */
public interface NewsCollector {

    // Up to `limit` recent news items mentioning the company. Possibly empty, never null.
    List<NewsItem> fetchRecent(Company company, int limit);

    /**
     * Whether this collector is currently backing off from its upstream source and is
     * refusing to fetch for a while.
     *
     * This exists so callers can tell the difference between the two things an empty result
     * from fetchRecent could mean: "this company genuinely has no recent coverage" versus
     * "we're in a cooldown and didn't even ask." MonitoringService uses it to set those
     * companies aside and come back to them once the cooldown lifts, rather than recording
     * them as having no news. Collectors with no such notion can just leave this alone.
     */
    default boolean isBackingOff() {
        return false;
    }
}
