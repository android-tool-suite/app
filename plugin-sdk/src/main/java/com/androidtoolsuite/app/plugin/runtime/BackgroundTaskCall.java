package com.androidtoolsuite.app.plugin.runtime;

import org.json.JSONObject;

public final class BackgroundTaskCall {
    public final String pluginId;
    public final String taskId;
    public final JSONObject input;
    public final long deadlineEpochMillis;

    public BackgroundTaskCall(String pluginId, String taskId, JSONObject input, long deadlineEpochMillis) {
        this.pluginId = pluginId;
        this.taskId = taskId;
        this.input = input;
        this.deadlineEpochMillis = deadlineEpochMillis;
    }
}
