package com.androidtoolsuite.app.plugin.v2;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;

public interface V2HostActions {
    Activity activity();

    void closeTool();

    void showMessage(String message);

    V2CapabilityRouter capabilityRouter();

    void closeRuntimeSession(String sessionId);

    CompletableFuture<JSONObject> pickFile(
            String pluginId,
            String sessionId,
            JSONArray mimeTypes,
            int maxBytes
    );

    void requestNotificationPermission();

}
