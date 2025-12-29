package com.qicesun.sreagent.agent;

import com.qicesun.sreagent.config.SessionConfig;
import com.qicesun.sreagent.config.SessionConfigStore;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DevOpsSystemMessageProvider {

    private final SessionConfigStore configStore;
    private final String gitlabUrl;
    private final String jiraUrl;

    public DevOpsSystemMessageProvider(
            SessionConfigStore configStore,
            @Value("${gitlab.url:}") String gitlabUrl,
            @Value("${jira.url:}") String jiraUrl) {
        this.configStore = Objects.requireNonNull(configStore, "configStore must not be null");
        this.gitlabUrl = normalizeBaseUrl(gitlabUrl);
        this.jiraUrl = normalizeBaseUrl(jiraUrl);
    }

    public String systemMessageFor(Object memoryId) {
        String sessionId = memoryId == null ? "" : memoryId.toString().trim();
        SessionConfig config = configStore.get(sessionId);
        return basePrompt() + System.lineSeparator() + externalConfigBlock(sessionId, config);
    }

    private String basePrompt() {
        return """
                You are an elite Site Reliability Engineer (SRE) Agent.
                Your mission is to autonomously diagnose and resolve system incidents.

                **External Configuration**
                - The server injects the latest external configuration before EVERY turn.
                - Treat injected values as authoritative defaults for tool calls.
                - Do NOT ask the user for Jira project key / GitLab project / Kubernetes scope if they are provided.
                - Never request, output, or store credentials (API keys, tokens). Tools handle authentication.

                **Capability Manifest:**
                1.  **Kubernetes**: Inspect pods (`listPods`), read logs (`getPodLogs`), and fix issues (`restartDeployment`).
                2.  **GitLab**: Check recent commits (`listRecentCommits`) to correlate errors with changes.
                3.  **Jira**: Report bugs (`createIssue`).
                4.  **Web**: Search for error solutions (`scrapeUrl`).

                **Execution Protocol (The 'OODA' Loop):**
                1.  **Observe**: When an alert comes in, FIRST check system status (`listPods`) and logs (`getPodLogs`).
                2.  **Orient**:
                    - If logs show a `NullPointerException` or logic error, check GitLab for recent commits.
                    - If logs show 'Connection Refused' or infrastructure errors, check dependencies.
                    - If the error is obscure, use Web Scraper to learn about it.
                3.  **Decide**:
                    - If it looks like a transient memory leak or stuck process -> Restart.
                    - If it looks like bad code -> Create Jira ticket & notify user.
                4.  **Act**: Execute the decision.

                **Tone**: Concise, Technical, Professional. Always explain your reasoning before taking action.
                """;
    }

    private String externalConfigBlock(String sessionId, SessionConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("<EXTERNAL_CONFIG>").append(System.lineSeparator());
        sb.append("session.id=").append(nullToEmpty(sessionId)).append(System.lineSeparator());
        sb.append("gitlab.baseUrl=").append(nullToEmpty(gitlabUrl)).append(System.lineSeparator());
        sb.append("jira.baseUrl=").append(nullToEmpty(jiraUrl)).append(System.lineSeparator());

        if (config == null) {
            sb.append("session.configured=false").append(System.lineSeparator());
            sb.append("</EXTERNAL_CONFIG>");
            return sb.toString();
        }

        sb.append("session.configured=true").append(System.lineSeparator());
        sb.append("k8s.enabled=").append(config.isK8sEnabled()).append(System.lineSeparator());
        sb.append("k8s.namespace=").append(nullToEmpty(config.getK8sNamespace())).append(System.lineSeparator());
        sb.append("k8s.workload.kind=").append(nullToEmpty(config.getK8sWorkloadKind())).append(System.lineSeparator());
        sb.append("k8s.workload.name=").append(nullToEmpty(config.getK8sWorkloadName())).append(System.lineSeparator());
        sb.append("gitlab.enabled=").append(config.isGitlabEnabled()).append(System.lineSeparator());
        sb.append("gitlab.projectId=").append(nullToEmpty(config.getGitlabProjectId())).append(System.lineSeparator());
        sb.append("jira.enabled=").append(config.isJiraEnabled()).append(System.lineSeparator());
        sb.append("jira.projectKey=").append(nullToEmpty(config.getJiraProjectKey())).append(System.lineSeparator());
        sb.append("</EXTERNAL_CONFIG>");
        return sb.toString();
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
