package com.androidtoolsuite.app.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.androidtoolsuite.app.plugin.runtime.PluginPackageStore;
import com.androidtoolsuite.app.plugin.runtime.PluginRuntime;
import com.androidtoolsuite.app.plugin.runtime.WidgetSnapshotStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(AndroidJUnit4.class)
public final class WidgetRefreshInstrumentedTest {
    private static final String ID = "test.widget_refresh";

    @Test
    public void bottomNavigationUpdatesWidgetVisibilityWithoutHostInvalidation() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.navigateForUi(0));
            awaitVisibility(scenario, true);
            selectTab(scenario, "仓库", 3);
            awaitVisibility(scenario, false);
            selectTab(scenario, "主页", 0);
            awaitVisibility(scenario, true);
        }
    }

    @Test
    public void updatedWidgetRefreshesOnReturningFromRepositoryWithoutOpeningTool() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    try {
                        remove(activity);
                        install(activity, 1);
                        activity.navigateForUi(0);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                awaitValue(scenario, 1);
                selectTab(scenario, "仓库", 3);
                scenario.onActivity(activity -> {
                    try { install(activity, 2); } catch (Exception error) { throw new AssertionError(error); }
                });
                SystemClock.sleep(500);
                selectTab(scenario, "主页", 0);
                awaitValue(scenario, 2);
                scenario.onActivity(activity -> {
                    try { install(activity, 3); } catch (Exception error) { throw new AssertionError(error); }
                });
                awaitValue(scenario, 3);
            } finally {
                scenario.onActivity(activity -> {
                    try { remove(activity); } catch (Exception error) { throw new AssertionError(error); }
                });
            }
        }
    }

    // Exercise the bottom navigation/Pager path. navigateForUi() explicitly invalidates
    // the host and would hide the missing observer that this regression targets.
    private static void selectTab(ActivityScenario<MainActivity> scenario, String label, int expected) {
        boolean clicked = false;
        AccessibilityNodeInfo root = null;
        for (int attempt = 0; attempt < 50 && !clicked; attempt++) {
            root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo node = findLabel(root, label);
            if (node != null) {
                while (node != null && !node.isClickable()) node = node.getParent();
                if (node != null) clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            if (!clicked) SystemClock.sleep(100);
        }
        assertTrue("Bottom navigation item unavailable: " + label + " root=" + root, clicked);
        AtomicReference<Integer> section = new AtomicReference<>();
        for (int attempt = 0; attempt < 50; attempt++) {
            scenario.onActivity(activity -> section.set(activity.currentSectionForUi()));
            if (Integer.valueOf(expected).equals(section.get())) return;
            SystemClock.sleep(100);
        }
        assertEquals("Pager did not settle", Integer.valueOf(expected), section.get());
    }

    private static AccessibilityNodeInfo findLabel(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        if (label.contentEquals(node.getText() == null ? "" : node.getText())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findLabel(node.getChild(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private static void awaitVisibility(ActivityScenario<MainActivity> scenario, boolean expected) throws Exception {
        AtomicReference<Boolean> actual = new AtomicReference<>();
        Field field = WidgetSnapshotStore.class.getDeclaredField("visible");
        field.setAccessible(true);
        for (int attempt = 0; attempt < 50; attempt++) {
            scenario.onActivity(activity -> {
                try { actual.set(field.getBoolean(PluginRuntime.get(activity).widgetSnapshots())); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            if (Boolean.valueOf(expected).equals(actual.get())) return;
            SystemClock.sleep(100);
        }
        assertEquals("Widget visibility must follow the Pager", Boolean.valueOf(expected), actual.get());
    }

    private static void awaitValue(ActivityScenario<MainActivity> scenario, int expected) throws Exception {
        AtomicReference<String> state = new AtomicReference<>("not observed");
        for (int attempt = 0; attempt < 80; attempt++) {
            AtomicReference<Integer> value = new AtomicReference<>();
            scenario.onActivity(activity -> {
                try {
                    WidgetSnapshotStore store = PluginRuntime.get(activity).widgetSnapshots();
                    Field entries = WidgetSnapshotStore.class.getDeclaredField("entries");
                    entries.setAccessible(true);
                    WidgetSnapshotStore.Entry entry = (WidgetSnapshotStore.Entry)
                            ((Map<?, ?>) entries.get(store)).get(ID + "/summary");
                    if (entry != null) {
                        JSONObject snapshot = entry.getValue();
                        if (snapshot != null) value.set(snapshot.optInt("value", -1));
                        Field visible = WidgetSnapshotStore.class.getDeclaredField("visible");
                        visible.setAccessible(true);
                        StringBuilder description = new StringBuilder("visible=" + visible.get(store));
                        for (String name : new String[]{"observers", "dirty", "job"}) {
                            Field field = entry.getClass().getDeclaredField(name);
                            field.setAccessible(true);
                            description.append(" ").append(name).append("=").append(field.get(entry));
                        }
                        state.set(description + " error=" + entry.getError() + " value=" + value.get());
                    }
                } catch (Exception error) { throw new AssertionError(error); }
            });
            if (Integer.valueOf(expected).equals(value.get())) return;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Expected widget " + expected + ": " + state.get());
    }

    private static void install(MainActivity activity, int version) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(activity);
        PluginPackageStore.InstallSession session = runtime.packages().install(packageBytes(version), "test", "", false);
        runtime.packages().setEnabled(ID, true);
        runtime.packages().confirmInstall(session);
        runtime.permissions().reconcile(runtime.packages().load());
        reload(activity);
        assertTrue(runtime.workerProviders().isActive(ID,
                runtime.packages().find(ID).generationDirectory.getName()));
    }

    private static void remove(MainActivity activity) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(activity);
        runtime.scheduler().cancelPlugin(ID);
        if (runtime.packages().find(ID) != null) runtime.packages().delete(ID);
        runtime.permissions().removePlugin(ID);
        reload(activity);
    }

    private static void reload(MainActivity activity) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod("reloadPluginsKeepingCurrentPage");
        method.setAccessible(true);
        method.invoke(activity);
    }

    private static byte[] packageBytes(int version) throws Exception {
        String manifest = """
                {"format":"ats-plugin","formatVersion":3,
                 "plugin":{"id":"test.widget_refresh","title":"Widget refresh fixture","description":"Device regression",
                   "version":"1.0.0","versionCode":%d,"minHostVersionCode":24,"minAndroidApi":26,"publisher":"test.publisher","kind":"tool"},
                 "platforms":["android"],
                 "runtime":{"ui":[{"id":"main","type":"declarative","entry":"ui/main.json"}],
                   "background":[{"id":"summary-worker","type":"javascript-worker","entry":"workers/summary.js","required":true,"timeoutMs":5000,"maxHeapBytes":16777216}],"providers":[]},
                 "requires":{"plugins":[],"capabilities":[{"id":"test.widget_summary","version":"^1.0.0","optional":false,"scopes":{}}]},
                 "provides":{"capabilities":[{"id":"test.widget_summary","version":"1.0.0","workerEntry":"summary-worker","methods":["test.widget_summary.get"]}]},
                 "contributes":{"tools":[{"id":"main","uiEntry":"main"}],"homeWidgets":[{"id":"summary","title":"Widget fixture","template":"metric","dataSource":"test.widget_summary.get","sizes":["2x2"]}]},
                 "datasets":[],"tasks":[]}
                """.formatted(version);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest.getBytes(StandardCharsets.UTF_8));
        files.put("ui/main.json", "{\"formatVersion\":1,\"body\":{\"type\":\"column\",\"children\":[{\"type\":\"text\",\"value\":\"Widget regression\"}]}}".getBytes(StandardCharsets.UTF_8));
        files.put("workers/summary.js", ("globalThis.atsWorkerMain=async()=>({title:'Widget fixture',state:'ready',value:" + version + "});").getBytes(StandardCharsets.UTF_8));
        JSONArray digests = new JSONArray();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            StringBuilder sha = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(file.getValue())) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            digests.put(new JSONObject().put("path", file.getKey()).put("size", file.getValue().length).put("sha256", sha.toString()));
        }
        files.put("META-INF/ats-integrity.json", new JSONObject().put("algorithm", "sha256").put("formatVersion", 1).put("files", digests).toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));zip.write(file.getValue());zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
