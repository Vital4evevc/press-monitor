package com.ourcrowd.pressmonitor.repository;

import com.ourcrowd.pressmonitor.model.Mention;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Frontend's own copy of the mention repository, bound to its read-only DataSource.
 * DashboardService only needs the full mention list (for the summary/timeline/feed
 * aggregates) and a per-company list ordered newest-first for the company detail page —
 * so that's the only custom query method here. The backend's copy of this interface has a
 * different custom method, since it's checking for duplicates before writing, not reading
 * back out for a dashboard.
 */
public interface MentionRepository extends JpaRepository<Mention, Long> {

    List<Mention> findByCompanyIdOrderByPublishedAtDesc(String companyId);
}
