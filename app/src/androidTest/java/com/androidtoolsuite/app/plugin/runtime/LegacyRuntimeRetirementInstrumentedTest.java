package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.androidtoolsuite.app.debug.DebugCommandReceiver;
import com.androidtoolsuite.app.host.MainActivity;
import com.androidtoolsuite.app.migration.HostMigrationArchive;
import com.androidtoolsuite.app.plugin.store.BuiltInPluginStateStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@RunWith(AndroidJUnit4.class)
public final class LegacyRuntimeRetirementInstrumentedTest {
    private static final String ID = "test.v1_retirement";
    private final Context context = ApplicationProvider.getApplicationContext();
    private final DebugCommandReceiver receiver = new DebugCommandReceiver();
    private final PluginRuntime runtime = PluginRuntime.get(context);
    private SharedPreferences legacy;
    private Set<String> previousRecords;
    private Set<String> previousEnabled;
    private boolean hadRecords;
    private boolean hadEnabled;
    private File legacyCode;
    private File legacyData;
    private File inbox;
    private File outbox;
    private String record;
    private boolean ownsRuntimeFixture;

    @Before public void seedLegacyInstallation() throws Exception {
        assertNull("Fixture ID must not overwrite an installed plugin", runtime.packages().find(ID));
        ownsRuntimeFixture = true;
        File directory = new File(context.getFilesDir(), "plugins/" + ID);
        assertFalse("Fixture directory must be unused", directory.exists());
        assertTrue(directory.mkdirs());
        legacyCode = new File(directory, "plugin.apk");
        legacyData = new File(directory, "business.json");
        Files.write(legacyCode.toPath(), new byte[]{1, 2, 3});
        Files.write(legacyData.toPath(), "legacy-data".getBytes(StandardCharsets.UTF_8));
        legacy = context.getSharedPreferences("external_plugins", Context.MODE_PRIVATE);
        hadRecords = legacy.contains("plugin_json_set");
        hadEnabled = legacy.contains("enabled_ids");
        previousRecords = new LinkedHashSet<>(legacy.getStringSet("plugin_json_set", Collections.emptySet()));
        previousEnabled = new LinkedHashSet<>(legacy.getStringSet("enabled_ids", Collections.emptySet()));
        record = new JSONObject().put("formatVersion", 1).put("plugin", new JSONObject()
                .put("id", ID).put("title", "Retired fixture").put("version", "1.0.0")
                .put("entryClass", "test.LegacyPlugin")).toString();
        Set<String> records = new LinkedHashSet<>(previousRecords);
        records.add(record);
        Set<String> enabled = new LinkedHashSet<>(previousEnabled);
        enabled.add(ID);
        assertTrue(legacy.edit().putStringSet("plugin_json_set", records).putStringSet("enabled_ids", enabled).commit());
        File input = new File(context.getFilesDir(), "debug-inbox/" + ID + ".atsplugin");
        assertFalse(input.exists());
        inbox = input;
        File output = new File(context.getExternalFilesDir(null), "debug-outbox/" + ID + ".atsplugin");
        assertFalse(output.exists());
        outbox = output;
        assertTrue(inbox.getParentFile().isDirectory() || inbox.getParentFile().mkdirs());
    }

    @After public void cleanupFixture() throws Exception {
        if (ownsRuntimeFixture) {
            runtime.scheduler().cancelPlugin(ID);
            if (runtime.packages().find(ID) != null) runtime.packages().delete(ID);
            runtime.permissions().removePlugin(ID);
        }
        if (previousRecords != null) {
            SharedPreferences.Editor edit = legacy.edit();
            if (hadRecords) edit.putStringSet("plugin_json_set", previousRecords); else edit.remove("plugin_json_set");
            if (hadEnabled) edit.putStringSet("enabled_ids", previousEnabled); else edit.remove("enabled_ids");
            assertTrue(edit.commit());
        }
        if (inbox != null) Files.deleteIfExists(inbox.toPath());
        if (outbox != null) Files.deleteIfExists(outbox.toPath());
        if (legacyCode != null) Files.deleteIfExists(legacyCode.toPath());
        if (legacyData != null) {
            Files.deleteIfExists(legacyData.toPath());
            Files.deleteIfExists(legacyData.getParentFile().toPath());
        }
    }

