package com.qicesun.sreagent.controller;

import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {

    private final KubernetesClient kubernetesClient;
    private final String openAiApiKey;
    private final String openAiModel;
    private final String gitlabUrl;
    private final String gitlabToken;
    private final String jiraUrl;
    private final String jiraEmail;
    private final String jiraToken;

    public StatusController(
            KubernetesClient kubernetesClient,
            @Value("${langchain4j.open-ai.api-key:}") String openAiApiKey,
            @Value("${langchain4j.open-ai.model:}") String openAiModel,
            @Value("${gitlab.url:}") String gitlabUrl,
            @Value("${gitlab.token:}") String gitlabToken,
            @Value("${jira.url:}") String jiraUrl,
            @Value("${jira.email:}") String jiraEmail,
            @Value("${jira.token:}") String jiraToken) {
        this.kubernetesClient = kubernetesClient;
        this.openAiApiKey = openAiApiKey;
        this.openAiModel = openAiModel;
        this.gitlabUrl = gitlabUrl;
        this.gitlabToken = gitlabToken;
        this.jiraUrl = jiraUrl;
        this.jiraEmail = jiraEmail;
        this.jiraToken = jiraToken;
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(
                k8sStatus(),
                openAiStatus(),
                gitlabStatus(),
                jiraStatus()
        );
    }

    private ComponentStatus k8sStatus() {
        String masterUrl = null;
        String namespace = null;
        try {
            masterUrl = kubernetesClient.getConfiguration().getMasterUrl();
            namespace = kubernetesClient.getConfiguration().getNamespace();
        } catch (Exception ignored) {
            // best-effort status
        }
        boolean configured = masterUrl != null && !masterUrl.isBlank();
        Map<String, String> details = new LinkedHashMap<>();
        if (masterUrl != null && !masterUrl.isBlank()) {
            details.put("masterUrl", masterUrl);
        }
        if (namespace != null && !namespace.isBlank()) {
            details.put("namespace", namespace);
        }
        return new ComponentStatus(configured, configured ? "configured" : "unknown", details);
    }

    private ComponentStatus openAiStatus() {
        boolean configured = openAiApiKey != null && !openAiApiKey.isBlank();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("model", openAiModel == null || openAiModel.isBlank() ? "unknown" : openAiModel);
        details.put("apiKey", configured ? maskToken(openAiApiKey) : "missing");
        return new ComponentStatus(configured, configured ? "configured" : "missing", details);
    }

    private ComponentStatus gitlabStatus() {
        boolean configured = gitlabUrl != null && !gitlabUrl.isBlank()
                && gitlabToken != null && !gitlabToken.isBlank();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("url", gitlabUrl == null || gitlabUrl.isBlank() ? "missing" : gitlabUrl);
        details.put("token", configured ? maskToken(gitlabToken) : "missing");
        return new ComponentStatus(configured, configured ? "configured" : "missing", details);
    }

    private ComponentStatus jiraStatus() {
        boolean configured = jiraUrl != null && !jiraUrl.isBlank()
                && jiraToken != null && !jiraToken.isBlank();
        Map<String, String> details = new LinkedHashMap<>();
        details.put("url", jiraUrl == null || jiraUrl.isBlank() ? "missing" : jiraUrl);
        details.put("email", jiraEmail == null || jiraEmail.isBlank() ? "not set" : maskEmail(jiraEmail));
        details.put("token", configured ? maskToken(jiraToken) : "missing");
        return new ComponentStatus(configured, configured ? "configured" : "missing", details);
    }

    private static String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "missing";
        }
        String trimmed = token.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        String head = trimmed.substring(0, 4);
        String tail = trimmed.substring(trimmed.length() - 4);
        return head + "..." + tail;
    }

    private static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "missing";
        }
        int at = email.indexOf('@');
        if (at <= 1) {
            return "***" + email.substring(at);
        }
        String head = email.substring(0, 1);
        String tail = email.substring(at);
        return head + "***" + tail;
    }

    public record ComponentStatus(boolean configured, String status, Map<String, String> details) {
    }

    public record StatusResponse(
            ComponentStatus k8s,
            ComponentStatus openai,
            ComponentStatus gitlab,
            ComponentStatus jira) {
    }
}
