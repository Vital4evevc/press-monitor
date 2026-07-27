package com.ourcrowd.pressmonitor.collector;

import com.ourcrowd.pressmonitor.config.MonitoringProperties;
import com.ourcrowd.pressmonitor.model.Company;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects news through Google News' public RSS search endpoint. For each company we ask for
 * https://news.google.com/rss/search?q="Company Name"&hl=en-US&gl=US&ceid=US:en and parse the
 * RSS 2.0 feed that comes back. No API key, no signup — which is exactly why it's a good fit
 * for a project someone should be able to clone and run.
 *
 * The catch is that it's an unofficial, unauthenticated endpoint, and Google actively defends
 * it against bulk traffic. When it decides we're a bot it doesn't rate-limit politely — it
 * serves an HTML "our systems have detected unusual traffic" interstitial (usually with a 429
 * or 503) in place of the feed. Left unhandled, that gets parsed as an empty feed, every
 * remaining company looks like it has no coverage, and a run quietly collects almost nothing.
 *
 * So there are three separate throttles here, and they do different jobs:
 *
 * - a semaphore capping how many RSS requests are in flight at once (monitoring.rss-concurrency,
 *   default 2), which is deliberately much lower than monitoring.concurrency — that higher
 *   number parallelizes Ollama classification, which Google has no opinion about;
 * - a per-request delay with jitter (monitoring.request-delay-ms, default 800ms give or take
 *   25%), so the requests we do make aren't evenly spaced in a machine-looking way;
 * - a cooldown circuit breaker: the first time we see a block page, RSS fetching stops
 *   entirely for a few minutes rather than hammering something already refusing us, doubling
 *   on repeat blocks up to a 20 minute ceiling.
 *
 * While that cooldown is active, isBackingOff() reports true and fetchRecent returns empty
 * without touching the network. MonitoringService watches that flag so it can set those
 * companies aside and retry them after the cooldown, instead of writing them off as having no
 * news — see its retry pass.
 *
 * Other limitations worth knowing about, also covered in the README: only a headline and short
 * snippet are available rather than article text; company-name ambiguity can pull in unrelated
 * coverage (mitigated by quoting the name, the optional search hint, and the LLM relevance
 * check downstream); and article links are Google redirect URLs that resolve to the publisher
 * when clicked.
 */
@Component
public class GoogleNewsRssCollector implements NewsCollector {

    private static final Logger log = LoggerFactory.getLogger(GoogleNewsRssCollector.class);

    private static final String ENDPOINT = "https://news.google.com/rss/search";
    private static final String LOCALE_PARAMS = "hl=en-US&gl=US&ceid=US:en";

    // A real browser UA. The honest "OurCrowdPressMonitor/1.0" string this used to send got
    // us blocked almost immediately — self-identifying as a bot to an endpoint that blocks
    // bots is a losing move. This isn't a way around the rate limits (the throttles above are
    // what keep us polite); it just stops us being singled out before we've sent anything.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // Retries here are for ordinary network flakiness. A block page is not flaky — it's a
    // decision, and retrying it just digs the hole deeper, so that path trips the breaker
    // instead of retrying.
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_START_MS = 500;

    private static final long COOLDOWN_BASE_MS = Duration.ofMinutes(3).toMillis();
    private static final long COOLDOWN_MAX_MS = Duration.ofMinutes(20).toMillis();

    private final RestClient http;
    private final MonitoringProperties props;

    // Caps concurrent RSS requests across every worker thread in a run.
    private final Semaphore rssPermits;

    // Circuit breaker state, shared across those same threads. cooldownUntilMs is epoch
    // millis; consecutiveBlocks drives how long each successive cooldown lasts.
    private final AtomicLong cooldownUntilMs = new AtomicLong(0);
    private final AtomicInteger consecutiveBlocks = new AtomicInteger(0);

