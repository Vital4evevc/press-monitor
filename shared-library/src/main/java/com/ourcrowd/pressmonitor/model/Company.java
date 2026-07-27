package com.ourcrowd.pressmonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * A tracked OurCrowd portfolio or fund company, seeded from companies.csv.
 *
 * This lives in press-monitor-shared instead of just the backend so both press-monitor-backend
 * and press-monitor-frontend can query the company table directly from their own MySQL
 * connection, each with its own credentials.
 */
@Entity
@Table(name = "company", indexes = @Index(name = "idx_company_name", columnList = "name", unique = true))
public class Company {

    // Stable slug derived from the name, e.g. "safe-superintelligence" — used as the primary key.
    @Id
    @Column(length = 128)
    private String id;

    @Column(nullable = false, length = 256)
    private String name;

    // Optional extra search terms we tack onto the news query to cut down on false positives.
    @Column(length = 256)
    private String searchHint;

    protected Company() {
        // for JPA
    }

    public Company(String id, String name, String searchHint) {
        this.id = id;
        this.name = name;
        this.searchHint = searchHint;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSearchHint() {
        return searchHint;
    }

    public void setSearchHint(String searchHint) {
        this.searchHint = searchHint;
    }
}
