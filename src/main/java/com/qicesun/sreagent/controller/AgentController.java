package com.qicesun.sreagent.controller;

import com.qicesun.sreagent.agent.AgentEventStore;
import com.qicesun.sreagent.agent.DevOpsAssistant;
import com.qicesun.sreagent.agent.SessionContextHolder;
import com.qicesun.sreagent.config.SessionConfig;
import com.qicesun.sreagent.config.SessionConfigStore;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

@RestController
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final DevOpsAssistant assistant;
    private final AgentEventStore eventStore;
    private final SessionConfigStore configStore;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public AgentController(
            DevOpsAssistant assistant,
            AgentEventStore eventStore,
            SessionConfigStore configStore) {
        this.assistant = assistant;
        this.eventStore = eventStore;
        this.configStore = configStore;
    }

    @PostMapping(path = "/chat/stream", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseBodyEmitter chatStream(
            @RequestBody String query,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        String resolvedSessionId = resolveSessionId(sessionId);
        String trimmedQuery = query == null ? "" : query.trim();
        if (trimmedQuery.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User query is required.");
        }
        String configError = validateSessionConfig(resolvedSessionId);
        if (configError != null) {
            eventStore.record(resolvedSessionId, "agent.error", configError);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, configError);
        }
        int requestLength = trimmedQuery.length();
        log.info("chat stream request sessionId={} length={}", resolvedSessionId, requestLength);
        eventStore.record(resolvedSessionId, "agent.request", "User query length=" + requestLength);
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        emitter.onTimeout(() -> withSession(resolvedSessionId, () -> {
            log.warn("chat stream timeout sessionId={}", resolvedSessionId);
            eventStore.record(resolvedSessionId, "agent.error", "Stream timeout");
            emitter.complete();
        }));
        emitter.onCompletion(() -> withSession(resolvedSessionId, () -> {
            log.info("chat stream completed sessionId={}", resolvedSessionId);
        }));
        streamExecutor.execute(() -> {
            long startNanos = System.nanoTime();
            try {
                String response;
                SessionContextHolder.setSessionId(resolvedSessionId);
                try {
                    response = assistant.chat(resolvedSessionId, trimmedQuery);
                } finally {
                    SessionContextHolder.clear();
                }
                String safeResponse = response == null ? "" : response;
                eventStore.record(resolvedSessionId, "agent.stream", "Streaming response");
                streamInChunks(emitter, safeResponse);
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                int length = safeResponse.length();
                log.info("chat response sessionId={} length={} elapsedMs={}",
                        resolvedSessionId, length, elapsedMs);
                eventStore.record(resolvedSessionId, "agent.response",
                        "Agent response length=" + length + " elapsedMs=" + elapsedMs);
                emitter.complete();
            } catch (Exception e) {
                log.warn("chat stream error sessionId={} error={}", resolvedSessionId, e.getMessage());
                eventStore.record(resolvedSessionId, "agent.error", "error=" + e.getMessage());
                try {
                    emitter.send("Error: " + safeErrorMessage(e), MediaType.TEXT_PLAIN);
                } catch (IOException ignored) {
                    // ignore
                } finally {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    private static String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId.trim();
    }

    private static String safeErrorMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "Unexpected error";
        }
        return message;
    }

    private static void withSession(String sessionId, Runnable action) {        
        SessionContextHolder.setSessionId(sessionId);
        try {
            action.run();
        } finally {
            SessionContextHolder.clear();
        }
    }

    private static void streamInChunks(ResponseBodyEmitter emitter, String response) throws IOException {
        String payload = response == null ? "" : response;
        if (payload.isBlank()) {
            emitter.send("No response received.", MediaType.TEXT_PLAIN);
            return;
        }
        int chunkSize = 128;
        for (int i = 0; i < payload.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, payload.length());
            emitter.send(payload.substring(i, end), MediaType.TEXT_PLAIN);
        }
    }

    private String validateSessionConfig(String sessionId) {
        if (configStore == null) {
            return null;
        }
        SessionConfig config = configStore.get(sessionId);
        if (config == null) {
            return "Session configuration is required before chatting.";
        }
        if (config.isK8sEnabled()) {
            if (config.getK8sNamespace() == null || config.getK8sNamespace().isBlank()) {
                return "Kubernetes namespace selection is required for this session.";
            }
            if (config.getK8sWorkloadKind() == null || config.getK8sWorkloadKind().isBlank()
                    || config.getK8sWorkloadName() == null || config.getK8sWorkloadName().isBlank()) {
                return "Kubernetes workload selection is required for this session.";
            }
        }
        if (config.isGitlabEnabled()
                && (config.getGitlabProjectId() == null || config.getGitlabProjectId().isBlank())) {
            return "GitLab project selection is required for this session.";
        }
        if (config.isJiraEnabled()
                && (config.getJiraProjectKey() == null || config.getJiraProjectKey().isBlank())) {
            return "Jira project selection is required for this session.";
        }
        return null;
    }

    @PreDestroy
    public void shutdown() {
        streamExecutor.shutdownNow();
    }
}
