package com.qicesun.sreagent.controller;

import com.qicesun.sreagent.agent.AgentEventStore;
import com.qicesun.sreagent.agent.SessionMemoryStore;
import com.qicesun.sreagent.config.SessionConfigStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {

    private final SessionMemoryStore memoryStore;
    private final AgentEventStore eventStore;
    private final SessionConfigStore configStore;

    public SessionController(
            SessionMemoryStore memoryStore,
            AgentEventStore eventStore,
            SessionConfigStore configStore) {
        this.memoryStore = memoryStore;
        this.eventStore = eventStore;
        this.configStore = configStore;
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable String sessionId) {
        memoryStore.clear(sessionId);
        eventStore.clear(sessionId);
        configStore.clear(sessionId);
    }
}
