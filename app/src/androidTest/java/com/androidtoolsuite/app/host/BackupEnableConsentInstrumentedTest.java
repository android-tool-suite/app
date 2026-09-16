package com.androidtoolsuite.app.host;

import static org.junit.Assert.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.androidtoolsuite.app.migration.*;
import com.androidtoolsuite.app.plugin.runtime.PluginRuntime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Opt-in: a signed Shizuku package must already be installed. Restores all enable states afterward. */
@RunWith(AndroidJUnit4.class)
public final class BackupEnableConsentInstrumentedTest {
    @Test public void backupCannotEnableNewOrPreviouslyDisabledTrustedProvider() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                File payload = new File(activity.getCacheDir(), "enable-consent-" + UUID.randomUUID());
                try {
                    PluginRuntime runtime = PluginRuntime.get(activity);
                    assertNotNull(runtime.packages().find("shizuku_auth"));
                    Class<?> checkpointType = Arrays.stream(MainActivity.class.getDeclaredClasses())
                            .filter(type -> type.getSimpleName().equals("HostRestoreCheckpoint")).findFirst().orElseThrow();
                    Constructor<?> constructor = checkpointType.getDeclaredConstructor(MainActivity.class);
                    constructor.setAccessible(true);
                    Method restore = MainActivity.class.getDeclaredMethod("restoreHostDataItems", List.class, Map.class, Set.class);
                    restore.setAccessible(true);
                    MigrationBridgeManager.DatasetOption option = MigrationBridgeManager.hostPluginStateExportOption(0);
                    List<MigrationBridgeManager.ImportSelection> selections = List.of(new MigrationBridgeManager.ImportSelection(option, DatasetRestoreMode.MERGE));
                    try (AutoCloseable checkpoint = (AutoCloseable) constructor.newInstance(activity)) {
                        JSONObject legacy = new JSONObject().put("formatVersion", 1)
                                .put("builtIn", new JSONArray().put(new JSONObject().put("id", "shizuku_auth").put("enabled", true)))
                                .put("external", new JSONArray());
                        write(payload, legacy);
                        restore.invoke(activity, selections, Map.of(option.key(), payload), Set.of("shizuku_auth"));
                        assertFalse(runtime.packages().isEnabled("shizuku_auth"));
                        JSONObject current = new JSONObject().put("formatVersion", 1).put("builtIn", new JSONArray())
                                .put("external", new JSONArray().put(new JSONObject().put("id", "shizuku_auth").put("enabled", true)));
                        write(payload, current);
                        restore.invoke(activity, selections, Map.of(option.key(), payload), Set.of());
                        assertFalse(runtime.packages().isEnabled("shizuku_auth"));
                    }
                } catch (Exception error) { throw new AssertionError(error); }
                finally { payload.delete(); }
            });
        }
    }
    private static void write(File file, JSONObject value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(value.toString().getBytes(StandardCharsets.UTF_8)); }
    }
}
