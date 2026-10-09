package com.androidtoolsuite.app.host;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.androidtoolsuite.app.migration.MigrationBridgeManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises callbacks after recomposition, rather than only testing set arithmetic. */
@RunWith(AndroidJUnit4.class)
public final class DataSelectionInstrumentedTest {
    @Test public void pluginImageAndRecordPickersUseTheDebugExportMarker() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var storage = com.androidtoolsuite.app.plugin.runtime.PluginRuntime.get(instrumentation.getTargetContext()).storage();
        String pluginId = "gacha_analysis", sessionId = "export-name-test", blobId = "export-name-test." + java.util.UUID.randomUUID();
        storage.importBlob(pluginId, sessionId, blobId, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AtomicReference<android.content.Intent> picker = new AtomicReference<>();
        var monitor = new android.app.Instrumentation.ActivityMonitor() {
            @Override public android.app.Instrumentation.ActivityResult onStartActivity(android.content.Intent intent) {
                if (!android.content.Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) return null;
                picker.set(new android.content.Intent(intent));
                return new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null);
            }
        };
        try (var scenario = ActivityScenario.launch(MainActivity.class)) {
            instrumentation.addMonitor(monitor);
            for (String name : List.of("UIGF-20261009-090530.json", "Phigros-B30-20261009-090530.png", "report-debug.csv")) {
                AtomicReference<java.util.concurrent.CompletableFuture<org.json.JSONObject>> future = new AtomicReference<>();
                picker.set(null);
                scenario.onActivity(activity -> future.set(activity.saveFile(pluginId, sessionId, blobId, name, name.endsWith("png") ? "image/png" : name.endsWith("csv") ? "text/csv" : "application/json", 1024)));
                instrumentation.waitForIdleSync();
                var cancellation = org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException.class, () -> future.get().get(5, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("CANCELLED", ((com.androidtoolsuite.app.plugin.runtime.CapabilityFailure) cancellation.getCause()).code);
                assertNotNull(picker.get());
                String expected = name.equals("report-debug.csv") ? name : name.substring(0, name.lastIndexOf('.')) + "-debug" + name.substring(name.lastIndexOf('.'));
                assertEquals(expected, picker.get().getStringExtra(android.content.Intent.EXTRA_TITLE));
            }
        } finally {
            instrumentation.removeMonitor(monitor);
            storage.closeSession(sessionId);
            storage.blobDelete(pluginId, blobId);
        }
    }

