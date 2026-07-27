package com.ourcrowd.pressmonitor.repository;

import com.ourcrowd.pressmonitor.model.Mention;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Backend's own copy of the mention repository, bound to its full read/write DataSource.
 * The collection pipeline only ever needs to check for an existing mention before inserting
 * a new one (see MonitoringService), so that's the only custom query method here — the
 * frontend's copy of this interface has a different custom method, since it reads mentions
 * back out for the dashboard instead of writing them.
 */
public interface MentionRepository extends JpaRepository<Mention, Long> {

    boolean existsByCompanyIdAndDedupKey(String companyId, String dedupKey);
}
