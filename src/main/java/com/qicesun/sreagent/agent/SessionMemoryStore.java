package com.qicesun.sreagent.agent;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SessionMemoryStore {

    private static final int MAX_MESSAGES = 20;

    private final Map<Object, ChatMemory> store = new ConcurrentHashMap<>();

    public ChatMemory get(Object memoryId) {
        Object key = memoryId == null ? "default" : memoryId;
        return store.computeIfAbsent(key, ignored -> MessageWindowChatMemory.withMaxMessages(MAX_MESSAGES));
    }

    public void clear(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return;
        }
        store.remove(memoryId);
    }
}
