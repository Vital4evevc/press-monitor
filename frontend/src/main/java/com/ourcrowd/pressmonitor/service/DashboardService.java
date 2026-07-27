package com.ourcrowd.pressmonitor.service;

import com.ourcrowd.pressmonitor.model.Company;
import com.ourcrowd.pressmonitor.model.Mention;
import com.ourcrowd.pressmonitor.model.MentionStatus;
import com.ourcrowd.pressmonitor.repository.CompanyRepository;
import com.ourcrowd.pressmonitor.repository.MentionRepository;
import com.ourcrowd.pressmonitor.web.dto.CompanyStatusDto;
import com.ourcrowd.pressmonitor.web.dto.DashboardSummary;
import com.ourcrowd.pressmonitor.web.dto.MentionDto;
import com.ourcrowd.pressmonitor.web.dto.SentimentBreakdown;
import com.ourcrowd.pressmonitor.web.dto.TimelinePoint;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * This is the read side of the system: it turns the stored companies and mentions into
 * the aggregates the dashboard actually needs — quarterly counts, sentiment splits,
 * "last mentioned" status, and a weekly trend line.
 *
 * It queries MySQL directly through CompanyRepository/MentionRepository (from
 * press-monitor-shared), bound to this service's own read-only DataSource — no proxying
 * the backend for any of this. The backend has its own full read/write connection to the
 * same tables for the collection pipeline, but doesn't need this class itself: it's purely
 * about building the dashboard's read-only views, which only this service exposes.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final CompanyRepository companyRepository;
    private final MentionRepository mentionRepository;
    private final int quarterDays;

    public DashboardService(CompanyRepository companyRepository,
                            MentionRepository mentionRepository,
                            // No ":90" fallback on purpose. application.yml sets this, and a
                            // default here would be a third copy of the number — one that
                            // silently wins if the key is ever removed, leaving this service
                            // computing a different quarter from the backend.
                            @Value("${monitoring.quarter-days}") int quarterDays) {
        this.companyRepository = companyRepository;
        this.mentionRepository = mentionRepository;
        this.quarterDays = quarterDays;
    }

    private Instant quarterCutoff() {
        return Instant.now().minus(quarterDays, ChronoUnit.DAYS);
    }

    public DashboardSummary summary() {
        List<Company> companies = companyRepository.findAll();
        List<Mention> quarter = mentionsInQuarter();

        long withCoverage = quarter.stream().map(Mention::getCompanyId).distinct().count();
        SentimentBreakdown breakdown = breakdownOf(quarter);

        return new DashboardSummary(
                quarterDays,
                companies.size(),
                withCoverage,
                companies.size() - withCoverage,
                quarter.size(),
                breakdown,
                Instant.now()
        );
    }

    // Per-company status, sorted with the busiest companies (most mentions this quarter) first.
    public List<CompanyStatusDto> companyStatuses() {
        Map<String, Company> byId = companyRepository.findAll().stream()
                .collect(Collectors.toMap(Company::getId, c -> c));
        Map<String, List<Mention>> allByCompany = mentionRepository.findAll().stream()
                .collect(Collectors.groupingBy(Mention::getCompanyId));

        Instant cutoff = quarterCutoff();
        List<CompanyStatusDto> out = new ArrayList<>();
        for (Company c : byId.values()) {
            List<Mention> all = allByCompany.getOrDefault(c.getId(), List.of());
            out.add(buildStatus(c, all, cutoff));
        }
        out.sort(Comparator.comparingLong(CompanyStatusDto::mentionsInQuarter).reversed()
                .thenComparing(CompanyStatusDto::name));
        return out;
    }

    public CompanyStatusDto companyStatus(String companyId) {
        Company c = companyRepository.findById(companyId).orElse(null);
        if (c == null) {
            return null;
        }
        List<Mention> all = mentionRepository.findByCompanyIdOrderByPublishedAtDesc(companyId);
        return buildStatus(c, all, quarterCutoff());
    }

    private CompanyStatusDto buildStatus(Company c, List<Mention> all, Instant cutoff) {
        List<Mention> quarter = all.stream()
                .filter(m -> m.getPublishedAt() != null && m.getPublishedAt().isAfter(cutoff))
                .toList();
        Instant last = all.stream()
                .map(Mention::getPublishedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        Long daysSince = last == null ? null : Duration.between(last, Instant.now()).toDays();
        MentionStatus status = MentionStatus.fromDays(daysSince);

        return new CompanyStatusDto(
                c.getId(),
                c.getName(),
                quarter.size(),
                breakdownOf(quarter),
                last,
                daysSince,
                status.name(),
                status.getDescription()
        );
    }

    // Weekly sentiment counts across the quarter, oldest week first.
    public List<TimelinePoint> timeline() {
        List<Mention> quarter = mentionsInQuarter();
        ZoneId zone = ZoneId.systemDefault();
        // each bucket is keyed by the Monday of that week
        Map<LocalDate, long[]> buckets = new TreeMap<>();
        for (Mention m : quarter) {
            if (m.getPublishedAt() == null) {
                continue;
            }
            LocalDate date = m.getPublishedAt().atZone(zone).toLocalDate();
            LocalDate monday = date.minusDays((date.getDayOfWeek().getValue() + 6) % 7);
            long[] counts = buckets.computeIfAbsent(monday, k -> new long[3]);
            switch (m.getSentiment()) {
                case POSITIVE -> counts[0]++;
                case NEGATIVE -> counts[1]++;
                case NEUTRAL -> counts[2]++;
                default -> { /* we don't chart UNKNOWN */ }
            }
        }
        List<TimelinePoint> out = new ArrayList<>();
        buckets.forEach((week, c) -> out.add(new TimelinePoint(week, c[0], c[1], c[2])));
        return out;
    }

    // The most recent mentions across all companies — feeds the dashboard's feed/alerts view.
    public List<MentionDto> recentMentions(int limit) {
        Map<String, String> names = companyNames();
        return mentionRepository.findAll().stream()
                .sorted(Comparator.comparing(
                        Mention::getPublishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .map(m -> toMentionDto(m, names.getOrDefault(m.getCompanyId(), m.getCompanyId())))
                .toList();
    }

    public List<MentionDto> mentionsForCompany(String companyId) {
        Map<String, String> names = companyNames();
        return mentionRepository.findByCompanyIdOrderByPublishedAtDesc(companyId).stream()
                .map(m -> toMentionDto(m, names.getOrDefault(m.getCompanyId(), m.getCompanyId())))
                .toList();
    }

    // Turns a Mention entity into the wire-level MentionDto.
    private static MentionDto toMentionDto(Mention m, String companyName) {
        return new MentionDto(
                m.getId(),
                m.getCompanyId(),
                companyName,
                m.getTitle(),
                m.getSnippet(),
                m.getUrl(),
                m.getSource(),
                m.getPublishedAt(),
                m.getSentiment().name().toLowerCase(),
                m.getSentimentConfidence(),
                m.getSentimentReason()
        );
    }

    private List<Mention> mentionsInQuarter() {
        Instant cutoff = quarterCutoff();
        return mentionRepository.findAll().stream()
                .filter(m -> m.getPublishedAt() != null && m.getPublishedAt().isAfter(cutoff))
                .toList();
    }

    private SentimentBreakdown breakdownOf(List<Mention> mentions) {
        long pos = 0, neg = 0, neu = 0, unk = 0;
        for (Mention m : mentions) {
            switch (m.getSentiment()) {
                case POSITIVE -> pos++;
                case NEGATIVE -> neg++;
                case NEUTRAL -> neu++;
                default -> unk++;
            }
        }
        return new SentimentBreakdown(pos, neg, neu, unk);
    }

    private Map<String, String> companyNames() {
        Map<String, String> names = new LinkedHashMap<>();
        companyRepository.findAll().forEach(c -> names.put(c.getId(), c.getName()));
        return names;
    }
}
