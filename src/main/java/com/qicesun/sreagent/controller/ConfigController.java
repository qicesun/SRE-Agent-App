package com.qicesun.sreagent.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qicesun.sreagent.config.SessionConfig;
import com.qicesun.sreagent.config.SessionConfigStore;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceList;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentList;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetList;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ConfigController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final SessionConfigStore configStore;
    private final KubernetesClient kubernetesClient;
    private final HttpClient httpClient;
    private final String gitlabUrl;
    private final String gitlabToken;
    private final String jiraUrl;
    private final String jiraToken;
    private final String jiraEmail;

    public ConfigController(
            SessionConfigStore configStore,
            KubernetesClient kubernetesClient,
            @Value("${gitlab.url:}") String gitlabUrl,
            @Value("${gitlab.token:}") String gitlabToken,
            @Value("${jira.url:}") String jiraUrl,
            @Value("${jira.token:}") String jiraToken,
            @Value("${jira.email:}") String jiraEmail) {
        this.configStore = configStore;
        this.kubernetesClient = kubernetesClient;
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
        this.gitlabUrl = normalizeBaseUrl(gitlabUrl);
        this.gitlabToken = trimOrEmpty(gitlabToken);
        this.jiraUrl = normalizeBaseUrl(jiraUrl);
        this.jiraToken = trimOrEmpty(jiraToken);
        this.jiraEmail = trimOrEmpty(jiraEmail);
    }

    @GetMapping("/config/options")
    public ConfigOptionsResponse options() {
        return new ConfigOptionsResponse(loadGitLabOptions(), loadJiraOptions(), loadK8sOptions());
    }

    @GetMapping("/config/k8s/workloads")
    public K8sWorkloadsResponse k8sWorkloads(@RequestParam(name = "namespace") String namespace) {
        String ns = namespace == null ? "" : namespace.trim();
        if (ns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "namespace is required.");
        }
        try {
            List<K8sWorkloadOption> workloads = new ArrayList<>();

            DeploymentList deployments = kubernetesClient.apps().deployments().inNamespace(ns).list();
            if (deployments != null && deployments.getItems() != null) {
                for (Deployment deployment : deployments.getItems()) {
                    String name = deployment.getMetadata() != null ? deployment.getMetadata().getName() : null;
                    if (name != null && !name.isBlank()) {
                        workloads.add(new K8sWorkloadOption(
                                "Deployment:" + name,
                                "Deployment - " + name,
                                "Deployment",
                                name));
                    }
                }
            }

            StatefulSetList statefulSets = kubernetesClient.apps().statefulSets().inNamespace(ns).list();
            if (statefulSets != null && statefulSets.getItems() != null) {
                for (StatefulSet statefulSet : statefulSets.getItems()) {
                    String name = statefulSet.getMetadata() != null ? statefulSet.getMetadata().getName() : null;
                    if (name != null && !name.isBlank()) {
                        workloads.add(new K8sWorkloadOption(
                                "StatefulSet:" + name,
                                "StatefulSet - " + name,
                                "StatefulSet",
                                name));
                    }
                }
            }

            workloads.sort(Comparator.comparing(K8sWorkloadOption::label, String.CASE_INSENSITIVE_ORDER));
            return new K8sWorkloadsResponse(true, null, workloads);
        } catch (Exception ex) {
            return new K8sWorkloadsResponse(false, "Kubernetes API error: " + safeMessage(ex), List.of());
        }
    }

    @GetMapping("/config/session")
    public SessionConfigResponse sessionConfig(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String resolved = requireSessionId(sessionId);
        SessionConfig config = configStore.get(resolved);
        if (config == null) {
            return new SessionConfigResponse(false, new SessionConfig());
        }
        return new SessionConfigResponse(true, config);
    }

    @PostMapping(path = "/config/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateSessionConfig(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody SessionConfig config) {
        String resolved = requireSessionId(sessionId);
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session config is required.");
        }
        SessionConfig normalized = normalizeConfig(config);
        validateConfig(normalized);
        configStore.save(resolved, normalized);
    }

    private ConfigOptions loadGitLabOptions() {
        if (!isGitlabConfigured()) {
            return new ConfigOptions(false, "GitLab not configured.", List.of());
        }
        String endpoint = gitlabUrl + "/api/v4/projects?membership=true&per_page=100&simple=true";
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .header("Accept", "application/json")
                .header("PRIVATE-TOKEN", gitlabToken)
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ConfigOptions(true,
                        "GitLab API request failed (status " + response.statusCode() + ").",
                        List.of());
            }
            List<ProjectOption> projects = parseGitLabProjects(response.body());
            projects.sort(Comparator.comparing(ProjectOption::label, String.CASE_INSENSITIVE_ORDER));
            return new ConfigOptions(true, null, projects);
        } catch (Exception ex) {
            return new ConfigOptions(true, "GitLab API error: " + safeMessage(ex), List.of());
        }
    }

    private ConfigOptions loadJiraOptions() {
        if (!isJiraConfigured()) {
            return new ConfigOptions(false, "Jira not configured.", List.of());
        }
        String endpoint = jiraUrl + "/rest/api/3/project/search?maxResults=100";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(HTTP_TIMEOUT)
                .GET()
                .header("Accept", "application/json");
        String authHeader = jiraAuthHeader();
        if (authHeader != null && !authHeader.isBlank()) {
            builder.header("Authorization", authHeader);
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ConfigOptions(true,
                        "Jira API request failed (status " + response.statusCode() + ").",
                        List.of());
            }
            List<ProjectOption> projects = parseJiraProjects(response.body());
            projects.sort(Comparator.comparing(ProjectOption::label, String.CASE_INSENSITIVE_ORDER));
            return new ConfigOptions(true, null, projects);
        } catch (Exception ex) {
            return new ConfigOptions(true, "Jira API error: " + safeMessage(ex), List.of());
        }
    }

    private ConfigOptions loadK8sOptions() {
        try {
            NamespaceList namespaceList = kubernetesClient.namespaces().list();
            if (namespaceList == null || namespaceList.getItems() == null || namespaceList.getItems().isEmpty()) {
                return new ConfigOptions(true, "No namespaces found.", List.of());
            }
            List<ProjectOption> namespaces = new ArrayList<>();
            for (Namespace namespace : namespaceList.getItems()) {
                String name = namespace.getMetadata() != null ? namespace.getMetadata().getName() : null;
                if (name != null && !name.isBlank()) {
                    namespaces.add(new ProjectOption(name, name));
                }
            }
            namespaces.sort(Comparator.comparing(ProjectOption::label, String.CASE_INSENSITIVE_ORDER));
            return new ConfigOptions(true, null, namespaces);
        } catch (Exception ex) {
            return new ConfigOptions(false, "Kubernetes API error: " + safeMessage(ex), List.of());
        }
    }

    private List<ProjectOption> parseGitLabProjects(String body) {
        List<ProjectOption> projects = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return projects;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            if (node == null || !node.isArray()) {
                return projects;
            }
            for (JsonNode item : node) {
                String path = textOrDefault(item, "path_with_namespace");
                if (path.isBlank()) {
                    path = textOrDefault(item, "name_with_namespace");
                }
                if (path.isBlank()) {
                    path = textOrDefault(item, "name");
                }
                if (!path.isBlank()) {
                    projects.add(new ProjectOption(path, path));
                }
            }
        } catch (Exception ignored) {
            return projects;
        }
        return projects;
    }

    private List<ProjectOption> parseJiraProjects(String body) {
        List<ProjectOption> projects = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return projects;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            JsonNode values = node.get("values");
            if (values == null || !values.isArray()) {
                return projects;
            }
            for (JsonNode item : values) {
                String key = textOrDefault(item, "key");
                String name = textOrDefault(item, "name");
                if (!key.isBlank()) {
                    String label = name.isBlank() ? key : key + " - " + name;
                    projects.add(new ProjectOption(key, label));
                }
            }
        } catch (Exception ignored) {
            return projects;
        }
        return projects;
    }

    private void validateConfig(SessionConfig config) {
        if (config.isK8sEnabled()) {
            if (config.getK8sNamespace() == null || config.getK8sNamespace().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Kubernetes namespace selection is required.");
            }
            if (config.getK8sWorkloadKind() == null || config.getK8sWorkloadKind().isBlank()
                    || config.getK8sWorkloadName() == null || config.getK8sWorkloadName().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Kubernetes workload selection is required.");
            }
            String kind = config.getK8sWorkloadKind().trim();
            if (!kind.equalsIgnoreCase("Deployment") && !kind.equalsIgnoreCase("StatefulSet")) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Kubernetes workload kind must be Deployment or StatefulSet.");
            }
        }
        if (config.isGitlabEnabled()) {
            if (!isGitlabConfigured()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "GitLab is not configured on this server.");
            }
            if (config.getGitlabProjectId() == null || config.getGitlabProjectId().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "GitLab project selection is required.");
            }
        }
        if (config.isJiraEnabled()) {
            if (!isJiraConfigured()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Jira is not configured on this server.");
            }
            if (config.getJiraProjectKey() == null || config.getJiraProjectKey().isBlank()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Jira project selection is required.");
            }
        }
    }

    private static SessionConfig normalizeConfig(SessionConfig config) {
        SessionConfig normalized = new SessionConfig();
        normalized.setK8sEnabled(config.isK8sEnabled());
        normalized.setK8sNamespace(trimToNull(config.getK8sNamespace()));
        normalized.setK8sWorkloadKind(trimToNull(config.getK8sWorkloadKind()));
        normalized.setK8sWorkloadName(trimToNull(config.getK8sWorkloadName()));
        normalized.setGitlabEnabled(config.isGitlabEnabled());
        normalized.setGitlabProjectId(trimToNull(config.getGitlabProjectId()));
        normalized.setJiraEnabled(config.isJiraEnabled());
        normalized.setJiraProjectKey(trimToNull(config.getJiraProjectKey()));
        if (!normalized.isK8sEnabled()) {
            normalized.setK8sNamespace(null);
            normalized.setK8sWorkloadKind(null);
            normalized.setK8sWorkloadName(null);
        }
        if (!normalized.isGitlabEnabled()) {
            normalized.setGitlabProjectId(null);
        }
        if (!normalized.isJiraEnabled()) {
            normalized.setJiraProjectKey(null);
        }
        return normalized;
    }

    private boolean isGitlabConfigured() {
        return gitlabUrl != null && !gitlabUrl.isBlank()
                && gitlabToken != null && !gitlabToken.isBlank();
    }

    private boolean isJiraConfigured() {
        return jiraUrl != null && !jiraUrl.isBlank()
                && jiraToken != null && !jiraToken.isBlank();
    }

    private String jiraAuthHeader() {
        if (jiraToken == null || jiraToken.isBlank()) {
            return null;
        }
        if (jiraEmail == null || jiraEmail.isBlank()) {
            return "Bearer " + jiraToken;
        }
        String raw = jiraEmail + ":" + jiraToken;
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Session-Id header is required.");
        }
        return sessionId.trim();
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

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String textOrDefault(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        String text = value.asText();
        return text == null ? "" : text.trim();
    }

    private static String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "Unexpected error" : message;
    }

    public record ProjectOption(String id, String label) {
    }

    public record ConfigOptions(boolean configured, String error, List<ProjectOption> projects) {
    }

    public record ConfigOptionsResponse(ConfigOptions gitlab, ConfigOptions jira, ConfigOptions k8s) {
    }

    public record SessionConfigResponse(boolean configured, SessionConfig config) {
    }

    public record K8sWorkloadOption(String id, String label, String kind, String name) {
    }

    public record K8sWorkloadsResponse(boolean configured, String error, List<K8sWorkloadOption> workloads) {
    }
}
