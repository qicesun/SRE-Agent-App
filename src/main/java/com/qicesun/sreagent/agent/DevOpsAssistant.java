package com.qicesun.sreagent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

public interface DevOpsAssistant {
    String chat(@MemoryId String memoryId, @UserMessage String userQuery);
}
