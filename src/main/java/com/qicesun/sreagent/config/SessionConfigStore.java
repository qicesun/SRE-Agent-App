package com.qicesun.sreagent.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SessionConfigStore {

    private final Map<String, SessionConfig> configs = new ConcurrentHashMap<>();

    public SessionConfig get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return configs.get(sessionId.trim());
    }

    public SessionConfig save(String sessionId, SessionConfig config) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        if (config == null) {
            configs.remove(sessionId.trim());
            return null;
        }
        SessionConfig normalized = normalize(config);
        configs.put(sessionId.trim(), normalized);
        return normalized;
    }

    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        configs.remove(sessionId.trim());
    }

    private static SessionConfig normalize(SessionConfig source) {
        SessionConfig normalized = new SessionConfig();
        normalized.setK8sEnabled(source.isK8sEnabled());
        normalized.setK8sNamespace(trimToNull(source.getK8sNamespace()));
        normalized.setK8sWorkloadKind(trimToNull(source.getK8sWorkloadKind()));
        normalized.setK8sWorkloadName(trimToNull(source.getK8sWorkloadName()));
        normalized.setGitlabEnabled(source.isGitlabEnabled());
        normalized.setGitlabProjectId(trimToNull(source.getGitlabProjectId()));
        normalized.setJiraEnabled(source.isJiraEnabled());
        normalized.setJiraProjectKey(trimToNull(source.getJiraProjectKey()));
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
