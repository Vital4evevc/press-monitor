package com.ourcrowd.pressmonitor.web;

import com.ourcrowd.pressmonitor.service.DashboardService;
import com.ourcrowd.pressmonitor.web.dto.CompanyStatusDto;
import com.ourcrowd.pressmonitor.web.dto.DashboardSummary;
import com.ourcrowd.pressmonitor.web.dto.MentionDto;
import com.ourcrowd.pressmonitor.web.dto.TimelinePoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The read-only JSON API the static dashboard calls.
 *
 * This is frontend-only — it's backed by DashboardService, which queries MySQL directly
 * through this service's own read-only connection. The backend doesn't expose these paths
 * at all: nothing calls them there, since the backend's only job is collecting/classifying
 * news and its own /api/run + /api/health endpoints (see RunController/HealthController on
 * that side). Keeping this controller here, rather than in press-monitor-shared, means the
 * backend isn't carrying REST endpoints it never actually serves.
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboard.summary();
    }

    @GetMapping("/companies")
    public List<CompanyStatusDto> companies() {
        return dashboard.companyStatuses();
    }

    @GetMapping("/companies/{id}")
    public ResponseEntity<CompanyStatusDto> company(@PathVariable String id) {
        CompanyStatusDto dto = dashboard.companyStatus(id);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    @GetMapping("/companies/{id}/mentions")
    public List<MentionDto> companyMentions(@PathVariable String id) {
        return dashboard.mentionsForCompany(id);
    }

    @GetMapping("/mentions/recent")
    public List<MentionDto> recentMentions(@RequestParam(defaultValue = "50") int limit) {
        return dashboard.recentMentions(limit);
    }

    @GetMapping("/timeline")
    public List<TimelinePoint> timeline() {
        return dashboard.timeline();
    }
}
