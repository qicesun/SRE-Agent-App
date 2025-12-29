package com.qicesun.sreagent.controller;

import com.qicesun.sreagent.agent.AgentEventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AgentEventsController {

    private final AgentEventStore eventStore;

    public AgentEventsController(AgentEventStore eventStore) {
        this.eventStore = eventStore;
    }

    @GetMapping(path = "/events/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String sessionId,
            @RequestParam(name = "afterId", defaultValue = "0") long afterId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return eventStore.openStream(sessionId, resolveAfterId(afterId, lastEventId));
    }

    private static long resolveAfterId(long afterId, String lastEventId) {
        if (afterId > 0) {
            return afterId;
        }
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