    public GoogleNewsRssCollector(@Qualifier("httpRestClient") RestClient http,
                                  MonitoringProperties props) {
        this.http = http;
        this.props = props;
        int permits = Math.max(1, props.getRssConcurrency());
        this.rssPermits = new Semaphore(permits);
        log.info("Google News RSS collector ready: max {} concurrent request(s), ~{}ms between requests",
                permits, props.getRequestDelayMs());
    }

    @Override
    public boolean isBackingOff() {
        return System.currentTimeMillis() < cooldownUntilMs.get();
    }

    @Override
    public List<NewsItem> fetchRecent(Company company, int limit) {
        // Don't even queue up behind the semaphore if we're mid-cooldown — the caller checks
        // isBackingOff() and will come back to this company later.
        if (isBackingOff()) {
            return List.of();
        }

        // Primary query: the exact-quoted name, plus the optional search hint. If that comes
        // back empty, try again unquoted — some companies just aren't matched verbatim. Skip
        // the second attempt if the first one tripped the breaker, since it'd be refused too.
        List<NewsItem> items = fetchForQuery(company.getName(), buildQueryUrl(company, true), limit);
        if (items.isEmpty() && !isBackingOff()) {
            items = fetchForQuery(company.getName(), buildQueryUrl(company, false), limit);
        }
        return items;
    }

    private List<NewsItem> fetchForQuery(String companyName, String url, int limit) {
        String xml = fetchWithRetry(companyName, url);
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        try {
            return parseRss(xml, limit);
        } catch (Exception e) {
            log.warn("Failed to parse RSS for '{}': {}", companyName, e.getMessage());
            return List.of();
        }
    }

