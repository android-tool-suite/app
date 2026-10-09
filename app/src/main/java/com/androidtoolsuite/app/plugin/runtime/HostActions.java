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

    /** End only display metadata when a document reloads; business resources are unaffected. */
    default void closeOperationReports(String sessionId) {}

    /** Captured on the UI thread when a report arrives, before another RPC can open a picker. */
    default boolean canStartUiOperation(String pluginId, String sessionId) { return false; }

    default CompletableFuture<JSONObject> reportOperation(CapabilityCall call) {
        CompletableFuture<JSONObject> result = new CompletableFuture<>();
        result.completeExceptionally(new CapabilityFailure("NOT_SUPPORTED", "当前容器不支持操作状态报告", false));
        return result;
    }

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
