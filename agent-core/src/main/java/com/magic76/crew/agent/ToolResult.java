package com.magic76.crew.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Result returned to the model after a tool invocation. */
public final class ToolResult {
    private final String callId;
    private final boolean success;
    private final Map<String, Object> payload;
    private final String errorCode;
    private final String errorMessage;

    private ToolResult(String callId,
                       boolean success,
                       Map<String, Object> payload,
                       String errorCode,
                       String errorMessage) {
        this.callId = callId == null ? "" : callId;
        this.success = success;
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (payload != null) copy.putAll(payload);
        this.payload = Collections.unmodifiableMap(copy);
        this.errorCode = errorCode == null ? "" : errorCode;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static ToolResult success(String callId, Map<String, Object> payload) {
        return new ToolResult(callId, true, payload, "", "");
    }

    public static ToolResult failure(String callId, String code, String message) {
        return new ToolResult(callId, false, null, code, message);
    }

    public String callId() { return callId; }
    public boolean success() { return success; }
    public Map<String, Object> payload() { return payload; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
}