    // Fetches the feed, retrying only genuine transient failures with a short exponential
    // backoff. Returns null if we gave up, or if we hit a block page and tripped the breaker.
    private String fetchWithRetry(String companyName, String url) {
        long backoffMs = RETRY_BACKOFF_START_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (isBackingOff()) {
                return null;
            }
            try {
                return fetchOnce(url);
            } catch (BlockedException be) {
                // Already recorded by enterCooldown — nothing to retry here.
                return null;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.warn("News fetch attempt {}/{} failed for '{}': {}",
                        attempt, MAX_ATTEMPTS, companyName, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    if (!sleep(backoffMs)) {
                        return null;
                    }
                    backoffMs *= 2;
                }
            }
        }
        return null;
    }

    // One request, taken under an RSS permit and preceded by the politeness delay. Returns the
    // body on success, throws BlockedException if Google served us a block page, and throws
    // anything else for a transient failure the caller should retry.
    private String fetchOnce(String url) throws InterruptedException {
        rssPermits.acquire();
        try {
            sleepPolitely();

            ResponseEntity<String> response = http.get()
                    .uri(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .retrieve()
                    // Suppress RestClient's default "throw on 4xx/5xx" so we can look at the
                    // status ourselves — a 429/503 here is a bot block to be handled with a
                    // cooldown, not an exception to be retried.
                    .onStatus(status -> true, (request, res) -> { })
                    .toEntity(String.class);

            HttpStatusCode status = response.getStatusCode();
            String body = response.getBody();

            if (status.value() == 429 || status.value() == 503 || looksLikeBlockPage(body)) {
                enterCooldown(status);
                throw new BlockedException();
            }
            if (status.isError()) {
                throw new IllegalStateException("HTTP " + status.value());
            }

            // A clean response means whatever got us blocked has passed; reset the breaker so
            // the next block starts its backoff from the bottom again rather than the top.
            consecutiveBlocks.set(0);
            cooldownUntilMs.set(0);
            return body;
        } finally {
            rssPermits.release();
        }
    }

    /**
     * Whether this response body is Google's bot interstitial rather than an RSS feed.
     *
     * We check the body and not just the status because the block doesn't always come with an
     * error code — sometimes it's a 200 carrying an HTML page. The last condition is the
     * general form of that: we asked for a feed and got a web page.
     */
    private static boolean looksLikeBlockPage(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String head = body.substring(0, Math.min(body.length(), 1000)).toLowerCase(Locale.ROOT);
        return head.contains("automated queries")
                || head.contains("unusual traffic")
                || head.contains("our systems have detected")
                || (head.contains("<html") && !head.contains("<rss"));
    }

    // Trips the breaker. Each consecutive block doubles the pause, up to the 20 minute cap.
    private void enterCooldown(HttpStatusCode status) {
        int blocks = consecutiveBlocks.incrementAndGet();
        long cooldownMs = Math.min(COOLDOWN_MAX_MS, COOLDOWN_BASE_MS << Math.min(blocks - 1, 8));
        long until = System.currentTimeMillis() + cooldownMs;
        // Never shorten a cooldown another thread already set.
        cooldownUntilMs.updateAndGet(prev -> Math.max(prev, until));
        log.warn("Google News is blocking us (HTTP {}, consecutive block #{}). "
                        + "Pausing RSS fetches for ~{} minute(s); affected companies get retried afterwards.",
                status.value(), blocks, Math.max(1, Math.round(cooldownMs / 60000.0)));
    }

    // The politeness delay, jittered by +/-25% so our requests don't arrive on a metronome.
    private void sleepPolitely() throws InterruptedException {
        long base = props.getRequestDelayMs();
        if (base <= 0) {
            return;
        }
        long spread = Math.max(1, base / 4);
        Thread.sleep(base + ThreadLocalRandom.current().nextLong(-spread, spread + 1));
    }

    // Returns false if we were interrupted, so callers can bail out promptly.
    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    String buildQueryUrl(Company company, boolean quoted) {
        StringBuilder q = new StringBuilder();
        if (quoted) {
            q.append('"').append(company.getName()).append('"');
        } else {
            q.append(company.getName());
        }
        if (company.getSearchHint() != null && !company.getSearchHint().isBlank()) {
            q.append(' ').append(company.getSearchHint());
        }
        String encoded = URLEncoder.encode(q.toString(), StandardCharsets.UTF_8);
        return ENDPOINT + "?q=" + encoded + "&" + LOCALE_PARAMS;
    }

    private List<NewsItem> parseRss(String xml, int limit) throws Exception {
        Document doc = secureBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList items = doc.getElementsByTagName("item");
        List<NewsItem> result = new ArrayList<>();
        for (int i = 0; i < items.getLength() && result.size() < limit; i++) {
            Element item = (Element) items.item(i);
            String rawTitle = text(item, "title");
            if (rawTitle == null || rawTitle.isBlank()) {
                continue;
            }
            String source = text(item, "source");
            // Google News titles look like "Headline - Publisher"; strip the trailing source.
            String title = stripTrailingSource(rawTitle, source);
            String link = text(item, "link");
            String snippet = stripHtml(text(item, "description"));
            Instant published = parseDate(text(item, "pubDate"));
            result.add(new NewsItem(title, snippet, link, source, published));
        }
        return result;
    }

    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        // Harden against XXE — we only parse remote XML.
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    private static String text(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) {
            return null;
        }
        Node n = nl.item(0);
        return n.getTextContent() == null ? null : n.getTextContent().trim();
    }

    private static String stripTrailingSource(String title, String source) {
        if (source != null && !source.isBlank() && title.endsWith(" - " + source)) {
            return title.substring(0, title.length() - (" - " + source).length()).trim();
        }
        return title;
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return null;
        }
        String noTags = html.replaceAll("<[^>]+>", " ");
        noTags = noTags.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
        return noTags.replaceAll("\\s+", " ").trim();
    }

    private static Instant parseDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) {
            return null;
        }
        try {
            // RSS pubDate is RFC-1123, e.g. "Tue, 21 Jul 2026 14:03:00 GMT".
            return ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH))
                    .toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    // Internal signal that a fetch hit a block page, so fetchWithRetry knows to stop rather
    // than retry. Never escapes this class.
    private static final class BlockedException extends RuntimeException {
        private BlockedException() {
            super(null, null, false, false);
        }
    }
}
