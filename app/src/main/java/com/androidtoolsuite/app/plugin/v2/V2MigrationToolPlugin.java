package com.androidtoolsuite.app.plugin.v2;

import android.app.Activity;
import android.view.View;

import com.androidtoolsuite.app.plugin.api.PluginHost;
import com.androidtoolsuite.app.plugin.api.ToolPlugin;
import com.androidtoolsuite.app.plugin.migration.DatasetCategory;
import com.androidtoolsuite.app.plugin.migration.DatasetRestoreMode;
import com.androidtoolsuite.app.plugin.migration.LegacyDataBridge;
import com.androidtoolsuite.app.plugin.migration.LegacyDatasetDescriptor;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Temporary adapter that lets the existing .atsbackup v3 UI manage Runtime v2 Datasets. */
public final class V2MigrationToolPlugin implements ToolPlugin {
    private final V2PackageStore.InstalledPlugin installed;
    private final V2DatasetService datasets;
    private final LegacyDataBridge bridge = new Bridge();

    public V2MigrationToolPlugin(V2PackageStore.InstalledPlugin installed, V2DatasetService datasets) {
        this.installed = installed;
        this.datasets = datasets;
    }

    @Override public String id() { return installed.manifest.plugin.id; }
    @Override public String title() { return installed.manifest.plugin.title; }
    @Override public String description() { return installed.manifest.plugin.description; }
    @Override public String version() { return installed.manifest.plugin.version; }
    @Override public boolean removable() { return true; }
    @Override public LegacyDataBridge legacyDataBridge() { return bridge; }
    @Override public View createView(Activity activity, PluginHost host) { return new View(activity); }
    @Override public void onSelected() { }
    @Override public void onHostStateChanged() { }
    @Override public void onDestroy() { }

    private RuntimePluginManifest.Dataset descriptor(String datasetId) {
        for (RuntimePluginManifest.Dataset value : installed.manifest.datasets) {
            if (value.id.equals(datasetId)) return value;
        }
        return null;
    }

    private final class Bridge implements LegacyDataBridge {
        @Override
        public List<LegacyDatasetDescriptor> datasets(Activity activity) throws IOException {
            List<LegacyDatasetDescriptor> result = new ArrayList<>();
            for (RuntimePluginManifest.Dataset dataset : installed.manifest.datasets) {
                if (!dataset.restoreModes.contains("replace") || !datasets.hasDataset(id(), dataset.id)) continue;
                result.add(new LegacyDatasetDescriptor(
                        dataset.id,
                        dataset.title,
                        category(dataset.category),
                        datasets.datasetSize(id(), dataset.id),
                        dataset.formatVersion,
                        dataset.sensitive,
                        Collections.singletonList(DatasetRestoreMode.REPLACE),
                        dataset.dependsOn
                ));
            }
            return result;
        }

        @Override
        public void exportDataset(Activity activity, String datasetId, OutputStream output) throws IOException {
            require(datasetId);
            datasets.exportDataset(id(), datasetId, output);
        }

        @Override
        public boolean supportsImport(String datasetId, int dataFormatVersion) {
            RuntimePluginManifest.Dataset dataset = descriptor(datasetId);
            return dataset != null && dataset.formatVersion == dataFormatVersion
                    && dataset.restoreModes.contains("replace");
        }

        @Override
        public boolean hasData(Activity activity, String datasetId) throws IOException {
            require(datasetId);
            return datasets.hasDataset(id(), datasetId);
        }

        @Override
        public boolean supportsRestoreMode(
                String datasetId,
                int dataFormatVersion,
                DatasetRestoreMode restoreMode
        ) {
            return restoreMode == DatasetRestoreMode.REPLACE && supportsImport(datasetId, dataFormatVersion);
        }

        @Override
        public void importDataset(
                Activity activity,
                String datasetId,
                int dataFormatVersion,
                DatasetRestoreMode restoreMode,
                InputStream input
        ) throws IOException {
            if (!supportsRestoreMode(datasetId, dataFormatVersion, restoreMode)) {
                throw new IOException("Runtime v2 Dataset format or restore mode is unsupported");
            }
            datasets.importDataset(id(), datasetId, "replace", input);
        }

        @Override
        public boolean supportsDelete(String datasetId) {
            return descriptor(datasetId) != null;
        }

        @Override
        public void deleteDataset(Activity activity, String datasetId) throws IOException {
            require(datasetId);
            try {
                datasets.delete(id(), datasetId);
            } catch (CapabilityFailure error) {
                throw new IOException(error.getMessage(), error);
            }
        }

        private void require(String datasetId) throws IOException {
            if (descriptor(datasetId) == null) throw new IOException("Unknown Runtime v2 Dataset: " + datasetId);
        }
    }

    private static DatasetCategory category(String value) {
        switch (value) {
            case "settings": return DatasetCategory.SETTINGS;
            case "cache": return DatasetCategory.CACHE;
            case "secret": return DatasetCategory.SECRET;
            default: return DatasetCategory.DATA;
        }
    }
}
