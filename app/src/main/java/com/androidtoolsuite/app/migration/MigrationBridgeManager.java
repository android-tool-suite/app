package com.androidtoolsuite.app.migration;

import android.app.Activity;

import com.androidtoolsuite.app.plugin.api.ToolPlugin;
import com.androidtoolsuite.app.plugin.migration.LegacyDataBridge;
import com.androidtoolsuite.app.plugin.migration.LegacyDatasetDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates the temporary v1-to-v2 Migration Bridge export and staged restore. */
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

    /** Matches archive records to installed legacy bridges without requiring data to exist yet. */
    public static List<DatasetOption> matchForImport(
            List<ToolPlugin> plugins,
            List<BackupArchiveV2.DatasetRecord> datasets
    ) throws IOException {
        Map<String, PluginBridge> bridges = new LinkedHashMap<>();
        for (ToolPlugin plugin : plugins) {
            LegacyDataBridge bridge;
            try {
                bridge = plugin.legacyDataBridge();
            } catch (RuntimeException error) {
                throw new IOException("无法读取 " + plugin.title() + " 的迁移接口", error);
            }
            if (bridge != null) bridges.putIfAbsent(plugin.id(), new PluginBridge(plugin.title(), bridge));
        }

        Map<String, DatasetOption> options = new LinkedHashMap<>();
        for (BackupArchiveV2.DatasetRecord dataset : datasets) {
            PluginBridge target = bridges.get(dataset.pluginId);
            if (target == null) continue;
            boolean supported;
            try {
                supported = target.bridge.supportsImport(
                        dataset.descriptor.id,
                        dataset.descriptor.dataFormatVersion
                );
            } catch (RuntimeException error) {
                throw new IOException("无法检查 " + target.title + " 的 Dataset 兼容性", error);
            }
            if (!supported) continue;
            DatasetOption option = new DatasetOption(
                    dataset.pluginId,
                    target.title,
                    dataset.descriptor,
                    target.bridge
            );
            if (options.putIfAbsent(option.key(), option) != null) {
                throw new IOException("迁移包包含重复 Dataset：" + option.key());
            }
        }

        boolean changed;
        do {
            changed = options.values().removeIf(option -> option.descriptor.dependencies.stream()
                    .anyMatch(dependency -> !options.containsKey(option.pluginId + "/" + dependency)));
        } while (changed);
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

    /**
     * Authenticates and stages every selected Dataset before mutating legacy storage, then restores
     * them in dependency order. Staged plaintext remains inside the app-private cache directory and
     * is deleted on both success and failure.
     */
    public static void restore(
            Activity activity,
            InputStream source,
            char[] password,
            List<DatasetOption> selected,
            File stagingDirectory
    ) throws IOException {
        if (selected.isEmpty()) throw new IOException("没有选择要恢复的 Dataset");
        validateSelection(selected);
        if (stagingDirectory.exists() || !stagingDirectory.mkdirs()) {
            throw new IOException("无法创建 Bridge 恢复暂存目录");
        }

        Map<String, File> staged = new LinkedHashMap<>();
        for (int index = 0; index < selected.size(); index++) {
            DatasetOption option = selected.get(index);
            staged.put(option.key(), new File(stagingDirectory, "dataset-" + index + ".payload"));
        }

        try {
            BackupArchiveV2.read(source, password, (dataset, input) -> {
                File destination = staged.get(dataset.key());
                if (destination == null) return;
                try (FileOutputStream output = new FileOutputStream(destination)) {
                    copy(input, output);
                    output.getFD().sync();
                }
            });
            for (Map.Entry<String, File> entry : staged.entrySet()) {
                if (!entry.getValue().isFile()) {
                    throw new IOException("迁移包缺少已选择的 Dataset：" + entry.getKey());
                }
            }

            Set<String> restored = new LinkedHashSet<>();
            List<DatasetOption> remaining = new ArrayList<>(selected);
            while (!remaining.isEmpty()) {
                boolean progressed = false;
                for (int index = 0; index < remaining.size(); ) {
                    DatasetOption option = remaining.get(index);
                    boolean ready = option.descriptor.dependencies.stream().allMatch(
                            dependency -> restored.contains(option.pluginId + "/" + dependency)
                    );
                    if (!ready) {
                        index++;
                        continue;
                    }
                    try (FileInputStream input = new FileInputStream(staged.get(option.key()))) {
                        option.bridge.importDataset(
                                activity,
                                option.descriptor.id,
                                option.descriptor.dataFormatVersion,
                                input
                        );
                    } catch (IOException | RuntimeException error) {
                        throw new IOException(
                                "恢复 " + option.pluginTitle + " · " + option.descriptor.name + " 失败",
                                error
                        );
                    }
                    restored.add(option.key());
                    remaining.remove(index);
                    progressed = true;
                }
                if (!progressed) throw new IOException("Dataset 依赖形成循环");
            }
        } finally {
            deleteRecursively(stagingDirectory);
        }
    }

    private static void validateSelection(List<DatasetOption> selected) throws IOException {
        Set<String> keys = new HashSet<>();
        for (DatasetOption option : selected) {
            if (!keys.add(option.key())) throw new IOException("重复选择 Dataset：" + option.key());
        }
        for (DatasetOption option : selected) {
            for (String dependency : option.descriptor.dependencies) {
                if (!keys.contains(option.pluginId + "/" + dependency)) {
                    throw new IOException("Dataset 缺少依赖：" + option.pluginId + "/" + dependency);
                }
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        // Best effort: the private cache will also be removed with app data.
        file.delete();
    }

    private static final class PluginBridge {
        final String title;
        final LegacyDataBridge bridge;

        PluginBridge(String title, LegacyDataBridge bridge) {
            this.title = title;
            this.bridge = bridge;
        }
    }
}
