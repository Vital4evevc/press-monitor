package com.ourcrowd.pressmonitor.service;

import com.ourcrowd.pressmonitor.model.Company;
import com.ourcrowd.pressmonitor.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Loads the seed list of companies from companies.csv on the classpath and upserts them
 * into the database on startup. The CSV is the source of truth for which companies we
 * track.
 *
 * The CSV needs a header row and looks like: name,search_hint — only name is required.
 */
@Component
public class CompanySeedLoader {

    private static final Logger log = LoggerFactory.getLogger(CompanySeedLoader.class);
    private static final String SEED_FILE = "companies.csv";

    private final CompanyRepository companyRepository;

    public CompanySeedLoader(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadSeed() {
        List<Company> companies = readSeed();
        if (companies.isEmpty()) {
            log.warn("No companies loaded from {} — nothing will be monitored.", SEED_FILE);
            return;
        }
        // saveAll upserts by primary key (the slug), so re-running this stays idempotent.
        companyRepository.saveAll(companies);
        log.info("Seeded {} companies from {}", companies.size(), SEED_FILE);
    }

    private List<Company> readSeed() {
        List<Company> out = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(SEED_FILE);
        if (!resource.exists()) {
            log.error("Seed file {} not found on classpath", SEED_FILE);
            return out;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (header) {
                    header = false; // skip header row
                    continue;
                }
                String[] cols = parseCsvLine(line);
                String name = cols.length > 0 ? cols[0].trim() : "";
                if (name.isEmpty()) {
                    continue;
                }
                String hint = cols.length > 1 ? emptyToNull(cols[1].trim()) : null;
                out.add(new Company(slugify(name), name, hint));
            }
        } catch (Exception e) {
            log.error("Failed to read seed file {}: {}", SEED_FILE, e.getMessage(), e);
        }
        return out;
    }

    // A minimal CSV parser that respects double-quoted fields containing commas.
    private static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }

    // Turns "Safe Superintelligence" into "safe-superintelligence".
    public static String slugify(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        String slug = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? "company" : slug;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
