package com.qicesun.sreagent.tools.webscraper;

import com.qicesun.sreagent.agent.AgentEventStore;
import com.qicesun.sreagent.agent.SessionContextHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A tool that fetches a web page and converts it to a lightweight Markdown representation.
 */
@Component
public class WebScraperTool {

    private static final Logger log = LoggerFactory.getLogger(WebScraperTool.class);

    private final WebScraperClient client;
    private AgentEventStore eventStore;

    /**
     * Creates a tool with the default {@link WebScraperClient}.
     */
    public WebScraperTool() {
        this(new WebScraperClient());
    }

    /**
     * Creates a tool with a provided {@link WebScraperClient}.
     *
     * @param client the client used to fetch and convert HTML
     */
    public WebScraperTool(WebScraperClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Autowired
    public void setEventStore(AgentEventStore eventStore) {
        this.eventStore = eventStore;
    }

    /**
     * Fetches the URL and returns a Markdown summary of the page content.
     *
     * @param url the URL to scrape
     * @return Markdown text or a user-friendly error message
     */
    @Tool("Scrape a web page and return Markdown text.")
    public String scrapeUrl(@P("The URL to scrape") String url) {
        long startNanos = System.nanoTime();
        try {
            String result = client.scrapeToMarkdown(url);
            if (result == null || result.isBlank()) {
                log.info("scrapeUrl urlLength={} result=empty elapsedMs={}",
                        url == null ? 0 : url.length(), (System.nanoTime() - startNanos) / 1_000_000);
                recordEvent("web.scrapeUrl", "No content extracted (urlLength=" + (url == null ? 0 : url.length()) + ")");
                return "No content extracted from URL.";
            }
            log.info("scrapeUrl urlLength={} resultLength={} elapsedMs={}",
                    url == null ? 0 : url.length(), result.length(),
                    (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("web.scrapeUrl", "Scraped content (urlLength=" + (url == null ? 0 : url.length())
                    + ", resultLength=" + result.length() + ")");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("scrapeUrl urlLength={} interrupted", url == null ? 0 : url.length());
            recordEvent("web.scrapeUrl", "Scrape interrupted (urlLength=" + (url == null ? 0 : url.length()) + ")");
            return "Error: Scraping was interrupted.";
        } catch (Exception e) {
            log.warn("scrapeUrl urlLength={} error={}", url == null ? 0 : url.length(), e.getMessage());
            recordEvent("web.scrapeUrl", "Scrape failed (urlLength=" + (url == null ? 0 : url.length())
                    + ") error=" + e.getMessage());
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                return "Error: Failed to fetch URL.";
            }
            return "Error: Failed to fetch URL. " + message;
        }
    }

    private void recordEvent(String source, String message) {
        if (eventStore == null) {
            return;
        }
        String sessionId = SessionContextHolder.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        eventStore.record(sessionId, source, message);
    }
}
