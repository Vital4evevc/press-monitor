package com.ourcrowd.pressmonitor;

import com.ourcrowd.pressmonitor.collector.NewsItem;
import com.ourcrowd.pressmonitor.service.CompanySeedLoader;
import com.ourcrowd.pressmonitor.service.MonitoringService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DomainLogicTest {

    @Test
    void slugifyHandlesPunctuationAndAccents() {
        assertEquals("safe-superintelligence", CompanySeedLoader.slugify("Safe Superintelligence"));
        assertEquals("ssi-safe-superintelligence", CompanySeedLoader.slugify("SSI (Safe Superintelligence)"));
        assertEquals("quai-md", CompanySeedLoader.slugify("Quai.MD"));
        assertEquals("3d-signals", CompanySeedLoader.slugify("3d Signals"));
        assertEquals("one-zero-digital-bank-ltd", CompanySeedLoader.slugify("One Zero Digital Bank Ltd."));
    }

    @Test
    void dedupKeyIsStableForSameUrlAndDiffersOtherwise() {
        NewsItem a = new NewsItem("Title A", "s", "https://x.com/1", "src", Instant.now());
        NewsItem aAgain = new NewsItem("Different title", "s2", "https://x.com/1", "src2", Instant.now());
        NewsItem b = new NewsItem("Title A", "s", "https://x.com/2", "src", Instant.now());
        assertEquals(MonitoringService.dedupKey(a), MonitoringService.dedupKey(aAgain));
        assertNotEquals(MonitoringService.dedupKey(a), MonitoringService.dedupKey(b));
    }
}
