package com.ourcrowd.pressmonitor.repository;

import com.ourcrowd.pressmonitor.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Backend's own copy of the company repository. This connects through the backend's
 * full read/write DataSource — it's the one that seeds companies and owns the schema.
 * The frontend has its own separate copy of this same interface, bound to its read-only
 * connection instead; the two aren't shared, since Spring Data repositories are cheap to
 * duplicate and doing so avoids coupling either service to a shared-library release just
 * to get a plain JpaRepository.
 */
public interface CompanyRepository extends JpaRepository<Company, String> {
}
