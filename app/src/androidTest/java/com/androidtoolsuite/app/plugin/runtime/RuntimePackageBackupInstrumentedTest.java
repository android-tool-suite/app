package com.androidtoolsuite.app.plugin.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.androidtoolsuite.app.migration.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public final class RuntimePackageBackupInstrumentedTest {
    private static final String ID = "test.runtime_v2_data";
    private PluginRuntime runtime;
    private Context context;
    @Before public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        runtime = PluginRuntime.get(context);
        cleanup();
    }
    @After public void cleanup() throws Exception {
        if (runtime.packages().find(ID) != null) {
            runtime.datasets().delete(ID, "credentials");
            runtime.datasets().delete(ID, "settings");
            runtime.packages().delete(ID);
        }
    }

    @Test public void localUnpublishedPackageAndDatasetRestoreTogether() throws Exception {
        byte[] original = DatasetServiceInstrumentedTest.packageBytes();
        PluginPackageStore.InstallSession install = RuntimePackageInstaller.begin(runtime.packages(), original, ID);
        runtime.packages().confirmInstall(install);
        byte[] data = "{\"formatVersion\":1,\"localProject\":true}".getBytes(StandardCharsets.UTF_8);
        MigrationToolPlugin bridge = new MigrationToolPlugin(runtime.packages().find(ID), runtime.datasets());
        bridge.datasetBridge().importDataset(null, "settings", 1, DatasetRestoreMode.REPLACE, new ByteArrayInputStream(data));
        List<MigrationBridgeManager.DatasetOption> options = new ArrayList<>(MigrationBridgeManager.discover(null, List.of(bridge)));
        options.add(MigrationBridgeManager.hostPluginPackageExportOption(ID, "Local project", original.length));
        List<MigrationBridgeManager.ExportSelection> exports = new ArrayList<>();
        for (MigrationBridgeManager.DatasetOption option : options) exports.add(new MigrationBridgeManager.ExportSelection(option, DataPackageArchive.Protection.NONE));
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        MigrationBridgeManager.writeDataPackage(null, backup, "test.app", "1.0", 1, exports,
                (option, output) -> output.write(runtime.packages().exportPackage(option.packagedPluginId())), new char[0]);
        cleanup();
        DataPackageArchive.ReadResult inspected = DataPackageArchive.inspect(new ByteArrayInputStream(backup.toByteArray()));
        List<MigrationBridgeManager.ImportSelection> selected = new ArrayList<>();
        for (MigrationBridgeManager.DatasetOption option : MigrationBridgeManager.matchDataPackageForImport(null, List.of(), inspected.items, true, Set.of())) {
            selected.add(new MigrationBridgeManager.ImportSelection(option, DatasetRestoreMode.REPLACE));
        }
        assertEquals(2, selected.size());
        try (RuntimePackageRestore packages = new RuntimePackageRestore(runtime.packages())) {
            MigrationBridgeManager.restoreDataPackage(null, new ByteArrayInputStream(backup.toByteArray()), new char[0], selected,
                    new File(context.getCacheDir(), "package-roundtrip-" + UUID.randomUUID()), packages::install,
                    items -> MigrationBridgeManager.resolveDataPackageImportBridges(null,
                            List.of(new MigrationToolPlugin(runtime.packages().find(ID), runtime.datasets())), items));
            packages.commit();
        }
        assertArrayEquals(original, runtime.packages().exportPackage(ID));
        assertFalse(runtime.packages().isEnabled(ID));
        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        runtime.datasets().exportDataset(ID, "settings", restored);
        assertArrayEquals(data, restored.toByteArray());
    }

    @Test public void mismatchedArchiveIdRollsBackAndUncommittedSessionRestoresPrevious() throws Exception {
        byte[] bytes = DatasetServiceInstrumentedTest.packageBytes();
        assertThrows(IOException.class, () -> RuntimePackageInstaller.begin(runtime.packages(), bytes, "wrong.id"));
        assertNull(runtime.packages().find(ID));
        PluginPackageStore.InstallSession session = RuntimePackageInstaller.begin(runtime.packages(), bytes, ID);
        runtime.packages().confirmInstall(session);
        String generation = runtime.packages().find(ID).generationDirectory.getName();
        PluginPackageStore.InstallSession pending = RuntimePackageInstaller.begin(runtime.packages(), DatasetServiceInstrumentedTest.packageBytes(2), ID);
        assertNotEquals(generation, runtime.packages().find(ID).generationDirectory.getName());
        runtime.packages().rollbackInstall(pending);
        assertEquals(generation, runtime.packages().find(ID).generationDirectory.getName());
        assertThrows(IOException.class, () -> RuntimePackageInstaller.begin(runtime.packages(), new byte[]{1,2,3}, ID));
        assertArrayEquals(bytes, runtime.packages().exportPackage(ID));
    }

    @Test public void failedMultiDatasetRestoreRollsBackAllGenerationsAndSecrets() throws Exception {
        PluginPackageStore.InstallSession install = RuntimePackageInstaller.begin(runtime.packages(), DatasetServiceInstrumentedTest.packageBytes(), ID);
        runtime.packages().confirmInstall(install);
        DatasetService service = runtime.datasets();
        byte[] before = "{\"formatVersion\":1,\"value\":\"before\"}".getBytes(StandardCharsets.UTF_8);
        service.importDataset(ID, "settings", "replace", new ByteArrayInputStream(before));
        service.secretSet(ID, "credentials", "test-token", "before");
        assertThrows(IOException.class, () -> service.withRestoreRollback(Set.of(ID), () -> {
            for (int i = 0; i < 3; i++) {
                byte[] changed = ("{\"formatVersion\":1,\"value\":" + i + "}").getBytes(StandardCharsets.UTF_8);
                service.importDataset(ID, "settings", "replace", new ByteArrayInputStream(changed));
            }
            try { service.secretSet(ID, "credentials", "test-token", "changed"); }
            catch (CapabilityFailure error) { throw new IOException(error); }
            service.importDataset(ID, "settings", "replace", new ByteArrayInputStream("invalid".getBytes(StandardCharsets.UTF_8)));
        }));
        ByteArrayOutputStream actual = new ByteArrayOutputStream();
        service.exportDataset(ID, "settings", actual);
        assertArrayEquals(before, actual.toByteArray());
        assertEquals("before", service.secretGet(ID, "credentials", "test-token").getString("value"));
    }
}
