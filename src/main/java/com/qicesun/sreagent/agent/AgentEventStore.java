package com.qicesun.sreagent.agent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AgentEventStore {

    private static final int MAX_EVENTS_PER_SESSION = 200;

    private final Map<String, Deque<AgentEvent>> events = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> streams = new ConcurrentHashMap<>();

    public void record(String sessionId, String source, String message) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String safeSource = source == null ? "unknown" : source;
        String safeMessage = message == null ? "" : message;

        long id = counters.computeIfAbsent(sessionId, key -> new AtomicLong()).incrementAndGet();
        AgentEvent event = new AgentEvent(id, Instant.now(), safeSource, safeMessage);

        Deque<AgentEvent> deque = events.computeIfAbsent(sessionId, key -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(event);
            while (deque.size() > MAX_EVENTS_PER_SESSION) {
                deque.removeFirst();
            }
        }
        broadcast(sessionId, event);
    }

    public List<AgentEvent> list(String sessionId, long afterId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        Deque<AgentEvent> deque = events.get(sessionId);
        if (deque == null) {
            return List.of();
        }
        List<AgentEvent> results = new ArrayList<>();
        synchronized (deque) {
            for (AgentEvent event : deque) {
                if (event.id() > afterId) {
                    results.add(event);
                }
            }
        }
        return results;
    }

    public SseEmitter openStream(String sessionId, long afterId) {
        SseEmitter emitter = new SseEmitter(0L);
        if (sessionId == null || sessionId.isBlank()) {
            emitter.complete();
            return emitter;
        }
        CopyOnWriteArrayList<SseEmitter> sessionEmitters =
                streams.computeIfAbsent(sessionId, key -> new CopyOnWriteArrayList<>());
        sessionEmitters.add(emitter);
        emitter.onCompletion(() -> removeEmitter(sessionId, emitter));
        emitter.onTimeout(() -> removeEmitter(sessionId, emitter));
        emitter.onError(error -> removeEmitter(sessionId, emitter));

        List<AgentEvent> backlog = list(sessionId, afterId);
        for (AgentEvent event : backlog) {
            if (!sendEvent(emitter, event)) {
                break;
            }
        }
        return emitter;
    }

    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        events.remove(sessionId);
        counters.remove(sessionId);
        List<SseEmitter> emitters = streams.remove(sessionId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                emitter.complete();
            }
        }
    }

    private void broadcast(String sessionId, AgentEvent event) {
        List<SseEmitter> emitters = streams.get(sessionId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            if (!sendEvent(emitter, event)) {
                emitters.remove(emitter);
            }
        }
    }

    private boolean sendEvent(SseEmitter emitter, AgentEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name("agent-event")
                    .id(Long.toString(event.id()))
                    .data(event));
            return true;
        } catch (Exception ex) {
            emitter.completeWithError(ex);
            return false;
        }
    }

    private void removeEmitter(String sessionId, SseEmitter emitter) {
        List<SseEmitter> emitters = streams.get(sessionId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }
}
