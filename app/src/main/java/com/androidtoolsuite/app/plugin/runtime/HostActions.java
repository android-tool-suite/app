package com.androidtoolsuite.app.plugin.runtime;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CompletableFuture;

public interface HostActions {
    Activity activity();

    void closeTool();

    void showMessage(String message);

    CapabilityRouter capabilityRouter();

    void closeRuntimeSession(String sessionId);

    CompletableFuture<JSONObject> pickFile(
            String pluginId,
            String sessionId,
            JSONArray mimeTypes,
            int maxBytes
    );

    CompletableFuture<JSONObject> saveFile(
            String pluginId,
            String sessionId,
            String blobId,
            String fileName,
            String mimeType,
            int maxBytes
    );

    void requestNotificationPermission();

}
