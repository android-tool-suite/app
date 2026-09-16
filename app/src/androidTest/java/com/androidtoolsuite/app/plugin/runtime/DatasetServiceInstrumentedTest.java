package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Base64;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.androidtoolsuite.app.plugin.runtime.CapabilityFailure;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(AndroidJUnit4.class)
public final class DatasetServiceInstrumentedTest {
    private static final String PLUGIN_ID = "test.runtime_v2_data";
    private Context context;
    private PluginRuntime runtime;

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        runtime = PluginRuntime.get(context);
        if (runtime.packages().find(PLUGIN_ID) != null) runtime.packages().delete(PLUGIN_ID);
        PluginPackageStore.InstallSession install = runtime.packages().install(packageBytes(), "test", "", false);
        runtime.packages().setEnabled(PLUGIN_ID, true);
        runtime.packages().confirmInstall(install);
    }

    @After
    public void tearDown() throws Exception {
        if (runtime.packages().find(PLUGIN_ID) != null) runtime.packages().delete(PLUGIN_ID);
    }

    @Test
    public void datasetCommitSecretRoundTripAndFailedValidationPreserveGeneration() throws Exception {
        DatasetService service = runtime.datasets();
        String session = "instrumented";
        byte[] first = "{\"formatVersion\":1,\"value\":\"first\"}".getBytes(StandardCharsets.UTF_8);
        JSONObject opened = service.openWrite(PLUGIN_ID, session, "settings");
        service.write(PLUGIN_ID, session, opened.getString("handle"), Base64.encodeToString(first, Base64.NO_WRAP));
        String generation = service.commit(PLUGIN_ID, session, opened.getString("handle")).getString("generation");

        assertArrayEquals(first, read(service, session, "settings"));
        assertTrue(generation.matches("g[2-9][0-9]*|g[2-9]"));

        JSONObject invalid = service.openWrite(PLUGIN_ID, session, "settings");
        service.write(PLUGIN_ID, session, invalid.getString("handle"),
                Base64.encodeToString("not-json".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        assertThrows(CapabilityFailure.class,
                () -> service.commit(PLUGIN_ID, session, invalid.getString("handle")));
        assertArrayEquals(first, read(service, session, "settings"));

        MigrationToolPlugin migration = new MigrationToolPlugin(
                runtime.packages().find(PLUGIN_ID), service
        );
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        migration.datasetBridge().exportDataset(null, "settings", exported);
        assertArrayEquals(first, exported.toByteArray());
        migration.datasetBridge().deleteDataset(null, "settings");
        assertTrue(!migration.datasetBridge().hasData(null, "settings"));
        migration.datasetBridge().importDataset(
                null,
                "settings",
                1,
                com.androidtoolsuite.app.migration.DatasetRestoreMode.REPLACE,
                new ByteArrayInputStream(exported.toByteArray())
        );
        assertArrayEquals(first, read(service, session, "settings"));

        service.secretSet(PLUGIN_ID, "credentials", "token", new JSONObject().put("value", "secret-value"));
        JSONObject secret = service.secretGet(PLUGIN_ID, "credentials", "token");
        assertTrue(secret.getBoolean("found"));
        assertEquals("secret-value", secret.getJSONObject("value").getString("value"));
        assertTrue(service.secretDelete(PLUGIN_ID, "credentials", "token").getBoolean("deleted"));
    }

    @Test
    public void multiChunkDatasetReadAppliesOffsets() throws Exception {
        DatasetService service = runtime.datasets();
        String session = "instrumented-chunked";
        StringBuilder content = new StringBuilder("{\"formatVersion\":1,\"items\":[");
        for (int index = 0; index < 10000; index++) {
            if (index > 0) content.append(',');
            content.append('"').append(String.format(Locale.ROOT, "%05d", index)).append('"');
        }
        content.append("]}");
        byte[] payload = content.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(payload.length > 64 * 1024);

        JSONObject opened = service.openWrite(PLUGIN_ID, session, "settings");
        String handle = opened.getString("handle");
        int midpoint = payload.length / 2;
        service.write(PLUGIN_ID, session, handle,
                Base64.encodeToString(Arrays.copyOfRange(payload, 0, midpoint), Base64.NO_WRAP));
        service.write(PLUGIN_ID, session, handle,
                Base64.encodeToString(Arrays.copyOfRange(payload, midpoint, payload.length), Base64.NO_WRAP));
        service.commit(PLUGIN_ID, session, handle);

        assertArrayEquals(payload, read(service, session, "settings"));
    }

    @Test
    public void pureJavaScriptWorkerRunsWithoutWebViewOrMessagePorts() throws Exception {
        PluginPackageStore.InstalledPlugin installed = runtime.packages().find(PLUGIN_ID);
        JSONObject result = JavaScriptWorkerEngine.run(
                context,
                installed,
                installed.manifest.backgroundEntries.get(0),
                new JSONObject().put("value", 41),
                runtime.capabilities(),
                "instrumented-worker"
        );

        assertEquals(42, result.getInt("answer"));
    }

    private static byte[] read(DatasetService service, String session, String datasetId) throws Exception {
        JSONObject opened = service.openRead(PLUGIN_ID, session, datasetId);
        assertTrue(opened.getBoolean("found"));
        String handle = opened.getString("handle");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            long offset = 0L;
            while (true) {
                JSONObject part = service.read(PLUGIN_ID, session, handle, offset, 64 * 1024);
                byte[] bytes = Base64.decode(part.getString("bytes"), Base64.NO_WRAP);
                output.write(bytes);
                offset += bytes.length;
                if (part.getBoolean("eof")) return output.toByteArray();
            }
        } finally {
            service.abort(PLUGIN_ID, session, handle);
        }
    }

    static byte[] packageBytes() throws Exception {
        return packageBytes(1);
    }

    static byte[] packageBytes(int versionCode) throws Exception {
        JSONObject manifest = new JSONObject()
                .put("format", "ats-plugin")
                .put("formatVersion", 3)
                .put("plugin", new JSONObject()
                        .put("id", PLUGIN_ID)
                        .put("title", "插件运行时 data test")
                        .put("description", "Instrumentation fixture")
                        .put("version", "1.0.0")
                        .put("versionCode", versionCode)
                        .put("minHostVersionCode", 1)
                        .put("publisher", "android_tool_suite.tests"))
                .put("platforms", new JSONArray().put("android"))
                .put("runtime", new JSONObject()
                        .put("ui", new JSONArray().put(new JSONObject()
                                .put("id", "main").put("type", "web").put("entry", "web/index.html")))
                        .put("background", new JSONArray().put(new JSONObject()
                                .put("id", "pure-worker")
                                .put("type", "javascript-worker")
                                .put("entry", "workers/pure.js")
                                .put("required", false)
                                .put("timeoutMs", 15000)
                                .put("maxHeapBytes", 8388608)))
                        .put("providers", new JSONArray()))
                .put("requires", new JSONObject()
                        .put("plugins", new JSONArray())
                        .put("capabilities", new JSONArray().put(new JSONObject()
                                .put("id", "storage").put("version", "^1.0.0")
                                .put("optional", false).put("scopes", new JSONObject()))))
                .put("provides", new JSONObject().put("capabilities", new JSONArray()))
                .put("contributes", new JSONObject()
                        .put("tools", new JSONArray().put(new JSONObject().put("id", "main").put("uiEntry", "main")))
                        .put("homeWidgets", new JSONArray()))
                .put("datasets", new JSONArray()
                        .put(dataset("settings", false))
                        .put(dataset("credentials", true)))
                .put("tasks", new JSONArray());
        List<Item> payloads = new ArrayList<>();
        payloads.add(new Item("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8)));
        payloads.add(new Item("web/index.html", "<!doctype html><title>test</title>".getBytes(StandardCharsets.UTF_8)));
        payloads.add(new Item(
                "workers/pure.js",
                "globalThis.atsWorkerMain=async input=>({answer:input.value+1});"
                        .getBytes(StandardCharsets.UTF_8)
        ));
        payloads.sort(Comparator.comparing(item -> item.path));
        JSONArray files = new JSONArray();
        for (Item item : payloads) {
            files.put(new JSONObject().put("path", item.path).put("size", item.bytes.length).put("sha256", sha256(item.bytes)));
        }
        byte[] integrity = new JSONObject()
                .put("algorithm", "sha256")
                .put("formatVersion", 1)
                .put("files", files)
                .toString().getBytes(StandardCharsets.UTF_8);
        List<Item> archive = new ArrayList<>(payloads);
        archive.add(new Item("META-INF/ats-integrity.json", integrity));
        archive.sort(Comparator.comparing(item -> item.path));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Item item : archive) {
                ZipEntry entry = new ZipEntry(item.path);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(item.bytes);
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static JSONObject dataset(String id, boolean sensitive) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("title", id)
                .put("category", sensitive ? "secret" : "settings")
                .put("formatVersion", 1)
                .put("sensitive", sensitive)
                .put("mediaType", "application/json")
                .put("validator", "json")
                .put("maxBytes", 131072)
                .put("restoreModes", new JSONArray().put("replace"))
                .put("dependsOn", new JSONArray());
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte item : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private static final class Item {
        final String path;
        final byte[] bytes;
        Item(String path, byte[] bytes) { this.path = path; this.bytes = bytes; }
    }
}
