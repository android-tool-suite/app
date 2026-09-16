package com.androidtoolsuite.app.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;

import com.androidtoolsuite.app.plugin.runtime.HostServices;
import com.androidtoolsuite.app.plugin.runtime.HostTool;
import com.androidtoolsuite.app.migration.DatasetCategory;
import com.androidtoolsuite.app.migration.DatasetRestoreMode;
import com.androidtoolsuite.app.migration.DatasetBridge;
import com.androidtoolsuite.app.migration.DatasetDescriptor;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MigrationBridgeManagerTest {
    @Test
    public void deleteUsesReverseDependencyOrder() throws Exception {
        List<String> deleted = new ArrayList<>();
        List<MigrationBridgeManager.DatasetOption> options = MigrationBridgeManager.discover(
                null,
                List.of(plugin(deleted))
        );

        MigrationBridgeManager.delete(null, options, options);

        assertEquals(List.of("child", "parent"), deleted);
    }

    @Test
    public void deletingDependencyRequiresItsDependents() throws Exception {
        List<String> deleted = new ArrayList<>();
        List<MigrationBridgeManager.DatasetOption> options = MigrationBridgeManager.discover(
                null,
                List.of(plugin(deleted))
        );

        IOException error = assertThrows(IOException.class, () -> MigrationBridgeManager.delete(
                null,
                options,
                List.of(options.get(0))
        ));
        assertTrue(error.getMessage().contains("必须同时删除"));
        assertTrue(deleted.isEmpty());
    }

    @Test
    public void hostDataIsSplitIntoIndependentPackageItems() {
        MigrationBridgeManager.DatasetOption settings =
                MigrationBridgeManager.hostSettingsExportOption(10L);
        MigrationBridgeManager.DatasetOption state =
                MigrationBridgeManager.hostPluginStateExportOption(20L);
        MigrationBridgeManager.DatasetOption plugin =
                MigrationBridgeManager.hostPluginPackageExportOption("sample", "Sample", 30L);

        assertEquals("android_tool_suite/app-settings", settings.key());
        assertEquals("android_tool_suite/plugin-enabled-state", state.key());
        assertEquals("android_tool_suite/plugin-package.sample", plugin.key());
        assertEquals("sample", plugin.packagedPluginId());
        assertTrue(settings.descriptor.restoreModes.contains(DatasetRestoreMode.MERGE));
        assertEquals(List.of(DatasetRestoreMode.REPLACE), plugin.descriptor.restoreModes);
        assertTrue(!plugin.supportsDelete());
    }

    @Test
    public void hostMigrationInstallsAndRestoresMissingPluginDataInOnePass() throws Exception {
        AtomicBoolean hostRestored = new AtomicBoolean();
        List<String> imported = new ArrayList<>();
        DatasetDescriptor dataset = descriptor("parent", List.of());
        DatasetBridge restoredBridge = new DatasetBridge() {
            @Override
            public List<DatasetDescriptor> datasets(Activity activity) {
                return List.of(dataset);
            }

            @Override
            public void exportDataset(Activity activity, String datasetId, OutputStream output) {
            }

            @Override
            public boolean supportsImport(String datasetId, int dataFormatVersion) {
                return dataset.id.equals(datasetId)
                        && dataset.dataFormatVersion == dataFormatVersion;
            }

            @Override
            public boolean hasData(Activity activity, String datasetId) {
                return false;
            }

            @Override
            public void importDataset(
                    Activity activity,
                    String datasetId,
                    int dataFormatVersion,
                    DatasetRestoreMode restoreMode,
                    InputStream input
            ) throws IOException {
                if (!hostRestored.get()) throw new IOException("Host migration must run first");
                imported.add(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        };

        MigrationBridgeManager.DatasetOption host =
                MigrationBridgeManager.hostPluginPackageExportOption("sample", "Sample", 4L);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        DataPackageArchive.write(archive, new DataPackageArchive.WriteRequest(
                "test.host",
                "1.0",
                1,
                List.of(
                        new DataPackageArchive.ItemSource(
                                host.pluginId,
                                host.pluginTitle,
                                host.kind,
                                host.descriptor,
                                DataPackageArchive.Protection.NONE,
                                output -> output.write("host".getBytes(StandardCharsets.UTF_8))
                        ),
                        new DataPackageArchive.ItemSource(
                                "sample",
                                "Sample",
                                DataPackageArchive.ItemKind.PLUGIN_DATA,
                                dataset,
                                DataPackageArchive.Protection.NONE,
                                output -> output.write("payload".getBytes(StandardCharsets.UTF_8))
                        )
                ),
                new char[0]
        ));

        DataPackageArchive.ReadResult inspection = DataPackageArchive.inspect(
                new ByteArrayInputStream(archive.toByteArray())
        );
        List<MigrationBridgeManager.DatasetOption> initial =
                MigrationBridgeManager.matchDataPackageForImport(
                        null,
                        List.of(),
                        inspection.items,
                        true,
                        Collections.emptySet()
                );
        assertEquals(2, initial.size());
        assertTrue(initial.get(1).requiresBridgeResolution());

        File root = Files.createTempDirectory("ats-one-pass-restore").toFile();
        try {
            List<MigrationBridgeManager.ImportSelection> selected = List.of(
                    new MigrationBridgeManager.ImportSelection(initial.get(0), DatasetRestoreMode.REPLACE),
                    new MigrationBridgeManager.ImportSelection(initial.get(1), DatasetRestoreMode.REPLACE)
            );
            MigrationBridgeManager.restoreDataPackage(
                    null,
                    new ByteArrayInputStream(archive.toByteArray()),
                    new char[0],
                    selected,
                    new File(root, "staging"),
                    (hostSelections, staged) -> {
                        File hostPayload = staged.get(hostSelections.get(0).option.key());
                        assertEquals(
                                "host",
                                new String(Files.readAllBytes(hostPayload.toPath()), StandardCharsets.UTF_8)
                        );
                        hostRestored.set(true);
                    },
                    resolvedSelection -> MigrationBridgeManager.resolveDataPackageImportBridges(
                            null,
                            List.of(plugin(restoredBridge)),
                            resolvedSelection
                    )
            );
        } finally {
            Files.deleteIfExists(root.toPath());
        }

        assertTrue(hostRestored.get());
        assertEquals(List.of("payload"), imported);
    }

    @Test
    public void importUsesTargetModesAndReportsExistingData() throws Exception {
        DatasetDescriptor descriptor = new DatasetDescriptor(
                "records",
                "Records",
                DatasetCategory.DATA,
                10L,
                1,
                false,
                List.of(DatasetRestoreMode.REPLACE, DatasetRestoreMode.MERGE)
        );
        byte[] archive = writePackageItem(descriptor);
        DatasetBridge bridge = new DatasetBridge() {
            @Override public List<DatasetDescriptor> datasets(Activity activity) { return List.of(descriptor); }
            @Override public void exportDataset(Activity activity, String id, OutputStream output) { }
            @Override public boolean supportsImport(String id, int version) { return true; }
            @Override public boolean hasData(Activity activity, String id) { return true; }
            @Override public boolean supportsRestoreMode(
                    String id,
                    int version,
                    DatasetRestoreMode mode
            ) {
                return mode == DatasetRestoreMode.MERGE;
            }
        };

        DataPackageArchive.ReadResult inspection = DataPackageArchive.inspect(
                new ByteArrayInputStream(archive)
        );
        List<MigrationBridgeManager.DatasetOption> options =
                MigrationBridgeManager.matchDataPackageForImport(
                        null,
                        List.of(plugin(bridge)),
                        inspection.items,
                        false,
                        Collections.emptySet()
                );

        assertEquals(1, options.size());
        assertTrue(options.get(0).hasExistingData);
        assertEquals(List.of(DatasetRestoreMode.MERGE), options.get(0).descriptor.restoreModes);
    }

    private static byte[] writePackageItem(DatasetDescriptor descriptor) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataPackageArchive.write(output, new DataPackageArchive.WriteRequest(
                "test.host",
                "1.0",
                1,
                List.of(new DataPackageArchive.ItemSource(
                        "sample",
                        "Sample",
                        DataPackageArchive.ItemKind.PLUGIN_DATA,
                        descriptor,
                        DataPackageArchive.Protection.NONE,
                        item -> item.write('x')
                )),
                new char[0]
        ));
        return output.toByteArray();
    }

    private static HostTool plugin(List<String> deleted) {
        DatasetBridge bridge = new DatasetBridge() {
            @Override
            public List<DatasetDescriptor> datasets(Activity activity) {
                return List.of(
                        descriptor("parent", List.of()),
                        descriptor("child", List.of("parent"))
                );
            }

            @Override
            public void exportDataset(Activity activity, String datasetId, OutputStream output) {
            }

            @Override
            public boolean supportsDelete(String datasetId) {
                return true;
            }

            @Override
            public void deleteDataset(Activity activity, String datasetId) {
                deleted.add(datasetId);
            }
        };
        return plugin(bridge);
    }

    private static HostTool plugin(DatasetBridge bridge) {
        return new HostTool() {
            @Override public String id() { return "sample"; }
            @Override public String title() { return "Sample"; }
            @Override public String description() { return "Sample"; }
            @Override public boolean removable() { return true; }
            @Override public DatasetBridge datasetBridge() { return bridge; }
            @Override public View createView(Activity activity, HostServices host) { return null; }
            @Override public void onSelected() { }
            @Override public void onHostStateChanged() { }
            @Override public void onDestroy() { }
        };
    }

    private static DatasetDescriptor descriptor(String id, List<String> dependencies) {
        return new DatasetDescriptor(
                id,
                id,
                DatasetCategory.DATA,
                0L,
                1,
                false,
                DatasetRestoreMode.REPLACE,
                dependencies
        );
    }
}