    @Test public void pluginDetailDataActionsAreScopedAndKeepVersionOnlyForPackageExports() {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<android.content.Intent> pickerIntent = new AtomicReference<>();
        AtomicReference<String> importOwner = new AtomicReference<>();
        AtomicReference<MainActivity> currentActivity = new AtomicReference<>();
        var monitor = new android.app.Instrumentation.ActivityMonitor() {
            @Override public android.app.Instrumentation.ActivityResult onStartActivity(android.content.Intent intent) {
                if (!android.content.Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())
                        && !android.content.Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) return null;
                pickerIntent.set(new android.content.Intent(intent));
                if (android.content.Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())) {
                    try {
                        var owner = MainActivity.class.getDeclaredField("pendingDataOwnerFilter");
                        owner.setAccessible(true);
                        importOwner.set((String) owner.get(currentActivity.get()));
                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                }
                // Exercise the real picker intent and cancellation callback without creating files.
                return new android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null);
            }
        };
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            instrumentation.addMonitor(monitor);
            scenario.onActivity(currentActivity::set);
            for (String pluginId : List.of("gacha_analysis", "phigros_advisor")) {
                scenario.onActivity(activity -> activity.showPluginManagementForUi(pluginId, "detail"));
                scrollToText("导出数据");
                capture("ui-plugin-data-" + pluginId + ".png");
                clickText("导出数据");
                awaitText("导出数据包");
                scenario.onActivity(activity -> {
                    var options = activity.migrationBridgeExportOptionsForUi();
                    assertFalse(options.isEmpty());
                    assertTrue("The current plugin's package remains available for backup", options.stream().anyMatch(option -> option.isPluginPackage()));
                    for (var option : options) assertEquals(pluginId, option.isPluginPackage() ? option.packagedPluginId() : option.pluginId);
                });
                clickText("继续");
                clickText("文件保护");
                clickText("不加密");
                pickerIntent.set(null);
                clickText("选择保存位置");
                instrumentation.waitForIdleSync();
                assertNotNull(pickerIntent.get());
                assertTrue(pickerIntent.get().getStringExtra(android.content.Intent.EXTRA_TITLE)
                        .matches(pluginId + "-data-\\d{8}-\\d{6}-debug\\.atsbackup"));
                awaitText("返回选择");
                clickText("返回选择");
                clickText("取消");
                awaitText("放弃当前选择？");
                clickText("放弃并退出");
                awaitText("插件详情");
                scenario.onActivity(activity -> assertTrue(activity.migrationBridgeExportOptionsForUi().isEmpty()));

                scrollToText("导入数据");
                pickerIntent.set(null);
                clickText("导入数据");
                instrumentation.waitForIdleSync();
                assertNotNull(pickerIntent.get());
                assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT, pickerIntent.get().getAction());
                assertEquals(pluginId, importOwner.get());
                awaitText("插件详情");

                scrollToText("删除数据");
                clickText("删除数据");
                awaitText("删除插件数据");
                scenario.onActivity(activity -> {
                    var options = activity.migrationBridgeDeleteOptionsForUi();
                    assertFalse(options.isEmpty());
                    for (var option : options) assertEquals(pluginId, option.pluginId);
                    assertTrue(((DeleteDraft) activity.uiStateForUi().getDataDrafts().get("delete")).getSelected().isEmpty());
                });
                capture("ui-plugin-data-delete-" + pluginId + ".png");
                clickText("取消");
                awaitText("插件详情");
                scenario.onActivity(activity -> assertTrue(activity.migrationBridgeDeleteOptionsForUi().isEmpty()));

                scrollToText("导出插件包");
                pickerIntent.set(null);
                final String[] version = {null};
                scenario.onActivity(activity -> version[0] = activity.importedDescriptorsForUi().stream().filter(item -> item.id.equals(pluginId)).findFirst().orElseThrow().version);
                clickText("导出插件包");
                instrumentation.waitForIdleSync();
                assertNotNull(pickerIntent.get());
                assertTrue(pickerIntent.get().getStringExtra(android.content.Intent.EXTRA_TITLE)
                        .equals(pluginId + "-v" + version[0] + "-debug.atsplugin"));
            }
            scenario.onActivity(activity -> activity.navigateForUi(0));
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private static void scrollToText(String text) {
        for (int attempt = 0; attempt < 10; attempt++) {
            var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            if (android.os.Build.VERSION.SDK_INT >= 35) automation.clearCache();
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            collectNodes(automation.getRootInActiveWindow(), nodes);
            for (var node : nodes) if (node.isVisibleToUser() && text.contentEquals(node.getText() == null ? "" : node.getText())) return;
            for (var node : nodes) if (node.isVisibleToUser() && node.isScrollable()
                    && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break;
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            SystemClock.sleep(100);
        }
        fail("Expected plugin detail action: " + text);
    }

    @Test public void selectingOneRowAfterClearingGroupDoesNotResurrectOldSelection() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> activity.prepareMigrationBridgeExportForPluginUi(MigrationBridgeManager.HOST_OWNER_ID));
            clickText("批量选择");
            clickText("取消当前选择");
            assertSelection(scenario, Collections.emptySet());
            clickText("Android Tool Suite");
            clickText("应用设置");
            assertSelection(scenario, Collections.singleton("android_tool_suite/app-settings"));
            capture("ui-final-data-selection.png");
            clickText("继续");
            awaitText("文件保护");
            capture("ui-final-data-settings.png");
            clickText("返回选择");
            assertSelection(scenario, Collections.singleton("android_tool_suite/app-settings"));
            clickText("应用设置");
            assertSelection(scenario, Collections.emptySet());
            scenario.onActivity(MainActivity::dismissMigrationBridgeExportForUi);
        }
    }

    private static void awaitText(String text) {
        long deadline=SystemClock.uptimeMillis()+5000;
        do {
            List<AccessibilityNodeInfo> nodes=new ArrayList<>();
            collectNodes(InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow(),nodes);
            for(var node:nodes)if(node.isVisibleToUser()&&text.contentEquals(node.getText()==null?"":node.getText()))return;
            SystemClock.sleep(30);
        }while(SystemClock.uptimeMillis()<deadline);
        fail("Expected rendered step: "+text);
    }

    private static void capture(String name) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        SystemClock.sleep(100);
        try {
            var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
            var bitmap=InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(bitmap);
            try(var output=new java.io.FileOutputStream(new java.io.File(context.getCacheDir(),name))){assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));}
            finally{bitmap.recycle();}
        }catch(java.io.IOException error){throw new AssertionError(error);}
    }

    private static void assertSelection(ActivityScenario<MainActivity> scenario, Set<String> expected) {
        AtomicReference<Set<String>> actual = new AtomicReference<>();
        long deadline = SystemClock.uptimeMillis() + 5_000;
        do {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                ExportDraft draft = (ExportDraft) activity.uiStateForUi().getDataDrafts().get("export");
                actual.set(draft == null ? null : draft.getSelected());
            });
            if (expected.equals(actual.get()) && hasRenderedSelection(expected.size())) return;
            SystemClock.sleep(30);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals(expected, actual.get());
        assertTrue("The visible selection summary must match the model", hasRenderedSelection(expected.size()));
    }

    private static boolean hasRenderedSelection(int count) {
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        for (AccessibilityNodeInfo node : nodes) if (node.getText() != null
                && node.getText().toString().startsWith("已选择 " + count + " 项")) return true;
        return false;
    }

    private static void clickText(String text) {
        long deadline = SystemClock.uptimeMillis() + 10_000;
        do {
            var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
            // Compose can replace the footer's semantics node after selection changes. Refresh its
            // clickable ancestor as well as the label; never click a stale cached visibility state.
            if (android.os.Build.VERSION.SDK_INT >= 35) automation.clearCache();
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> nodes = new ArrayList<>();
                collectNodes(root, nodes);
                for (AccessibilityNodeInfo node : nodes) {
                    if (!text.contentEquals(node.getText() == null ? "" : node.getText())) continue;
                    AccessibilityNodeInfo target = node;
                    while (target != null) {
                        if (!target.refresh()) { target = null; break; }
                        if (target.isClickable()) break;
                        target = target.getParent();
                    }
                    if (target != null && target.isVisibleToUser() && target.isEnabled()
                            && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                        return;
                    }
                }
            }
            SystemClock.sleep(30);
        } while (SystemClock.uptimeMillis() < deadline);
        AccessibilityNodeInfo root = InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectNodes(root, nodes);
        StringBuilder visible = new StringBuilder();
        for (AccessibilityNodeInfo node : nodes) if (node.getText() != null && !node.isPassword()) {
            visible.append(node.getText()).append(" | ");
        }
        try {
            java.io.File file = new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "ui-selection-test-failure.png");
            try (java.io.OutputStream output = new java.io.FileOutputStream(file)) {
                InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            }
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        fail("Current UI action not found: " + text + "; visible: " + visible);
    }
    private static void collectNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> nodes) {
        if (node == null || nodes.size() >= 1024) return;
        if (!node.refresh()) return;
        nodes.add(node);
        for (int index = 0; index < node.getChildCount(); index++) collectNodes(node.getChild(index), nodes);
    }
}
