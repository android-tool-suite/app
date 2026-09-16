package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public final class FileExportCapabilityInstrumentedTest {
    @Test
    public void exportRequiresGestureAndDeclaredMimeScope() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        CapabilityRouter router = new CapabilityRouter();
        RecordingActions actions = new RecordingActions(router);
        List<AutoCloseable> registrations = HostCapabilityProviders.registerActivityCapabilities(
                context,
                actions,
                router
        );
        RuntimePluginManifest manifest = RuntimePluginManifest.parse(manifest());
        JSONObject payload = new JSONObject()
                .put("blobId", "generated-image")
                .put("fileName", "Phigros-B30.png")
                .put("mimeType", "image/png");
        try {
            ExecutionException noGesture = org.junit.Assert.assertThrows(
                    ExecutionException.class,
                    () -> router.invoke(
                            manifest, "test.file_export", "export-session", "file.export.save",
                            payload, false, 10_000
                    ).get(1, TimeUnit.SECONDS)
            );
            assertEquals("CONSENT_REQUIRED", ((CapabilityFailure) noGesture.getCause()).code);

            JSONObject saved = router.invoke(
                    manifest, "test.file_export", "export-session", "file.export.save",
                    payload, true, 10_000
            ).get(1, TimeUnit.SECONDS);
            assertTrue(saved.getBoolean("saved"));
            assertEquals("generated-image", actions.blobId);

            JSONObject wrongMime = new JSONObject(payload.toString()).put("mimeType", "text/plain");
            ExecutionException undeclared = org.junit.Assert.assertThrows(
                    ExecutionException.class,
                    () -> router.invoke(
                            manifest, "test.file_export", "export-session", "file.export.save",
                            wrongMime, true, 10_000
                    ).get(1, TimeUnit.SECONDS)
            );
            assertEquals("CAPABILITY_UNDECLARED", ((CapabilityFailure) undeclared.getCause()).code);
        } finally {
            for (int index = registrations.size() - 1; index >= 0; index--) registrations.get(index).close();
            router.close();
        }
    }

    private static String manifest() {
        return "{"
                + "\"format\":\"ats-plugin\",\"formatVersion\":3,"
                + "\"plugin\":{\"id\":\"test.file_export\",\"title\":\"Export\","
                + "\"description\":\"Export test\",\"version\":\"1.0.0\","
                + "\"versionCode\":1,\"minHostVersionCode\":1,\"publisher\":\"test\",\"kind\":\"tool\"},"
                + "\"platforms\":[\"android\"],"
                + "\"runtime\":{\"ui\":[{\"id\":\"main\",\"type\":\"declarative\","
                + "\"entry\":\"ui/main.json\"}],\"background\":[],\"providers\":[]},"
                + "\"requires\":{\"plugins\":[],\"capabilities\":[{"
                + "\"id\":\"file.export\",\"version\":\"^1.0.0\",\"optional\":false,"
                + "\"scopes\":{\"mimeTypes\":[\"image/png\"],\"maxBytes\":1048576}}]},"
                + "\"provides\":{\"capabilities\":[]},"
                + "\"contributes\":{\"tools\":[{\"id\":\"main\",\"uiEntry\":\"main\"}],"
                + "\"homeWidgets\":[]},\"datasets\":[],\"tasks\":[]}"
                ;
    }

    private static final class RecordingActions implements HostActions {
        private final CapabilityRouter router;
        String blobId;

        RecordingActions(CapabilityRouter router) {
            this.router = router;
        }

        @Override public Activity activity() { return null; }
        @Override public void closeTool() { }
        @Override public void showMessage(String message) { }
        @Override public CapabilityRouter capabilityRouter() { return router; }
        @Override public void closeRuntimeSession(String sessionId) { }
        @Override public CompletableFuture<JSONObject> pickFile(
                String pluginId, String sessionId, JSONArray mimeTypes, int maxBytes
        ) {
            return CompletableFuture.failedFuture(new AssertionError("pickFile should not be called"));
        }
        @Override public CompletableFuture<JSONObject> saveFile(
                String pluginId,
                String sessionId,
                String blobId,
                String fileName,
                String mimeType,
                int maxBytes
        ) {
            this.blobId = blobId;
            try {
                return CompletableFuture.completedFuture(new JSONObject()
                        .put("saved", true)
                        .put("size", 42)
                        .put("sha256", "a".repeat(64)));
            } catch (Exception error) {
                return CompletableFuture.failedFuture(error);
            }
        }
        @Override public void requestNotificationPermission() { }
    }
}
