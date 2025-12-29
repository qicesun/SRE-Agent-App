package com.qicesun.sreagent.tools.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qicesun.sreagent.agent.AgentEventStore;
import com.qicesun.sreagent.agent.SessionContextHolder;
import com.qicesun.sreagent.config.SessionConfig;
import com.qicesun.sreagent.config.SessionConfigStore;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GitLabTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 5;
    private static final Logger log = LoggerFactory.getLogger(GitLabTool.class);

    private final String baseUrl;
    private final String token;
    private final HttpClient httpClient;
    private AgentEventStore eventStore;
    private SessionConfigStore configStore;

    public GitLabTool(
            @Value("${gitlab.url:}") String baseUrl,
            @Value("${gitlab.token:}") String token) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.token = token == null ? "" : token.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Autowired
    public void setEventStore(AgentEventStore eventStore) {
        this.eventStore = eventStore;
    }

    @Autowired
    public void setConfigStore(SessionConfigStore configStore) {
        this.configStore = configStore;
    }

    @Tool("List recent commits for a GitLab project.")
    public String listRecentCommits(
            @P("Project ID (e.g. 'group/project')") String projectId,
            @P("Limit (default 5)") int limit) {
        long startNanos = System.nanoTime();
        SessionConfig config = resolveSessionConfig();
        if (config != null) {
            if (!config.isGitlabEnabled()) {
                return "GitLab is disabled for this session.";
            }
            if (config.getGitlabProjectId() == null || config.getGitlabProjectId().isBlank()) {
                return "GitLab project is not selected for this session.";
            }
            projectId = config.getGitlabProjectId();
        }
        if (projectId == null || projectId.trim().isEmpty()) {
            return "Project ID is required.";
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return "GitLab URL is not configured.";
        }
        if (token.isBlank()) {
            return "GitLab token is not configured.";
        }

        int effectiveLimit = limit > 0 ? limit : DEFAULT_LIMIT;
        String encodedProjectId = encodePathSegment(projectId.trim());
        String endpoint = String.format(
                "%s/api/v4/projects/%s/repository/commits?per_page=%d",
                baseUrl,
                encodedProjectId,
                effectiveLimit);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .GET()
                .header("Accept", "application/json")
                .header("PRIVATE-TOKEN", token)
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (status < 200 || status >= 300) {
                log.warn("listRecentCommits projectId={} status={} elapsedMs={}",
                        projectId, status, (System.nanoTime() - startNanos) / 1_000_000);
                recordEvent("gitlab.listRecentCommits",
                        "Failed to list commits for project=" + projectId + " status=" + status);
                String message = body.isBlank() ? "GitLab API request failed." : body;
                return "Error: " + message;
            }
            JsonNode commits = OBJECT_MAPPER.readTree(body);
            if (commits == null || !commits.isArray() || commits.isEmpty()) {
                log.info("listRecentCommits projectId={} count=0 elapsedMs={}",
                        projectId, (System.nanoTime() - startNanos) / 1_000_000);
                recordEvent("gitlab.listRecentCommits",
                        "No commits found for project=" + projectId);
                return "No commits found for project: " + projectId + ".";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Commit Hash | Author | Message").append("\n");
            for (JsonNode commit : commits) {
                String hash = textOrDefault(commit.get("short_id"),
                        textOrDefault(commit.get("id"), "unknown"));
                String author = textOrDefault(commit.get("author_name"), "Unknown");
                String message = textOrDefault(commit.get("title"),
                        textOrDefault(commit.get("message"), ""));
                message = normalizeMessage(message);
                sb.append(hash)
                        .append(" | ")
                        .append(author)
                        .append(" | ")
                        .append(message)
                        .append("\n");
            }
            log.info("listRecentCommits projectId={} count={} elapsedMs={}",
                    projectId, commits.size(), (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("gitlab.listRecentCommits",
                    "Listed commits for project=" + projectId + " (count=" + commits.size() + ")");
            return sb.toString().trim();
        } catch (IOException e) {
            log.warn("listRecentCommits projectId={} error={}", projectId, e.getMessage());
            recordEvent("gitlab.listRecentCommits",
                    "Failed to list commits for project=" + projectId + " error=" + e.getMessage());
            return "Error: I/O error while calling GitLab API. " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("listRecentCommits projectId={} interrupted", projectId);
            recordEvent("gitlab.listRecentCommits",
                    "List commits interrupted for project=" + projectId);
            return "Error: GitLab request was interrupted.";
        } catch (Exception e) {
            log.warn("listRecentCommits projectId={} error={}", projectId, e.getMessage());
            recordEvent("gitlab.listRecentCommits",
                    "Failed to list commits for project=" + projectId + " error=" + e.getMessage());
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                return "Error: Unexpected error while calling GitLab API.";
            }
            return "Error: " + message;
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

    private SessionConfig resolveSessionConfig() {
        if (configStore == null) {
            return null;
        }
        String sessionId = SessionContextHolder.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return configStore.get(sessionId);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        String value = node.asText();
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private static String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.replaceAll("\\s+", " ").trim();
    }
}
