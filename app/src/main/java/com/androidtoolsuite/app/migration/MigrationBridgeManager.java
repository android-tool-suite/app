package com.androidtoolsuite.app.migration;

import android.app.Activity;

import com.androidtoolsuite.app.plugin.api.ToolPlugin;
import com.androidtoolsuite.app.plugin.migration.LegacyDataBridge;
import com.androidtoolsuite.app.plugin.migration.LegacyDatasetDescriptor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates the temporary, read-only v1-to-v2 Migration Bridge export. */
public final class MigrationBridgeManager {
    private MigrationBridgeManager() {
    }

    public static final class DatasetOption {
        public final String pluginId;
        public final String pluginTitle;
        public final LegacyDatasetDescriptor descriptor;
        private final LegacyDataBridge bridge;

        private DatasetOption(
                String pluginId,
                String pluginTitle,
                LegacyDatasetDescriptor descriptor,
                LegacyDataBridge bridge
        ) {
            this.pluginId = pluginId;
            this.pluginTitle = pluginTitle;
            this.descriptor = descriptor;
            this.bridge = bridge;
        }

        public String key() {
            return pluginId + "/" + descriptor.id;
        }
    }

    public static List<DatasetOption> discover(Activity activity, List<ToolPlugin> plugins)
            throws IOException {
        Map<String, DatasetOption> options = new LinkedHashMap<>();
        for (ToolPlugin plugin : plugins) {
            LegacyDataBridge bridge;
            List<LegacyDatasetDescriptor> datasets;
            try {
                bridge = plugin.legacyDataBridge();
                if (bridge == null) continue;
                datasets = bridge.datasets(activity);
            } catch (RuntimeException error) {
                throw new IOException("无法读取 " + plugin.title() + " 的迁移清单", error);
            }
            if (datasets == null) {
                throw new IOException(plugin.title() + " 返回了空迁移清单");
            }
            for (LegacyDatasetDescriptor descriptor : datasets) {
                if (descriptor == null) {
                    throw new IOException(plugin.title() + " 包含无效 Dataset");
                }
                DatasetOption option = new DatasetOption(plugin.id(), plugin.title(), descriptor, bridge);
                if (options.putIfAbsent(option.key(), option) != null) {
                    throw new IOException("包含重复 Dataset：" + option.key());
                }
            }
        }
        for (DatasetOption option : options.values()) {
            for (String dependency : option.descriptor.dependencies) {
                if (!options.containsKey(option.pluginId + "/" + dependency)) {
                    throw new IOException("Dataset 缺少依赖：" + option.pluginId + "/" + dependency);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(options.values()));
    }

    public static void write(
            Activity activity,
            OutputStream target,
            String sourcePackage,
            String sourceVersionName,
            int sourceVersionCode,
            List<DatasetOption> selected,
            char[] password
    ) throws IOException {
        List<BackupArchiveV2.DatasetSource> sources = new ArrayList<>();
        for (DatasetOption option : selected) {
            sources.add(new BackupArchiveV2.DatasetSource(
                    option.pluginId,
                    option.descriptor,
                    output -> option.bridge.exportDataset(activity, option.descriptor.id, output)
            ));
        }
        BackupArchiveV2.write(target, new BackupArchiveV2.WriteRequest(
                sourcePackage,
                sourceVersionName,
                sourceVersionCode,
                sources,
                password
        ));
    }
}
