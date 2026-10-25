package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.*;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.androidtoolsuite.app.BuildConfig;
import com.androidtoolsuite.app.debug.DebugCommandReceiver;
import com.androidtoolsuite.app.update.UpdateCatalog;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public final class RuntimePackageInstallInstrumentedTest {
    private static final String ID = "test.runtime_v2_data";
    private static final byte[] DATA = "{\"formatVersion\":1,\"value\":\"preserved\"}"
            .getBytes(StandardCharsets.UTF_8);
    private PluginRuntime runtime;
    private Context context;

    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        runtime = PluginRuntime.get(context);
        cleanup();
    }

    @After public void cleanup() throws Exception {
        if (runtime == null) return;
        runtime.scheduler().cancelPlugin(ID);
        if (runtime.packages().find(ID) != null) {
            runtime.datasets().delete(ID, "settings");
            runtime.datasets().delete(ID, "credentials");
            runtime.packages().delete(ID);
        }
        runtime.permissions().removePlugin(ID);
    }

    @Test public void localDowngradeAndSameVersionReplacementPreserveDataAndEnabledState() throws Exception {
        seed();
        byte[] lower = DatasetServiceInstrumentedTest.packageBytes(1);
        runtime.packages().confirmInstall(RuntimePackageInstaller.begin(runtime.packages(), lower, ID));
        assertArrayEquals(lower, runtime.packages().exportPackage(ID));
        assertPreserved();
        byte[] replacement = DatasetServiceInstrumentedTest.packageBytes(1, 1, 1, 24, "Rebuilt locally");
        runtime.packages().confirmInstall(RuntimePackageInstaller.begin(runtime.packages(), replacement, ID));
        assertArrayEquals(replacement, runtime.packages().exportPackage(ID));
        assertPreserved();
    }

    @Test public void adbBroadcastDowngradesAndReplacesWithoutExtraFlags() throws Exception {
        seed();
        JSONObject downgraded = importViaAdb(DatasetServiceInstrumentedTest.packageBytes(1));
        assertTrue(downgraded.toString(), downgraded.getBoolean("ok"));
        assertTrue(downgraded.getJSONObject("data").getBoolean("downgraded"));
        assertFalse(downgraded.getJSONObject("data").getBoolean("sameVersionReplacement"));
        assertPreserved();
        byte[] replacement = DatasetServiceInstrumentedTest.packageBytes(1, 1, 1, 24, "Rebuilt via ADB");
        JSONObject replaced = importViaAdb(replacement);
        assertTrue(replaced.toString(), replaced.getBoolean("ok"));
        assertTrue(replaced.getJSONObject("data").getBoolean("sameVersionReplacement"));
        assertFalse(replaced.getJSONObject("data").getBoolean("downgraded"));
        assertArrayEquals(replacement, runtime.packages().exportPackage(ID));
        assertPreserved();
    }

    @Test public void incompatibleDowngradeIsRejectedByLocalRepositoryAndAdbBeforeActivation() throws Exception {
        seed();
        byte[] previous = runtime.packages().exportPackage(ID);
        String generation = runtime.packages().find(ID).generationDirectory.getName();
        byte[] incompatible = DatasetServiceInstrumentedTest.packageBytes(1, 2, 1, 24, "Incompatible");
        assertThrows(IOException.class, () -> RuntimePackageInstaller.begin(runtime.packages(), incompatible, ID));
        assertThrows(IOException.class, () -> RuntimePackageInstaller.beginRepository(
                runtime.packages(), incompatible, release(1, 1, 2)));
        JSONObject response = importViaAdb(incompatible);
        assertFalse(response.toString(), response.getBoolean("ok"));
        assertEquals(generation, runtime.packages().find(ID).generationDirectory.getName());
        assertArrayEquals(previous, runtime.packages().exportPackage(ID));
        assertPreserved();
    }

    @Test public void repositoryCompatibilityAndMetadataChecksUseTheSameTransaction() throws Exception {
        seed();
        byte[] lower = DatasetServiceInstrumentedTest.packageBytes(1);
        assertThrows(IOException.class, () -> RuntimePackageInstaller.beginRepository(
                runtime.packages(), lower, release(1, 0, 0)));
        assertThrows(IOException.class, () -> RuntimePackageInstaller.beginRepository(
                runtime.packages(), lower, release(2, 0, 1)));
        assertEquals(2, runtime.packages().find(ID).manifest.plugin.versionCode);
        PluginPackageStore.InstallSession install = RuntimePackageInstaller.beginRepository(
                runtime.packages(), lower, release(1, 0, 1));
        runtime.packages().confirmInstall(install);
        assertTrue(runtime.packages().find(ID).repositoryVerified);
        assertEquals("https://example.test/release", runtime.packages().find(ID).source);
        assertArrayEquals(lower, runtime.packages().exportPackage(ID));
        assertPreserved();
    }

    @Test public void adbRejectsUnsupportedHostAndAndroidRequirements() throws Exception {
        seed();
        byte[] previous = runtime.packages().exportPackage(ID);
        JSONObject host = importViaAdb(DatasetServiceInstrumentedTest.packageBytes(
                1, 1, BuildConfig.VERSION_CODE + 1, 24, "Newer host required"));
        assertFalse(host.toString(), host.getBoolean("ok"));
        JSONObject android = importViaAdb(DatasetServiceInstrumentedTest.packageBytes(
                1, 1, 1, Build.VERSION.SDK_INT + 1, "Newer Android required"));
        assertFalse(android.toString(), android.getBoolean("ok"));
        assertArrayEquals(previous, runtime.packages().exportPackage(ID));
        assertPreserved();
    }

    @Test public void interruptedDowngradeRestoresPreviousPackageAndSource() throws Exception {
        seed();
        byte[] previous = runtime.packages().exportPackage(ID);
        String source = runtime.packages().find(ID).source;
        RuntimePackageInstaller.begin(runtime.packages(), DatasetServiceInstrumentedTest.packageBytes(1),
                ID, "adb-debug", "debug");
        assertEquals(1, runtime.packages().find(ID).manifest.plugin.versionCode);
        PluginPackageStore recovered = new PluginPackageStore(context);
        assertArrayEquals(previous, recovered.exportPackage(ID));
        assertEquals(source, recovered.find(ID).source);
        assertPreserved();
    }

    private void seed() throws Exception {
        runtime.packages().confirmInstall(RuntimePackageInstaller.begin(
                runtime.packages(), DatasetServiceInstrumentedTest.packageBytes(2), ID));
        runtime.packages().setEnabled(ID, true);
        runtime.datasets().importDataset(ID, "settings", "replace", new ByteArrayInputStream(DATA));
        runtime.datasets().secretSet(ID, "credentials", "test-key", "preserved");
    }

    private void assertPreserved() throws Exception {
        assertTrue(runtime.packages().isEnabled(ID));
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        runtime.datasets().exportDataset(ID, "settings", actual);
        assertArrayEquals(DATA, actual.toByteArray());
        assertEquals("preserved", runtime.datasets().secretGet(ID, "credentials", "test-key").getString("value"));
    }

    private JSONObject importViaAdb(byte[] bytes) throws Exception {
        File inbox = new File(context.getFilesDir(), "debug-inbox");
        assertTrue(inbox.isDirectory() || inbox.mkdirs());
        File packageFile = new File(inbox, "install-regression.atsplugin");
        try (FileOutputStream output = new FileOutputStream(packageFile)) { output.write(bytes); }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        automation.adoptShellPermissionIdentity("android.permission.DUMP");
        try {
            context.sendOrderedBroadcast(new Intent(DebugCommandReceiver.ACTION)
                            .setClass(context, DebugCommandReceiver.class)
                            .putExtra("command", "import-plugin")
                            .putExtra("path", packageFile.getName()),
                    null, new BroadcastReceiver() {
                        @Override public void onReceive(Context ignored, Intent intent) {
                            result.set(getResultData());
                            completed.countDown();
                        }
                    }, new Handler(Looper.getMainLooper()), 0, null, null);
            assertTrue("Debug import broadcast timed out", completed.await(30, TimeUnit.SECONDS));
            assertNotNull("Debug import returned no response", result.get());
            return new JSONObject(result.get());
        } finally {
            automation.dropShellPermissionIdentity();
            assertTrue(packageFile.delete());
        }
    }

    private static UpdateCatalog.PluginRelease release(int versionCode, int minReadable, int maxReadable)
            throws Exception {
        JSONObject plugin = new JSONObject()
                .put("id", ID).put("title", "Install fixture")
                .put("repositoryUrl", "https://example.test/repository")
                .put("versionName", "1.0.0").put("versionCode", versionCode)
                .put("minHostVersionCode", 1).put("minAndroidApi", 24)
                .put("releaseUrl", "https://example.test/release")
                .put("downloadUrl", "https://example.test/plugin.atsplugin")
                .put("size", 1).put("sha256", "a".repeat(64));
        if (maxReadable > 0) plugin.put("dataCompatibility", new JSONObject()
                .put("schemaVersion", 1).put("dataFormatVersion", 1)
                .put("minReadableDataFormatVersion", minReadable)
                .put("maxReadableDataFormatVersion", maxReadable));
        return UpdateCatalog.parse(new JSONObject().put("schemaVersion", 1)
                .put("plugins", new org.json.JSONArray().put(plugin)).toString()).findPlugin(ID);
    }
}
