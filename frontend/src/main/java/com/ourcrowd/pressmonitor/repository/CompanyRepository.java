package com.ourcrowd.pressmonitor.repository;

import com.ourcrowd.pressmonitor.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Frontend's own copy of the company repository. This connects through the frontend's
 * read-only DataSource — DashboardService only ever reads companies, never writes them.
 * The backend has its own separate copy of this same interface, bound to its read/write
 * connection instead; the two aren't shared, since Spring Data repositories are cheap to
 * duplicate and doing so avoids coupling either service to a shared-library release just
 * to get a plain JpaRepository.
 */
public interface CompanyRepository extends JpaRepository<Company, String> {
}