    @Test public void legacyRecordCannotBeListedEnabledExportedOrBackedUp() throws Exception {
        JSONArray plugins = command("listPlugins", new Class<?>[]{Context.class}, context).getJSONArray("plugins");
        for (int i = 0; i < plugins.length(); i++) assertNotEquals(ID, plugins.getJSONObject(i).getString("id"));
        assertThrows(IllegalArgumentException.class, () -> command("setPluginEnabled",
                new Class<?>[]{Context.class, String.class, boolean.class}, context, ID, true));
        assertThrows(IllegalArgumentException.class, () -> command("exportPlugin",
                new Class<?>[]{Context.class, String.class, String.class}, context, ID, outbox.getName()));
        assertFalse(outbox.exists());
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                try {
                    assertTrue(activity.importedDescriptorsForUi().stream().noneMatch(item -> ID.equals(item.id)));
                    JSONObject state = (JSONObject) invoke(activity, "capturePluginState", new Class<?>[]{});
                    JSONArray external = state.getJSONArray("external");
                    for (int i = 0; i < external.length(); i++) assertNotEquals(ID, external.getJSONObject(i).getString("id"));
                } catch (Exception error) { throw new AssertionError(error); }
            });
        }
        assertLegacyFilesUntouched();
    }

    @Test public void sameIdFormatV3InstallsDisabledAndUsesOnlyRuntimeState() throws Exception {
        byte[] bytes = runtimePackage();
        Files.write(inbox.toPath(), bytes);
        JSONObject installed = command("importPlugin", new Class<?>[]{Context.class, String.class, boolean.class},
                context, inbox.getName(), false);
        assertEquals(3, installed.getInt("formatVersion"));
        assertFalse(installed.getBoolean("enabled"));
        assertFalse(runtime.packages().isEnabled(ID));
        command("setPluginEnabled", new Class<?>[]{Context.class, String.class, boolean.class}, context, ID, true);
        assertTrue(runtime.packages().isEnabled(ID));
        command("exportPlugin", new Class<?>[]{Context.class, String.class, String.class}, context, ID, outbox.getName());
        assertArrayEquals(bytes, Files.readAllBytes(outbox.toPath()));
        command("deletePlugin", new Class<?>[]{Context.class, String.class}, context, ID);
        assertNull(runtime.packages().find(ID));
        assertLegacyFilesUntouched();
    }

    @Test public void api1PackagesAreRejectedByDebugAndBackupInstaller() throws Exception {
        for (int format : new int[]{1, 2}) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("manifest.json", new JSONObject(record).put("formatVersion", format).toString().getBytes(StandardCharsets.UTF_8));
            entries.put("plugin.apk", Files.readAllBytes(legacyCode.toPath()));
            byte[] bytes = zip(entries);
            Files.write(inbox.toPath(), bytes);
            assertThrows(IllegalArgumentException.class, () -> command("importPlugin",
                    new Class<?>[]{Context.class, String.class, boolean.class}, context, inbox.getName(), false));
            assertThrows(java.io.IOException.class, () -> RuntimePackageInstaller.begin(runtime.packages(), bytes, ID));
            assertNull(runtime.packages().find(ID));
        }
        assertLegacyFilesUntouched();
    }

    @Test public void historicalArchiveRestoresSettingsWithoutInstallingOrEnablingPlugins() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                BuiltInPluginStateStore builtIns = new BuiltInPluginStateStore(activity);
                Set<String> previousBuiltIns = new LinkedHashSet<>(builtIns.enabledIds());
                JSONObject previousHost = null;
                boolean providerEnabled = runtime.packages().isEnabled("shizuku_auth");
                try {
                    previousHost = (JSONObject) invoke(activity, "captureHostSettings", new Class<?>[]{});
                    JSONObject host = new JSONObject(previousHost.toString());
                    host.put("autoCheckUpdates", !previousHost.getBoolean("autoCheckUpdates"));
                    HostMigrationArchive.Snapshot snapshot = new HostMigrationArchive.Snapshot(
                            context.getPackageName(), "1.6.1", 20, host, Set.of("shizuku_auth"),
                            List.of(new HostMigrationArchive.PluginEntry(ID, true, new byte[]{1, 2, 3})));
                    ByteArrayOutputStream archive = new ByteArrayOutputStream();
                    HostMigrationArchive.write(archive, snapshot);
                    invoke(activity, "restoreHostMigrationItem", new Class<?>[]{InputStream.class},
                            new ByteArrayInputStream(archive.toByteArray()));
                    assertEquals(host.getBoolean("autoCheckUpdates"), activity.autoCheckUpdatesForUi());
                    assertNull(runtime.packages().find(ID));
                    assertFalse(builtIns.isEnabled("shizuku_auth"));
                    assertEquals(providerEnabled, runtime.packages().isEnabled("shizuku_auth"));
                } catch (Exception error) { throw new AssertionError(error); }
                finally {
                    try {
                        if (previousHost != null) assertEquals(true, invoke(activity, "applyHostSettings", new Class<?>[]{JSONObject.class}, previousHost));
                        assertTrue(builtIns.replaceEnabledIds(previousBuiltIns));
                    } catch (Exception error) { throw new AssertionError(error); }
                }
            });
        }
        assertLegacyFilesUntouched();
    }

    private void assertLegacyFilesUntouched() throws Exception {
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(legacyCode.toPath()));
        assertEquals("legacy-data", new String(Files.readAllBytes(legacyData.toPath()), StandardCharsets.UTF_8));
        assertTrue(legacy.getStringSet("plugin_json_set", Collections.emptySet()).contains(record));
    }

    private JSONObject command(String name, Class<?>[] types, Object... args) throws Exception {
        return (JSONObject) invoke(receiver, name, types, args);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }

    private static byte[] runtimePackage() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(DatasetServiceInstrumentedTest.packageBytes()))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().startsWith("META-INF/")) continue;
                byte[] bytes = input.readAllBytes();
                if (entry.getName().equals("manifest.json")) bytes = new String(bytes, StandardCharsets.UTF_8)
                        .replace("test.runtime_v2_data", ID).getBytes(StandardCharsets.UTF_8);
                entries.put(entry.getName(), bytes);
            }
        }
        JSONArray files = new JSONArray();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(entry.getValue())) hash.append(String.format("%02x", value & 255));
            files.put(new JSONObject().put("path", entry.getKey()).put("size", entry.getValue().length).put("sha256", hash.toString()));
        }
        entries.put("META-INF/ats-integrity.json", new JSONObject().put("formatVersion", 1).put("algorithm", "sha256")
                .put("files", files).toString().getBytes(StandardCharsets.UTF_8));
        return zip(entries);
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
