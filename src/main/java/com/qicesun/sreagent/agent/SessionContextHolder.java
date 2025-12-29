package com.qicesun.sreagent.agent;

public final class SessionContextHolder {

    private static final ThreadLocal<String> SESSION_ID = new ThreadLocal<>();

    private SessionContextHolder() {
    }

    public static void setSessionId(String sessionId) {
        SESSION_ID.set(sessionId);
    }

    public static String getSessionId() {
        return SESSION_ID.get();
    }

    public static void clear() {
        SESSION_ID.remove();
    }
}
