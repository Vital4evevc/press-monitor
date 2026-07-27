package com.ourcrowd.pressmonitor.service;

import com.ourcrowd.pressmonitor.config.AlertProperties;
import com.ourcrowd.pressmonitor.model.Company;
import com.ourcrowd.pressmonitor.model.Mention;
import com.ourcrowd.pressmonitor.repository.CompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Delivers a "new coverage" alert via webhook (see alert.webhook-url in application.yml).
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertProperties props;
    private final CompanyRepository companyRepository;
    private final RestClient http;

    public AlertService(AlertProperties props,
                        CompanyRepository companyRepository,
                        @Qualifier("httpRestClient") RestClient http) {
        this.props = props;
        this.companyRepository = companyRepository;
        this.http = http;
    }

    // Fires an alert for the mentions found in a run. Does nothing if there's nothing new.
    public void alertNewMentions(List<Mention> newMentions) {
        if (newMentions == null || newMentions.isEmpty()) {
            log.info("No new mentions this run — no alert sent.");
            return;
        }
        Map<String, String> names = companyRepository.findAll().stream()
                .collect(Collectors.toMap(Company::getId, Company::getName));

        String text = formatMessage(newMentions, names);
        postWebhook(newMentions.size(), text);
    }

    String formatMessage(List<Mention> mentions, Map<String, String> names) {
        Map<String, List<Mention>> byCompany = mentions.stream()
                .collect(Collectors.groupingBy(Mention::getCompanyId));

        StringBuilder sb = new StringBuilder();
        sb.append("New press coverage detected: ")
          .append(mentions.size()).append(" mention(s) across ")
          .append(byCompany.size()).append(" company(ies).\n");

        byCompany.forEach((companyId, list) -> {
            sb.append("\n").append(names.getOrDefault(companyId, companyId))
              .append(" (").append(list.size()).append("):\n");
            for (Mention m : list) {
                sb.append("  • [").append(m.getSentiment()).append("] ")
                  .append(m.getTitle());
                if (m.getSource() != null) {
                    sb.append(" — ").append(m.getSource());
                }
                if (m.getUrl() != null) {
                    sb.append("\n    ").append(m.getUrl());
                }
                sb.append("\n");
            }
        });
        return sb.toString().trim();
    }

    private void postWebhook(int count, String text) {
        String webhook = props.getWebhookUrl();
        if (webhook == null || webhook.isBlank()) {
            log.warn("alert.webhook-url is empty — skipping alert.");
            return;
        }
        try {
            http.post().uri(webhook)
                    .body(Map.of("newMentions", count, "message", text, "at", Instant.now().toString()))
                    .retrieve().toBodilessEntity();
            log.info("Webhook alert delivered.");
        } catch (Exception e) {
            log.warn("Webhook alert failed: {}", e.getMessage());
        }
    }
}
