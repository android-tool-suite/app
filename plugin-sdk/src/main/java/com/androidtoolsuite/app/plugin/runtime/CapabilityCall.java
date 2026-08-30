package com.androidtoolsuite.app.plugin.runtime;

import org.json.JSONObject;

/** A single capability invocation. Payloads deliberately remain JSON-compatible. */
public final class CapabilityCall {
    public final String pluginId;
    public final String sessionId;
    public final String method;
    public final JSONObject payload;
    public final JSONObject scopes;
    public final boolean userGesture;
    public final long deadlineEpochMillis;

    public CapabilityCall(
            String pluginId,
            String sessionId,
            String method,
            JSONObject payload,
            JSONObject scopes,
            boolean userGesture,
            long deadlineEpochMillis
    ) {
        this.pluginId = pluginId;
        this.sessionId = sessionId;
        this.method = method;
        this.payload = payload;
        this.scopes = scopes;
        this.userGesture = userGesture;
        this.deadlineEpochMillis = deadlineEpochMillis;
    }
}
