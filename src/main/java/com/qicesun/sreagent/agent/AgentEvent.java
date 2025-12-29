package com.qicesun.sreagent.agent;

import java.time.Instant;

public record AgentEvent(long id, Instant timestamp, String source, String message) {
}
