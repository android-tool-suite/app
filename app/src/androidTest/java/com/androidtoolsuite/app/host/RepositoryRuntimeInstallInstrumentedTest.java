package com.androidtoolsuite.app.host;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.androidtoolsuite.app.plugin.runtime.PluginRuntime;
import com.androidtoolsuite.app.update.UpdateCatalog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(AndroidJUnit4.class)
public final class RepositoryRuntimeInstallInstrumentedTest {
    private static final String PLUGIN_ID = "test.repository_runtime";

    @Test
    public void uninstalledRepositoryEntryStaysInstallableButIsNotAnUpdate() throws Exception {
        UpdateCatalog repository = catalog("a".repeat(64), 1);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField("updateCatalog");
                    field.setAccessible(true);
                    Object previous = field.get(activity);
                    try {
                        assertTrue(PluginRuntime.get(activity).packages().find(PLUGIN_ID) == null);
                        field.set(activity, repository);
                        UpdateCatalog.PluginRelease release = repository.findPlugin(PLUGIN_ID);
                        assertTrue(activity.isRepositoryPluginVersionSelectableForUi(release));
                        assertEquals(1, activity.repositoryPluginsForUi().size());
                        assertFalse(activity.isRepositoryPluginUpdateAvailableForUi(release));
                        assertTrue(activity.availablePluginUpdatesForUi().isEmpty());
                        assertEquals(0, activity.availableUpdateCountForUi());
                    } finally {
                        field.set(activity, previous);
                    }
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }
    }

    @Test
    public void repositoryRuntimePackageIsVerifiedInstalledAndRecognizedAsCurrent() throws Exception {
        byte[] packageBytes = packageBytes();
        String digest = sha256(packageBytes);
        UpdateCatalog.PluginRelease release = catalog(digest, packageBytes.length).findPlugin(PLUGIN_ID);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                PluginRuntime runtime = PluginRuntime.get(activity);
                try {
                    runtime.scheduler().cancelPlugin(PLUGIN_ID);
                    if (runtime.packages().find(PLUGIN_ID) != null) runtime.packages().delete(PLUGIN_ID);
                    Method install = MainActivity.class.getDeclaredMethod(
                            "installRepositoryRuntimePlugin",
                            byte[].class,
                            UpdateCatalog.PluginRelease.class
                    );
                    install.setAccessible(true);
                    install.invoke(activity, packageBytes, release);
                    PluginRuntime.get(activity).permissions().reconcile(runtime.packages().load());
                    assertTrue(runtime.packages().find(PLUGIN_ID).repositoryVerified);
                    assertTrue(activity.isRepositoryPluginVersionInstalledForUi(release));
                    assertFalse(activity.isRepositoryPluginUpdateAvailableForUi(release));
                    UpdateCatalog.PluginRelease newer = catalog("f".repeat(64), packageBytes.length, 2).findPlugin(PLUGIN_ID);
                    assertTrue(activity.isRepositoryPluginUpdateAvailableForUi(newer));
                    activity.setPluginUpdateCheckEnabledForUi(PLUGIN_ID, false);
                    assertFalse(activity.isRepositoryPluginUpdateAvailableForUi(newer));
                    activity.setPluginUpdateCheckEnabledForUi(PLUGIN_ID, true);
                } catch (Exception error) {
                    throw new AssertionError(error);
                } finally {
                    runtime.scheduler().cancelPlugin(PLUGIN_ID);
                    try {
                        if (runtime.packages().find(PLUGIN_ID) != null) runtime.packages().delete(PLUGIN_ID);
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                    runtime.permissions().removePlugin(PLUGIN_ID);
                }
            });
        }
    }

    private static byte[] packageBytes() throws Exception {
        String manifest = "{"
                + "\"format\":\"ats-plugin\",\"formatVersion\":3,"
                + "\"plugin\":{\"id\":\"" + PLUGIN_ID + "\",\"title\":\"Repository Runtime\","
                + "\"description\":\"Repository fixture\",\"version\":\"1.0.0\",\"versionCode\":1,"
                + "\"minHostVersionCode\":24,\"minAndroidApi\":26,\"publisher\":\"test.publisher\",\"kind\":\"tool\"},"
                + "\"platforms\":[\"android\"],"
                + "\"runtime\":{\"ui\":[{\"id\":\"main\",\"type\":\"declarative\",\"entry\":\"ui/main.json\"}],\"background\":[],\"providers\":[]},"
                + "\"requires\":{\"plugins\":[],\"capabilities\":[]},\"provides\":{\"capabilities\":[]},"
                + "\"contributes\":{\"tools\":[{\"id\":\"main\",\"uiEntry\":\"main\"}],\"homeWidgets\":[]},"
                + "\"datasets\":[],\"tasks\":[]}";
        String ui = "{\"formatVersion\":1,\"body\":{\"type\":\"column\",\"children\":[{\"type\":\"text\",\"value\":\"Repository fixture\"}]}}";
        Map<String, byte[]> payloads = new LinkedHashMap<>();
        payloads.put("manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        payloads.put("ui/main.json", ui.getBytes(StandardCharsets.UTF_8));
        JSONArray files = new JSONArray();
        for (Map.Entry<String, byte[]> entry : payloads.entrySet()) {
            files.put(new JSONObject()
                    .put("path", entry.getKey())
                    .put("sha256", sha256(entry.getValue()))
                    .put("size", entry.getValue().length));
        }
        payloads.put("META-INF/ats-integrity.json", new JSONObject()
                .put("algorithm", "sha256")
                .put("files", files)
                .put("formatVersion", 1)
                .toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : payloads.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey());
                item.setTime(0L);
                zip.putNextEntry(item);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static UpdateCatalog catalog(String digest, int size) throws Exception {
        return catalog(digest, size, 1);
    }

    private static UpdateCatalog catalog(String digest, int size, int versionCode) throws Exception {
        return UpdateCatalog.parse("{\"schemaVersion\":1,\"plugins\":[{"
                + "\"id\":\"" + PLUGIN_ID + "\",\"title\":\"Repository Runtime\","
                + "\"description\":\"Repository fixture\",\"author\":\"test.publisher\","
                + "\"repositoryUrl\":\"https://example.test/repository\",\"versionName\":\"1.0.0\","
                + "\"versionCode\":" + versionCode + ",\"minHostVersionCode\":24,\"minAndroidApi\":26,\"sdkVersion\":\"\","
                + "\"dependencies\":[],\"dataCompatibility\":{\"schemaVersion\":1,\"dataFormatVersion\":1,"
                + "\"minReadableDataFormatVersion\":0,\"maxReadableDataFormatVersion\":1},"
                + "\"releaseUrl\":\"https://example.test/release\",\"downloadUrl\":\"https://example.test/plugin.atsplugin\","
                + "\"size\":" + size + ",\"sha256\":\"" + digest + "\"}]}" );
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }
}
