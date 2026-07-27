package com.ourcrowd.pressmonitor.service;

import com.ourcrowd.pressmonitor.collector.NewsCollector;
import com.ourcrowd.pressmonitor.collector.NewsItem;
import com.ourcrowd.pressmonitor.config.MonitoringProperties;
import com.ourcrowd.pressmonitor.llm.SentimentClassifier;
import com.ourcrowd.pressmonitor.llm.SentimentResult;
import com.ourcrowd.pressmonitor.model.Company;
import com.ourcrowd.pressmonitor.model.Mention;
import com.ourcrowd.pressmonitor.model.Sentiment;
import com.ourcrowd.pressmonitor.repository.CompanyRepository;
import com.ourcrowd.pressmonitor.repository.MentionRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the pipeline: for each company, fetch recent news, classify relevance and
 * sentiment with the local LLM, and persist whatever's new and on-topic.
 *
 * Runs are idempotent. Anything already stored for a company is skipped before it ever
 * reaches the model, so re-running is cheap and only ever adds genuinely new coverage —
 * which is also what keeps the daily alert from repeating itself.
 *
 * Companies are processed in parallel, monitoring.concurrency at a time. That number is
 * really about Ollama: classification is the slow part, and it's ours to parallelize. The
 * RSS side is throttled separately and much more conservatively inside
 * GoogleNewsRssCollector, because that endpoint is Google's and it pushes back.
 *
 * That pushback is why this class knows about backing off at all. When the collector trips
 * its circuit breaker, fetches return empty without hitting the network — indistinguishable,
 * from here, from a company that simply has no coverage. So any company we couldn't actually
 * ask about gets set aside and retried once the cooldown lifts, instead of being silently
 * recorded as quiet. Before that retry pass existed, a single block partway through a run
 * meant every company after it came back empty.
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);

    // How long the retry pass is willing to wait for a cooldown to lift before giving up. A
    // little longer than the collector's own 20 minute ceiling, so a maxed-out cooldown still
    // gets its retry rather than timing out right at the boundary.
    private static final Duration MAX_BACKOFF_WAIT = Duration.ofMinutes(25);
    private static final long BACKOFF_POLL_MS = 15_000;

    private final CompanyRepository companyRepository;
    private final MentionRepository mentionRepository;
    private final NewsCollector newsCollector;
    private final SentimentClassifier classifier;
    private final MonitoringProperties props;

    // One worker per concurrently-processed company. Each worker does its own fetching and
    // classifying end to end.
    private final ExecutorService workers;

    public MonitoringService(CompanyRepository companyRepository,
                             MentionRepository mentionRepository,
                             NewsCollector newsCollector,
                             SentimentClassifier classifier,
                             MonitoringProperties props) {
        this.companyRepository = companyRepository;
        this.mentionRepository = mentionRepository;
        this.newsCollector = newsCollector;
        this.classifier = classifier;
        this.props = props;

        int threads = Math.max(1, props.getConcurrency());
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "monitor-worker");
            t.setDaemon(true);
            return t;
        });
        log.info("Monitoring worker pool size: {}", threads);
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
    }

    // Runs the pipeline across every seeded company.
    public RunResult runAll() {
        return run(companyRepository.findAll());
    }

    // Runs the pipeline for a specific set of companies.
    public RunResult run(List<Company> companies) {
        Instant started = Instant.now();
        // Anything published before this is outside the reporting window, so we drop it here
        // rather than paying to classify and store news we'd never show.
        Instant cutoff = started.minus(props.getQuarterDays(), ChronoUnit.DAYS);

        Tally tally = new Tally();
        log.info("Starting monitoring run over {} companies", companies.size());

        processBatch(companies, cutoff, tally);

        // Anything we couldn't actually reach gets a second chance once Google lets us back in.
        if (!tally.deferred.isEmpty()) {
            List<Company> retry = new ArrayList<>(tally.deferred);
            tally.deferred.clear();
            log.info("{} company(ies) skipped while Google News was blocking us — waiting for the "
                    + "cooldown to clear, then retrying them.", retry.size());
            if (waitForBackoffToClear()) {
                processBatch(retry, cutoff, tally);
            }
            if (!tally.deferred.isEmpty()) {
                log.warn("{} company(ies) still couldn't be fetched after the retry pass — they'll "
                        + "be picked up on the next run.", tally.deferred.size());
            }
        }

        Instant finished = Instant.now();
        RunResult result = new RunResult(started, finished, companies.size(),
                tally.fetched.get(), tally.classified.get(),
                new ArrayList<>(tally.newMentions), tally.irrelevant.get());
        int unclassified = tally.unclassified.get();
        log.info("Run complete: scanned={}, fetched={}, classified={}, new={}, irrelevant={}, unclassified={}",
                result.companiesScanned(), result.itemsFetched(), result.itemsClassified(),
                result.newMentionCount(), result.skippedIrrelevant(), unclassified);
        // If essentially nothing came back with a usable label, the model is the problem, not
        // the news. Say so plainly — this used to be invisible, because an item that fails
        // classification is still stored (as UNKNOWN) and the run looks perfectly healthy.
        if (unclassified > 0 && unclassified == result.newMentionCount()) {
            log.error("All {} new mention(s) this run have sentiment=UNKNOWN, which points at the "
                    + "model rather than the coverage. Check the model is pulled and returns plain "
                    + "JSON — reasoning models such as qwen3 need ollama.think=false (see "
                    + "OllamaProperties). The warnings logged above show what it actually returned.",
                    unclassified);
        }
        return result;
    }

    // Fans a set of companies out across the worker pool and waits for all of them.
    private void processBatch(List<Company> companies, Instant cutoff, Tally tally) {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Company company : companies) {
            tasks.add(() -> {
                processCompany(company, cutoff, tally);
                return null;
            });
        }
        try {
            for (Future<Void> future : workers.invokeAll(tasks)) {
                try {
                    future.get();
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    log.warn("A company task failed: {}", cause.getMessage());
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("Run interrupted while processing companies.");
        }
    }

    // One company, start to finish. Never throws — a failure here shouldn't take down the
    // other 257.
    private void processCompany(Company company, Instant cutoff, Tally tally) {
        try {
            // Don't bother asking if the collector is already in a cooldown; set it aside now.
            if (newsCollector.isBackingOff()) {
                tally.deferred.add(company);
                return;
            }

            List<NewsItem> items = newsCollector.fetchRecent(company, props.getMaxItemsPerCompany());

            // Empty plus an active cooldown means we got blocked partway through rather than
            // finding nothing — that's a deferral, not a result.
            if (items.isEmpty() && newsCollector.isBackingOff()) {
                tally.deferred.add(company);
                return;
            }
            tally.fetched.addAndGet(items.size());

            // Cheap filtering first: drop anything outside the quarter, anything repeated
            // within this feed, and anything we already stored on an earlier run. Only what
            // survives all three is worth an LLM call.
            Set<String> seenThisBatch = new HashSet<>();
            List<NewsItem> toClassify = new ArrayList<>();
            for (NewsItem item : items) {
                // Items with no parseable date are kept rather than guessed at.
                if (item.publishedAt() != null && item.publishedAt().isBefore(cutoff)) {
                    continue;
                }
                String dedupKey = dedupKey(item);
                if (!seenThisBatch.add(dedupKey)) {
                    continue;
                }
                if (mentionRepository.existsByCompanyIdAndDedupKey(company.getId(), dedupKey)) {
                    continue;
                }
                toClassify.add(item);
            }
            tally.classified.addAndGet(toClassify.size());

            for (NewsItem item : toClassify) {
                Mention m = classifyAndStore(company, item, tally);
                if (m != null) {
                    tally.newMentions.add(m);
                }
            }
        } catch (Exception e) {
            log.warn("Error processing company '{}': {}", company.getName(), e.getMessage());
        }
    }

    /**
     * Classifies one item and stores it if we're keeping it. Returns the saved Mention, or
     * null if the item was dropped or something went wrong. Each save is its own transaction,
     * so one bad row never takes the others with it.
     */
    private Mention classifyAndStore(Company company, NewsItem item, Tally tally) {
        try {
            SentimentResult result = classifier.classify(company, item);

            // Relevance is decided conservatively, and deliberately so. We only drop an item
            // when the cheap deterministic name check AND the model both say it's off-topic.
            // Either signal alone is too weak to delete real coverage on: the name check
            // misses legitimate coverage that refers to a company oddly, and a small local
            // model will occasionally call a perfectly on-topic article irrelevant.
            boolean nameAppears = nameAppears(company, item);
            if (!nameAppears && !result.relevant()) {
                tally.irrelevant.incrementAndGet();
                log.debug("Dropping likely-irrelevant item for {}: {}", company.getName(), item.title());
                return null;
            }
            if (result.sentiment() == Sentiment.UNKNOWN) {
                tally.unclassified.incrementAndGet();
            }
            return mentionRepository.save(toMention(company, item, dedupKey(item), result));
        } catch (Exception e) {
            log.warn("Failed to classify/store '{}' for {}: {}",
                    item.title(), company.getName(), e.getMessage());
            return null;
        }
    }

    // Blocks until the collector's cooldown lifts. Returns false if we gave up or were
    // interrupted, in which case the retry pass is skipped.
    private boolean waitForBackoffToClear() {
        long deadline = System.currentTimeMillis() + MAX_BACKOFF_WAIT.toMillis();
        try {
            while (newsCollector.isBackingOff()) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("Gave up waiting for the Google News cooldown to clear after {} minutes.",
                            MAX_BACKOFF_WAIT.toMinutes());
                    return false;
                }
                Thread.sleep(BACKOFF_POLL_MS);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Mention toMention(Company company, NewsItem item, String dedupKey, SentimentResult r) {
        Mention m = new Mention();
        m.setCompanyId(company.getId());
        m.setTitle(truncate(item.title(), 1024));
        m.setSnippet(item.snippet());
        m.setUrl(truncate(item.url(), 2048));
        m.setSource(truncate(item.source(), 256));
        m.setPublishedAt(item.publishedAt() != null ? item.publishedAt() : Instant.now());
        m.setCollectedAt(Instant.now());
        m.setSentiment(r.sentiment());
        m.setSentimentConfidence(r.confidence());
        m.setSentimentReason(truncate(r.reason(), 1024));
        m.setDedupKey(dedupKey);
        return m;
    }

    // SHA-256 of the article URL, falling back to the title when there's no URL to hash.
    public static String dedupKey(NewsItem item) {
        String basis = (item.url() != null && !item.url().isBlank()) ? item.url() : item.title();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(basis.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // SHA-256 is always available; fall back to a hashCode string just in case.
            return Integer.toHexString(basis.hashCode());
        }
    }

    /**
     * Whether the company name, or its most distinctive word, plausibly shows up in the item's
     * text. Deterministic and cheap, and a far more reliable relevance signal than a small LLM
     * on its own — which is why it gets a veto in classifyAndStore.
     */
    public static boolean nameAppears(Company company, NewsItem item) {
        String hay = ((item.title() == null ? "" : item.title()) + " "
                + (item.snippet() == null ? "" : item.snippet())).toLowerCase(Locale.ROOT);
        String name = company.getName().toLowerCase(Locale.ROOT);
        if (name.isBlank()) {
            return false;
        }
        if (hay.contains(name)) {
            return true;
        }
        // Fall back to the longest token (>=4 chars) so "Stripe Inc." still matches "stripe".
        String longest = Arrays.stream(name.split("[^a-z0-9]+"))
                .filter(w -> w.length() >= 4)
                .max(Comparator.comparingInt(String::length))
                .orElse("");
        return !longest.isEmpty() && hay.contains(longest);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // Per-run counters. Workers all write to these concurrently, hence the atomics and the
    // synchronized lists.
    private static final class Tally {
        private final AtomicInteger fetched = new AtomicInteger();
        private final AtomicInteger classified = new AtomicInteger();
        private final AtomicInteger irrelevant = new AtomicInteger();
        // Stored, but with no usable sentiment. High numbers here mean the model isn't
        // answering in the shape we asked for, not that the news is genuinely ambiguous.
        private final AtomicInteger unclassified = new AtomicInteger();
        private final List<Mention> newMentions = Collections.synchronizedList(new ArrayList<>());
        private final List<Company> deferred = Collections.synchronizedList(new ArrayList<>());
    }
}
