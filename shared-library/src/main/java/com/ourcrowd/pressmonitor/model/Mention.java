package com.ourcrowd.pressmonitor.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * A single press mention of a company, classified for sentiment.
 *
 * We enforce uniqueness on the combination of companyId and dedupKey so re-running the
 * collector never creates duplicates. dedupKey is a hash of the article URL, falling back
 * to the title when there's no URL to hash.
 *
 * This lives in press-monitor-shared instead of just the backend so both press-monitor-backend
 * and press-monitor-frontend can query the mention table directly from their own MySQL
 * connection, each with its own credentials.
 */
@Entity
@Table(
    name = "mention",
    uniqueConstraints = @UniqueConstraint(name = "uk_mention_company_dedup", columnNames = {"company_id", "dedup_key"}),
    indexes = {
        @Index(name = "idx_mention_company", columnList = "company_id"),
        @Index(name = "idx_mention_published", columnList = "published_at")
    }
)
public class Mention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, length = 128)
    private String companyId;

    @Column(nullable = false, length = 1024)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String snippet;

    @Column(length = 2048)
    private String url;

    @Column(length = 256)
    private String source;

    // When the article was published, according to the RSS feed.
    @Column(name = "published_at")
    private Instant publishedAt;

    // When we first collected and stored this row.
    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sentiment sentiment = Sentiment.UNKNOWN;

    // The model's own confidence in this label, 0.0 to 1.0 — best effort, not gospel.
    @Column(name = "sentiment_confidence")
    private Double sentimentConfidence;

    // A short explanation from the model for why it picked this label — handy for spot-checking.
    @Column(name = "sentiment_reason", length = 1024)
    private String sentimentReason;

    // Hash we use to spot duplicate articles for the same company.
    @Column(name = "dedup_key", nullable = false, length = 64)
    private String dedupKey;

    public Mention() {
        // JPA needs a no-arg constructor, and the pipeline reuses it to build new rows too.
    }

    public Long getId() {
        return id;
    }

    public String getCompanyId() {
        return companyId;
    }

    public void setCompanyId(String companyId) {
        this.companyId = companyId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSnippet() {
        return snippet;
    }

    public void setSnippet(String snippet) {
        this.snippet = snippet;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(Instant collectedAt) {
        this.collectedAt = collectedAt;
    }

    public Sentiment getSentiment() {
        return sentiment;
    }

    public void setSentiment(Sentiment sentiment) {
        this.sentiment = sentiment;
    }

    public Double getSentimentConfidence() {
        return sentimentConfidence;
    }

    public void setSentimentConfidence(Double sentimentConfidence) {
        this.sentimentConfidence = sentimentConfidence;
    }

    public String getSentimentReason() {
        return sentimentReason;
    }

    public void setSentimentReason(String sentimentReason) {
        this.sentimentReason = sentimentReason;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public void setDedupKey(String dedupKey) {
        this.dedupKey = dedupKey;
    }
}
