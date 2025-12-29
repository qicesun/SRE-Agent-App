package com.qicesun.sreagent.tools.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qicesun.sreagent.agent.AgentEventStore;
import com.qicesun.sreagent.agent.SessionContextHolder;
import com.qicesun.sreagent.config.SessionConfig;
import com.qicesun.sreagent.config.SessionConfigStore;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Agent-ready Jira tool backed by {@link JiraClient}.
 */
@Component
public final class JiraTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_RESULTS = 5;
    private static final int DESCRIPTION_LIMIT = 500;
    private static final Logger log = LoggerFactory.getLogger(JiraTool.class);

    private final JiraClient client;
    private final String jiraUrl;
    private final String jiraToken;
    private AgentEventStore eventStore;
    private SessionConfigStore configStore;

    @Autowired
    public JiraTool(
            JiraClient client,
            @Value("${jira.url:}") String jiraUrl,
            @Value("${jira.token:}") String jiraToken) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.jiraUrl = jiraUrl == null ? "" : jiraUrl.trim();
        this.jiraToken = jiraToken == null ? "" : jiraToken.trim();
    }

    public JiraTool(JiraClient client) {
        this(client, "http://localhost", "test-token");
    }

    @Autowired
    public void setEventStore(AgentEventStore eventStore) {
        this.eventStore = eventStore;
    }

    @Autowired
    public void setConfigStore(SessionConfigStore configStore) {
        this.configStore = configStore;
    }

    /**
     * Creates a new builder for {@link JiraTool}.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Tool("Get Jira issue details by key.")
    public String getIssue(@P("Issue key, e.g. PROJ-123") String key) {
        long startNanos = System.nanoTime();
        String disabledMessage = resolveDisabledMessage();
        if (disabledMessage != null) {
            return disabledMessage;
        }
        if (!isJiraConfigured()) {
            return "Jira is not configured.";
        }
        try {
            JsonNode issue = client.getIssue(key);
            String issueKey = textOrDefault(issue.get("key"), "(unknown key)");
            JsonNode fields = issue.get("fields");
            String summary = textOrDefault(getNode(fields, "summary"), "(no summary)");
            String status = textOrDefault(getNode(fields, "status", "name"), "Unknown");
            String priority = textOrDefault(getNode(fields, "priority", "name"), "Unspecified");
            String assignee = textOrDefault(getNode(fields, "assignee", "displayName"), "Unassigned");
            JsonNode descriptionNode = getNode(fields, "description");
            String descriptionText = extractDescription(descriptionNode);
            String descriptionLine = buildDescriptionLine(descriptionNode, descriptionText);

            log.info("getIssue key={} elapsedMs={}", issueKey,
                    (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("jira.getIssue", "Fetched issue " + issueKey);
            return String.format(
                    "[%s] %s (%s) | Assignee: %s | Priority: %s%n%s",
                    issueKey, summary, status, assignee, priority, descriptionLine);
        } catch (JiraClient.JiraClientException e) {
            log.warn("getIssue key={} error={}", key, e.getMessage());
            recordEvent("jira.getIssue", "Failed to fetch issue " + key + " error=" + e.getMessage());
            return formatError(e);
        } catch (RuntimeException e) {
            log.warn("getIssue key={} error={}", key, e.getMessage());
            recordEvent("jira.getIssue", "Failed to fetch issue " + key + " error=" + e.getMessage());
            return formatError(e);
        }
    }

    @Tool("Search Jira issues using JQL. Returns up to 5 results.")
    public String searchIssues(@P("JQL query string") String jql) {
        long startNanos = System.nanoTime();
        String disabledMessage = resolveDisabledMessage();
        if (disabledMessage != null) {
            return disabledMessage;
        }
        if (!isJiraConfigured()) {
            return "Jira is not configured.";
        }
        try {
            JsonNode searchResponse = client.searchIssues(jql, DEFAULT_MAX_RESULTS);
            JsonNode issues = searchResponse.get("issues");
            if (issues == null || !issues.isArray() || issues.isEmpty()) {
                log.info("searchIssues jqlLength={} count=0 elapsedMs={}",
                        jql == null ? 0 : jql.length(), (System.nanoTime() - startNanos) / 1_000_000);
                recordEvent("jira.searchIssues", "Search returned no issues");
                return "No issues found.";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode issue : issues) {
                String issueKey = textOrDefault(issue.get("key"), "(unknown key)");
                JsonNode fields = issue.get("fields");
                String summary = textOrDefault(getNode(fields, "summary"), "(no summary)");
                String status = textOrDefault(getNode(fields, "status", "name"), "Unknown");
                String priority = textOrDefault(getNode(fields, "priority", "name"), "Unspecified");
                String assignee = textOrDefault(getNode(fields, "assignee", "displayName"), "Unassigned");
                if (sb.length() > 0) {
                    sb.append(System.lineSeparator());
                }
                sb.append(String.format(
                        "- [%s] %s (%s) | Assignee: %s | Priority: %s", issueKey, summary, status, assignee, priority));
            }
            log.info("searchIssues jqlLength={} count={} elapsedMs={}",
                    jql == null ? 0 : jql.length(), issues.size(), (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("jira.searchIssues", "Search returned " + issues.size() + " issues");
            return sb.toString();
        } catch (JiraClient.JiraClientException e) {
            log.warn("searchIssues jqlLength={} error={}",
                    jql == null ? 0 : jql.length(), e.getMessage());
            recordEvent("jira.searchIssues", "Search failed error=" + e.getMessage());
            return formatError(e);
        } catch (RuntimeException e) {
            log.warn("searchIssues jqlLength={} error={}",
                    jql == null ? 0 : jql.length(), e.getMessage());
            recordEvent("jira.searchIssues", "Search failed error=" + e.getMessage());
            return formatError(e);
        }
    }

    @Tool("Create a Jira issue with summary and description.")
    public String createIssue(
            @P("Project key, e.g. PROJ") String projectKey,
            @P("Issue summary") String summary,
            @P("Issue description text") String description,
            @P("Issue type name, default Task") String issueType,
            @P("Priority name, default Medium") String priority) {
        long startNanos = System.nanoTime();
        SessionConfig config = resolveSessionConfig();
        if (config != null && !config.isJiraEnabled()) {
            return "Jira is disabled for this session.";
        }
        if (!isJiraConfigured()) {
            return "Jira is not configured.";
        }
        String resolvedProjectKey = projectKey;
        if (config != null && config.getJiraProjectKey() != null && !config.getJiraProjectKey().isBlank()) {
            resolvedProjectKey = config.getJiraProjectKey();
        }
        if (resolvedProjectKey == null || resolvedProjectKey.isBlank()) {
            return "Jira project key is required for this session.";
        }
        try {
            ObjectNode fields = OBJECT_MAPPER.createObjectNode();
            fields.putObject("project").put("key", resolvedProjectKey);
            fields.put("summary", summary);
            fields.putObject("issuetype").put("name", defaultIfBlank(issueType, "Task"));
            fields.putObject("priority").put("name", defaultIfBlank(priority, "Medium"));
            fields.set("description", toADF(description));

            ObjectNode payload = OBJECT_MAPPER.createObjectNode();
            payload.set("fields", fields);

            JsonNode response = client.createIssue(payload);
            String key = textOrDefault(response.get("key"), "(unknown key)");
            log.info("createIssue projectKey={} issueType={} elapsedMs={}",
                    resolvedProjectKey, defaultIfBlank(issueType, "Task"),
                    (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("jira.createIssue", "Created issue " + key + " in project=" + resolvedProjectKey);
            return "Created issue [" + key + "]";
        } catch (JiraClient.JiraClientException e) {
            log.warn("createIssue projectKey={} error={}", resolvedProjectKey, e.getMessage());
            recordEvent("jira.createIssue", "Failed to create issue in project=" + resolvedProjectKey + " error=" + e.getMessage());
            return formatError(e);
        } catch (RuntimeException e) {
            log.warn("createIssue projectKey={} error={}", resolvedProjectKey, e.getMessage());
            recordEvent("jira.createIssue", "Failed to create issue in project=" + resolvedProjectKey + " error=" + e.getMessage());
            return formatError(e);
        }
    }

    @Tool("Add a comment to a Jira issue.")
    public String addComment(@P("Issue key, e.g. PROJ-123") String issueKey, @P("Comment text") String body) {
        long startNanos = System.nanoTime();
        String disabledMessage = resolveDisabledMessage();
        if (disabledMessage != null) {
            return disabledMessage;
        }
        if (!isJiraConfigured()) {
            return "Jira is not configured.";
        }
        try {
            ObjectNode payload = OBJECT_MAPPER.createObjectNode();
            payload.set("body", toADF(body));
            client.addComment(issueKey, payload);
            log.info("addComment issueKey={} bodyLength={} elapsedMs={}",
                    issueKey, body == null ? 0 : body.length(),
                    (System.nanoTime() - startNanos) / 1_000_000);
            recordEvent("jira.addComment", "Added comment to issue " + issueKey);
            return "Comment added";
        } catch (JiraClient.JiraClientException e) {
            log.warn("addComment issueKey={} error={}", issueKey, e.getMessage());
            recordEvent("jira.addComment", "Failed to add comment to issue " + issueKey + " error=" + e.getMessage());
            return formatError(e);
        } catch (RuntimeException e) {
            log.warn("addComment issueKey={} error={}", issueKey, e.getMessage());
            recordEvent("jira.addComment", "Failed to add comment to issue " + issueKey + " error=" + e.getMessage());
            return formatError(e);
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

    private String resolveDisabledMessage() {
        SessionConfig config = resolveSessionConfig();
        if (config != null && !config.isJiraEnabled()) {
            return "Jira is disabled for this session.";
        }
        return null;
    }

    private boolean isJiraConfigured() {
        return jiraUrl != null && !jiraUrl.isBlank()
                && jiraToken != null && !jiraToken.isBlank();
    }

    private static String extractDescription(JsonNode descriptionNode) {
        if (descriptionNode == null || descriptionNode.isNull()) {
            return "";
        }
        String extracted = extractAdfText(descriptionNode).trim();
        if (extracted.isEmpty()) {
            return "";
        }
        return truncate(extracted, DESCRIPTION_LIMIT);
    }

    private static String buildDescriptionLine(JsonNode descriptionNode, String descriptionText) {
        if (descriptionNode == null || descriptionNode.isNull()) {
            return "Description: (none)";
        }
        if (descriptionText.isEmpty()) {
            return "Description: (ADF content available)";
        }
        return "Description: " + descriptionText;
    }

    private static String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "...";
    }

    private static String extractAdfText(JsonNode adfNode) {
        if (adfNode == null || adfNode.isNull()) {
            return "";
        }
        if (adfNode.has("content") && adfNode.get("content").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode child : adfNode.get("content")) {
                String childText = extractAdfText(child).trim();
                if (!childText.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(System.lineSeparator());
                    }
                    sb.append(childText);
                }
            }
            return sb.toString();
        }
        if (adfNode.has("text")) {
            return adfNode.get("text").asText("");
        }
        return "";
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
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

    private static JsonNode getNode(JsonNode root, String... path) {
        JsonNode current = root;
        for (String segment : path) {
            if (current == null || current.isNull()) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }

    private static String formatError(RuntimeException exception) {
        if (exception instanceof JiraClient.JiraClientException jiraException) {
            String message = extractErrorMessage(jiraException.getResponseBody());
            if (message == null || message.isBlank()) {
                if (jiraException.getStatusCode() > 0) {
                    message = "Jira API request failed with status " + jiraException.getStatusCode();
                } else {
                    message = "Jira API request failed";
                }
            }
            return "Error: " + message;
        }
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = "Unexpected error while calling Jira";
        }
        return "Error: " + message;
    }

    private static String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(responseBody);
            JsonNode errorMessages = node.get("errorMessages");
            if (errorMessages != null && errorMessages.isArray() && errorMessages.size() > 0) {
                return errorMessages.get(0).asText();
            }
            JsonNode errors = node.get("errors");
            if (errors != null && errors.isObject()) {
                var fields = errors.fieldNames();
                if (fields.hasNext()) {
                    String field = fields.next();
                    return field + ": " + errors.get(field).asText();
                }
            }
            JsonNode message = node.get("message");
            if (message != null && !message.isNull()) {
                return message.asText();
            }
        } catch (Exception ignored) {
            return truncate(responseBody, 200);
        }
        return truncate(responseBody, 200);
    }

    private static ObjectNode toADF(String text) {
        String safeText = text == null ? "" : text;
        ObjectNode doc = OBJECT_MAPPER.createObjectNode();
        doc.put("version", 1);
        doc.put("type", "doc");
        ArrayNode content = doc.putArray("content");
        ObjectNode paragraph = content.addObject();
        paragraph.put("type", "paragraph");
        ArrayNode paragraphContent = paragraph.putArray("content");
        ObjectNode textNode = paragraphContent.addObject();
        textNode.put("type", "text");
        textNode.put("text", safeText);
        return doc;
    }

    /**
     * Builder for {@link JiraTool}.
     */
    public static final class Builder {
        private String baseUrl;
        private JiraClient.Authentication authentication;
        private Duration timeout = Duration.ofSeconds(30);

        private Builder() {}

        /**
         * Sets Jira base URL, e.g. {@code https://your-domain.atlassian.net}.
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Sets Jira authentication strategy.
         */
        public Builder authentication(JiraClient.Authentication authentication) {
            this.authentication = authentication;
            return this;
        }

        /**
         * Sets request timeout.
         */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
            return this;
        }

        /**
         * Builds a {@link JiraTool} with an underlying {@link JiraClient}.
         */
        public JiraTool build() {
            JiraClient client = JiraClient.builder()
                    .baseUrl(baseUrl)
                    .authentication(authentication)
                    .timeout(timeout)
                    .build();
            return new JiraTool(client);
        }
    }
}
