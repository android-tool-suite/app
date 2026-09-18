package com.androidtoolsuite.app.migration;

import android.app.Activity;

import com.androidtoolsuite.app.plugin.runtime.HostTool;
import com.androidtoolsuite.app.migration.DatasetCategory;
import com.androidtoolsuite.app.migration.DatasetRestoreMode;
import com.androidtoolsuite.app.migration.DatasetBridge;
import com.androidtoolsuite.app.migration.DatasetDescriptor;

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

/** Coordinates Data Package export, inspection, staged restore, and plugin Dataset cleanup. */
public final class MigrationBridgeManager {
    public static final String HOST_OWNER_ID = "android_tool_suite";
    public static final String HOST_SETTINGS_ITEM_ID = "app-settings";
    public static final String HOST_PLUGIN_STATE_ITEM_ID = "plugin-enabled-state";
    public static final String HOST_PLUGIN_PACKAGE_PREFIX = "plugin-package.";

    private MigrationBridgeManager() {
    }

    public static final class DatasetOption {
        public final String pluginId;
        public final String pluginTitle;
        public final DatasetDescriptor descriptor;
        private final DatasetBridge bridge;
        public final DataPackageArchive.ItemKind kind;
        public final DataPackageArchive.Protection archiveProtection;
        public final boolean hasExistingData;

        private DatasetOption(
                String pluginId,
                String pluginTitle,
                DatasetDescriptor descriptor,
                DatasetBridge bridge,
                DataPackageArchive.ItemKind kind,
                DataPackageArchive.Protection archiveProtection,
                boolean hasExistingData
        ) {
            this.pluginId = pluginId;
            this.pluginTitle = pluginTitle;
            this.descriptor = descriptor;
            this.bridge = bridge;
            this.kind = kind;
            this.archiveProtection = archiveProtection;
            this.hasExistingData = hasExistingData;
        }

        public String key() {
            return pluginId + "/" + descriptor.id;
        }

        public boolean isHostItem() {
            return kind != DataPackageArchive.ItemKind.PLUGIN_DATA;
        }

        public boolean isPluginPackage() {
            return kind == DataPackageArchive.ItemKind.HOST_PLUGIN_PACKAGE;
        }

        public String packagedPluginId() {
            if (!isPluginPackage() || !descriptor.id.startsWith(HOST_PLUGIN_PACKAGE_PREFIX)) {
                return "";
            }
            return descriptor.id.substring(HOST_PLUGIN_PACKAGE_PREFIX.length());
        }

        /** True when the package can install this item's plugin before the bridge is loaded. */
        public boolean requiresBridgeResolution() {
            return bridge == null && !isHostItem();
        }

        public boolean supportsDelete() {
            if (bridge == null || isHostItem()) return false;
            try {
                return bridge.supportsDelete(descriptor.id);
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }

    public static final class ExportSelection {
        public final DatasetOption option;
        public final DataPackageArchive.Protection protection;

        public ExportSelection(
                DatasetOption option,
                DataPackageArchive.Protection protection
        ) {
            this.option = java.util.Objects.requireNonNull(option, "option");
            this.protection = java.util.Objects.requireNonNull(protection, "protection");
        }
    }

    public static final class ImportSelection {
        public final DatasetOption option;
        public final DatasetRestoreMode restoreMode;

        public ImportSelection(DatasetOption option, DatasetRestoreMode restoreMode) {
            this.option = java.util.Objects.requireNonNull(option, "option");
            this.restoreMode = java.util.Objects.requireNonNull(restoreMode, "restoreMode");
            if (!option.descriptor.supportsRestoreMode(restoreMode)) {
                throw new IllegalArgumentException("unsupported restore mode: " + option.key());
            }
        }
    }

    public static List<DatasetOption> discover(Activity activity, List<HostTool> plugins)
            throws IOException {
        Map<String, DatasetOption> options = new LinkedHashMap<>();
        for (HostTool plugin : plugins) {
            DatasetBridge bridge;
            List<DatasetDescriptor> datasets;
            try {
                bridge = plugin.datasetBridge();
                if (bridge == null) continue;
                datasets = bridge.datasets(activity);
            } catch (RuntimeException error) {
                throw new IOException("无法读取 " + plugin.title() + " 的迁移清单", error);
            }
            if (datasets == null) {
                throw new IOException(plugin.title() + " 返回了空迁移清单");
            }
            for (DatasetDescriptor descriptor : datasets) {
                if (descriptor == null) {
                    throw new IOException(plugin.title() + " 包含无效 Dataset");
                }
                DatasetOption option = new DatasetOption(
                        plugin.id(),
                        plugin.title(),
                        descriptor,
                        bridge,
                        DataPackageArchive.ItemKind.PLUGIN_DATA,
                        DataPackageArchive.Protection.NONE,
                        true
                );
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

    /** Matches archive records to installed Dataset adapters without requiring data to exist yet. */
    public static List<DatasetOption> matchForImport(
            Activity activity,
            List<HostTool> plugins,
            List<BackupArchiveV2.DatasetRecord> datasets
    ) throws IOException {
        Map<String, PluginBridge> bridges = new LinkedHashMap<>();
        for (HostTool plugin : plugins) {
            DatasetBridge bridge;
            try {
                bridge = plugin.datasetBridge();
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
            boolean hasExistingData;
            try {
                hasExistingData = target.bridge.hasData(activity, dataset.descriptor.id);
            } catch (IOException | RuntimeException error) {
                throw new IOException("无法检查 " + target.title + " 的现有数据", error);
            }
            DatasetOption option = new DatasetOption(
                    dataset.pluginId,
                    target.title,
                    dataset.descriptor,
                    target.bridge,
                    DataPackageArchive.ItemKind.PLUGIN_DATA,
                    DataPackageArchive.Protection.NONE,
                    hasExistingData
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

    public static DatasetOption hostSettingsExportOption(long estimatedSize) {
        return new DatasetOption(
                HOST_OWNER_ID,
                "Android Tool Suite",
                new DatasetDescriptor(
                        HOST_SETTINGS_ITEM_ID,
                        "应用设置",
                        DatasetCategory.SETTINGS,
                        estimatedSize,
                        1,
                        false,
                        java.util.Arrays.asList(DatasetRestoreMode.REPLACE, DatasetRestoreMode.MERGE)
                ),
                null,
                DataPackageArchive.ItemKind.HOST_SETTINGS,
                DataPackageArchive.Protection.NONE,
                true
        );
    }

    public static DatasetOption hostPluginStateExportOption(long estimatedSize) {
        return new DatasetOption(
                HOST_OWNER_ID,
                "Android Tool Suite",
                new DatasetDescriptor(
                        HOST_PLUGIN_STATE_ITEM_ID,
                        "插件启用状态",
                        DatasetCategory.SETTINGS,
                        estimatedSize,
                        1,
                        false,
                        java.util.Arrays.asList(DatasetRestoreMode.REPLACE, DatasetRestoreMode.MERGE)
                ),
                null,
                DataPackageArchive.ItemKind.HOST_PLUGIN_STATE,
                DataPackageArchive.Protection.NONE,
                true
        );
    }

    public static DatasetOption hostPluginPackageExportOption(
            String pluginId,
            String pluginTitle,
            long estimatedSize
    ) {
        return new DatasetOption(
                HOST_OWNER_ID,
                "Android Tool Suite",
                new DatasetDescriptor(
                        HOST_PLUGIN_PACKAGE_PREFIX + pluginId,
                        "插件包 · " + pluginTitle,
                        DatasetCategory.DATA,
                        estimatedSize,
                        1,
                        false,
                        DatasetRestoreMode.REPLACE
                ),
                null,
                DataPackageArchive.ItemKind.HOST_PLUGIN_PACKAGE,
                DataPackageArchive.Protection.NONE,
                true
        );
    }

    /** Matches a v3 package to Host items and currently installed plugin bridges. */
    public static List<DatasetOption> matchDataPackageForImport(
            Activity activity,
            List<HostTool> plugins,
            List<DataPackageArchive.ItemRecord> items,
            boolean includeDeferredPluginData,
            Set<String> installedPluginIds
    ) throws IOException {
        Map<String, PluginBridge> bridges = pluginBridges(plugins);
        Map<String, DatasetOption> options = new LinkedHashMap<>();
        Set<String> packagedPluginIds = new LinkedHashSet<>();
        for (DataPackageArchive.ItemRecord item : items) {
            if (item.kind == DataPackageArchive.ItemKind.HOST_PLUGIN_PACKAGE
                    && item.descriptor.id.startsWith(HOST_PLUGIN_PACKAGE_PREFIX)) {
                packagedPluginIds.add(item.descriptor.id.substring(HOST_PLUGIN_PACKAGE_PREFIX.length()));
            }
        }
        for (DataPackageArchive.ItemRecord item : items) {
            if (item.protection == DataPackageArchive.Protection.ACCOUNT) continue;
            if (item.kind != DataPackageArchive.ItemKind.PLUGIN_DATA) {
                DatasetOption option = matchHostItem(item, installedPluginIds);
                if (option != null && options.putIfAbsent(option.key(), option) != null) {
                    throw new IOException("数据包包含重复项目：" + option.key());
                }
                continue;
            }
            PluginBridge target = bridges.get(item.ownerId);
            if (target == null) {
                if (!includeDeferredPluginData || !packagedPluginIds.contains(item.ownerId)) continue;
                DatasetOption option = new DatasetOption(
                        item.ownerId,
                        item.ownerName,
                        item.descriptor,
                        null,
                        item.kind,
                        item.protection,
                        false
                );
                if (options.putIfAbsent(option.key(), option) != null) {
                    throw new IOException("数据包包含重复项目：" + option.key());
                }
                continue;
            }
            List<DatasetRestoreMode> modes;
            boolean hasExistingData;
            try {
                modes = supportedModes(target.bridge, item.descriptor);
                hasExistingData = target.bridge.hasData(activity, item.descriptor.id);
            } catch (IOException error) {
                throw new IOException("无法检查 " + target.title + " 的现有数据", error);
            } catch (RuntimeException error) {
                throw new IOException("无法检查 " + target.title + " 的数据兼容性", error);
            }
            if (modes.isEmpty()) continue;
            DatasetOption option = new DatasetOption(
                    item.ownerId,
                    target.title,
                    descriptorWithModes(item.descriptor, modes),
                    target.bridge,
                    item.kind,
                    item.protection,
                    hasExistingData
            );
            if (options.putIfAbsent(option.key(), option) != null) {
                throw new IOException("数据包包含重复项目：" + option.key());
            }
        }

        boolean changed;
        do {
            changed = options.values().removeIf(option -> !option.isHostItem()
                    && option.descriptor.dependencies.stream().anyMatch(
                    dependency -> !options.containsKey(option.pluginId + "/" + dependency)
            ));
        } while (changed);
        return Collections.unmodifiableList(new ArrayList<>(options.values()));
    }

    /** Rebinds selected plugin items after their package items have been installed. */
    public static List<ImportSelection> resolveDataPackageImportBridges(
            Activity activity,
            List<HostTool> plugins,
            List<ImportSelection> selected
    ) throws IOException {
        Map<String, PluginBridge> bridges = pluginBridges(plugins);
        List<ImportSelection> resolved = new ArrayList<>();
        for (ImportSelection selection : selected) {
            DatasetOption option = selection.option;
            if (option.isHostItem()) {
                resolved.add(selection);
                continue;
            }
            PluginBridge target = bridges.get(option.pluginId);
            if (target == null) {
                throw new IOException("所选插件包未安装数据所属插件：" + option.pluginTitle);
            }
            boolean supported;
            boolean hasExistingData;
            try {
                supported = target.bridge.supportsRestoreMode(
                        option.descriptor.id,
                        option.descriptor.dataFormatVersion,
                        selection.restoreMode
                );
                hasExistingData = target.bridge.hasData(activity, option.descriptor.id);
            } catch (IOException error) {
                throw new IOException("无法检查 " + target.title + " 的现有数据", error);
            } catch (RuntimeException error) {
                throw new IOException("无法检查 " + target.title + " 的数据兼容性", error);
            }
            if (!supported) {
                throw new IOException(target.title + " 不支持所选恢复方式：" + option.descriptor.name);
            }
            if (!option.hasExistingData && hasExistingData) {
                throw new IOException(
                        target.title + " · " + option.descriptor.name
                                + " 在安装插件后检测到已有数据，请重新打开数据包选择处理方式"
                );
            }
            DatasetOption rebound = new DatasetOption(
                    option.pluginId,
                    target.title,
                    descriptorWithModes(option.descriptor,
                            Collections.singletonList(selection.restoreMode)),
                    target.bridge,
                    option.kind,
                    option.archiveProtection,
                    hasExistingData
            );
            resolved.add(new ImportSelection(rebound, selection.restoreMode));
        }
        validateImportSelection(resolved);
        return Collections.unmodifiableList(resolved);
    }

    private static DatasetOption matchHostItem(
            DataPackageArchive.ItemRecord item,
            Set<String> installedPluginIds
    ) throws IOException {
        if (!HOST_OWNER_ID.equals(item.ownerId)) return null;
        List<DatasetRestoreMode> modes;
        boolean hasExistingData;
        if (item.kind == DataPackageArchive.ItemKind.HOST_SETTINGS
                && HOST_SETTINGS_ITEM_ID.equals(item.descriptor.id)) {
            modes = intersectModes(item.descriptor.restoreModes,
                    java.util.Arrays.asList(DatasetRestoreMode.REPLACE, DatasetRestoreMode.MERGE));
            hasExistingData = true;
        } else if (item.kind == DataPackageArchive.ItemKind.HOST_PLUGIN_STATE
                && HOST_PLUGIN_STATE_ITEM_ID.equals(item.descriptor.id)) {
            modes = intersectModes(item.descriptor.restoreModes,
                    java.util.Arrays.asList(DatasetRestoreMode.REPLACE, DatasetRestoreMode.MERGE));
            hasExistingData = true;
        } else if (item.kind == DataPackageArchive.ItemKind.HOST_PLUGIN_PACKAGE
                && item.descriptor.id.startsWith(HOST_PLUGIN_PACKAGE_PREFIX)) {
            String pluginId = item.descriptor.id.substring(HOST_PLUGIN_PACKAGE_PREFIX.length());
            if (pluginId.isEmpty() || !pluginId.matches("[A-Za-z0-9._-]+")) {
                throw new IOException("插件包项目 ID 无效");
            }
            modes = intersectModes(item.descriptor.restoreModes,
                    Collections.singletonList(DatasetRestoreMode.REPLACE));
            hasExistingData = installedPluginIds.contains(pluginId);
        } else {
            return null;
        }
        if (modes.isEmpty()) return null;
        return new DatasetOption(
                item.ownerId,
                item.ownerName,
                descriptorWithModes(item.descriptor, modes),
                null,
                item.kind,
                item.protection,
                hasExistingData
        );
    }

    private static List<DatasetRestoreMode> supportedModes(
            DatasetBridge bridge,
            DatasetDescriptor descriptor
    ) {
        List<DatasetRestoreMode> result = new ArrayList<>();
        for (DatasetRestoreMode mode : descriptor.restoreModes) {
            if (bridge.supportsRestoreMode(descriptor.id, descriptor.dataFormatVersion, mode)) {
                result.add(mode);
            }
        }
        return result;
    }

    private static List<DatasetRestoreMode> intersectModes(
            List<DatasetRestoreMode> first,
            List<DatasetRestoreMode> second
    ) {
        List<DatasetRestoreMode> result = new ArrayList<>();
        for (DatasetRestoreMode mode : first) if (second.contains(mode)) result.add(mode);
        return result;
    }

    private static DatasetDescriptor descriptorWithModes(
            DatasetDescriptor descriptor,
            List<DatasetRestoreMode> modes
    ) {
        return new DatasetDescriptor(
                descriptor.id,
                descriptor.name,
                descriptor.category,
                descriptor.estimatedSize,
                descriptor.dataFormatVersion,
                descriptor.sensitive,
                modes,
                descriptor.dependencies
        );
    }

    private static Map<String, PluginBridge> pluginBridges(List<HostTool> plugins)
            throws IOException {
        Map<String, PluginBridge> bridges = new LinkedHashMap<>();
        for (HostTool plugin : plugins) {
            DatasetBridge bridge;
            try {
                bridge = plugin.datasetBridge();
            } catch (RuntimeException error) {
                throw new IOException("无法读取 " + plugin.title() + " 的数据接口", error);
            }
            if (bridge != null) {
                bridges.putIfAbsent(plugin.id(), new PluginBridge(plugin.title(), bridge));
            }
        }
        return bridges;
    }

    public static void writeDataPackage(
            Activity activity,
            OutputStream target,
            String sourcePackage,
            String sourceVersionName,
            int sourceVersionCode,
            List<ExportSelection> selected,
            HostItemWriter hostItemWriter,
            char[] password
    ) throws IOException {
        List<DataPackageArchive.ItemSource> sources = new ArrayList<>();
        for (ExportSelection selection : selected) {
            DatasetOption option = selection.option;
            if (option.isHostItem()) {
                if (hostItemWriter == null) throw new IOException("缺少应用数据导出器");
                sources.add(new DataPackageArchive.ItemSource(
                        option.pluginId,
                        option.pluginTitle,
                        option.kind,
                        option.descriptor,
                        selection.protection,
                        output -> hostItemWriter.write(option, output)
                ));
            } else {
                sources.add(new DataPackageArchive.ItemSource(
                        option.pluginId,
                        option.pluginTitle,
                        option.kind,
                        option.descriptor,
                        selection.protection,
                        output -> option.bridge.exportDataset(
                                activity,
                                option.descriptor.id,
                                output
                        )
                ));
            }
        }
        DataPackageArchive.write(target, new DataPackageArchive.WriteRequest(
                sourcePackage,
                sourceVersionName,
                sourceVersionCode,
                sources,
                password
        ));
    }

    @FunctionalInterface
    public interface HostItemWriter {
        void write(DatasetOption option, OutputStream output) throws IOException;
    }

    @FunctionalInterface
    public interface HostItemsRestorer {
        void restore(List<ImportSelection> selected, Map<String, File> staged) throws IOException;
    }

    @FunctionalInterface
    public interface PostHostDatasetResolver {
        List<ImportSelection> resolve(List<ImportSelection> selected) throws IOException;
    }

    /** Stages and verifies selected v3 items before applying Host state and plugin data. */
    public static void restoreDataPackage(
            Activity activity,
            InputStream source,
            char[] password,
            List<ImportSelection> selected,
            File stagingDirectory,
            HostItemsRestorer hostRestorer,
            PostHostDatasetResolver postHostResolver
    ) throws IOException {
        if (selected.isEmpty()) throw new IOException("没有选择要恢复的数据项目");
        validateImportSelection(selected);
        if (stagingDirectory.exists() || !stagingDirectory.mkdirs()) {
            throw new IOException("无法创建数据恢复暂存目录");
        }
        Map<String, File> staged = stagedFiles(selected, stagingDirectory);
        Set<String> keys = new LinkedHashSet<>(staged.keySet());
        try {
            DataPackageArchive.read(source, password, keys, (item, input) -> {
                File destination = staged.get(item.key());
                if (destination == null) return;
                try (FileOutputStream output = new FileOutputStream(destination)) {
                    copy(input, output);
                    output.getFD().sync();
                }
            });
            requireStaged(staged);

            List<ImportSelection> hostSelections = new ArrayList<>();
            for (ImportSelection selection : selected) {
                if (selection.option.isHostItem()) hostSelections.add(selection);
            }
            if (!hostSelections.isEmpty()) {
                try {
                    hostRestorer.restore(Collections.unmodifiableList(hostSelections), staged);
                } catch (IOException | RuntimeException error) {
                    throw new IOException("恢复 Android Tool Suite 应用数据失败", error);
                }
            }
            List<ImportSelection> restoreOptions = selected;
            if (!hostSelections.isEmpty() && postHostResolver != null) {
                restoreOptions = postHostResolver.resolve(selected);
                requireSameSelection(selected, restoreOptions);
            }
            requireResolvedBridges(restoreOptions);
            restoreStagedDatasets(activity, restoreOptions, staged);
        } finally {
            deleteRecursively(stagingDirectory);
        }
    }

    /** Permanently deletes selected Datasets in reverse dependency order. */
    public static void delete(
            Activity activity,
            List<DatasetOption> available,
            List<DatasetOption> selected
    ) throws IOException {
        if (selected.isEmpty()) throw new IOException("没有选择要删除的数据项目");
        Set<String> selectedKeys = new LinkedHashSet<>();
        for (DatasetOption option : selected) {
            if (option.isHostItem() || !option.supportsDelete()) {
                throw new IOException("数据项目不支持删除：" + option.key());
            }
            if (!selectedKeys.add(option.key())) {
                throw new IOException("重复选择数据项目：" + option.key());
            }
        }
        for (DatasetOption candidate : available) {
            if (selectedKeys.contains(candidate.key())) continue;
            for (String dependency : candidate.descriptor.dependencies) {
                if (selectedKeys.contains(candidate.pluginId + "/" + dependency)) {
                    throw new IOException("删除 " + dependency + " 时必须同时删除 "
                            + candidate.descriptor.name);
                }
            }
        }

        List<DatasetOption> remaining = new ArrayList<>(selected);
        while (!remaining.isEmpty()) {
            boolean progressed = false;
            for (int index = 0; index < remaining.size(); ) {
                DatasetOption option = remaining.get(index);
                boolean hasDependent = remaining.stream().anyMatch(candidate ->
                        candidate != option
                                && candidate.pluginId.equals(option.pluginId)
                                && candidate.descriptor.dependencies.contains(option.descriptor.id)
                );
                if (hasDependent) {
                    index++;
                    continue;
                }
                try {
                    option.bridge.deleteDataset(activity, option.descriptor.id);
                } catch (IOException | RuntimeException error) {
                    throw new IOException(
                            "删除 " + option.pluginTitle + " · " + option.descriptor.name + " 失败",
                            error
                    );
                }
                remaining.remove(index);
                progressed = true;
            }
            if (!progressed) throw new IOException("数据项目依赖形成循环");
        }
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
     * Authenticates and stages every selected Dataset before mutating runtime storage, then restores
     * them in dependency order. Staged plaintext remains inside the app-private cache directory and
     * is deleted on both success and failure.
     */
    public static void restore(
            Activity activity,
            InputStream source,
            char[] password,
            List<ImportSelection> selected,
            File stagingDirectory
    ) throws IOException {
        if (selected.isEmpty()) throw new IOException("没有选择要恢复的 Dataset");
        validateImportSelection(selected);
        if (stagingDirectory.exists() || !stagingDirectory.mkdirs()) {
            throw new IOException("无法创建 Bridge 恢复暂存目录");
        }

        Map<String, File> staged = new LinkedHashMap<>();
        for (int index = 0; index < selected.size(); index++) {
            DatasetOption option = selected.get(index).option;
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
            List<ImportSelection> remaining = new ArrayList<>(selected);
            while (!remaining.isEmpty()) {
                boolean progressed = false;
                for (int index = 0; index < remaining.size(); ) {
                    ImportSelection selection = remaining.get(index);
                    DatasetOption option = selection.option;
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
                                selection.restoreMode,
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

    private static void validateImportSelection(List<ImportSelection> selected) throws IOException {
        List<DatasetOption> options = new ArrayList<>();
        for (ImportSelection selection : selected) {
            if (!selection.option.descriptor.supportsRestoreMode(selection.restoreMode)) {
                throw new IOException("数据项目不支持所选恢复方式：" + selection.option.key());
            }
            options.add(selection.option);
        }
        validateSelection(options);
    }

    private static Map<String, File> stagedFiles(
            List<ImportSelection> selected,
            File stagingDirectory
    ) {
        Map<String, File> staged = new LinkedHashMap<>();
        for (int index = 0; index < selected.size(); index++) {
            DatasetOption option = selected.get(index).option;
            staged.put(option.key(), new File(stagingDirectory, "item-" + index + ".payload"));
        }
        return staged;
    }

    private static void requireStaged(Map<String, File> staged) throws IOException {
        for (Map.Entry<String, File> entry : staged.entrySet()) {
            if (!entry.getValue().isFile()) {
                throw new IOException("数据包缺少已选择的项目：" + entry.getKey());
            }
        }
    }

    private static void requireSameSelection(
            List<ImportSelection> expected,
            List<ImportSelection> resolved
    ) throws IOException {
        if (resolved == null) throw new IOException("插件数据接口刷新返回空结果");
        Set<String> expectedKeys = new LinkedHashSet<>();
        Set<String> resolvedKeys = new LinkedHashSet<>();
        for (ImportSelection selection : expected) {
            expectedKeys.add(selection.option.key() + ":" + selection.restoreMode.name());
        }
        for (ImportSelection selection : resolved) {
            resolvedKeys.add(selection.option.key() + ":" + selection.restoreMode.name());
        }
        if (expectedKeys.size() != expected.size()
                || resolvedKeys.size() != resolved.size()
                || !expectedKeys.equals(resolvedKeys)) {
            throw new IOException("插件数据接口刷新改变了用户选择范围");
        }
    }

    private static void requireResolvedBridges(List<ImportSelection> options) throws IOException {
        for (ImportSelection selection : options) {
            DatasetOption option = selection.option;
            if (option.requiresBridgeResolution()) {
                throw new IOException("数据所属插件尚未安装或未提供恢复接口：" + option.pluginTitle);
            }
        }
    }

    private static void restoreStagedDatasets(
            Activity activity,
            List<ImportSelection> selected,
            Map<String, File> staged
    ) throws IOException {
        Set<String> restored = new LinkedHashSet<>();
        List<ImportSelection> remaining = new ArrayList<>();
        for (ImportSelection selection : selected) {
            DatasetOption option = selection.option;
            if (option.isHostItem()) restored.add(option.key());
            else remaining.add(selection);
        }
        while (!remaining.isEmpty()) {
            boolean progressed = false;
            for (int index = 0; index < remaining.size(); ) {
                ImportSelection selection = remaining.get(index);
                DatasetOption option = selection.option;
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
                            selection.restoreMode,
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
            if (!progressed) throw new IOException("数据项目依赖形成循环");
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
        final DatasetBridge bridge;

        PluginBridge(String title, DatasetBridge bridge) {
            this.title = title;
            this.bridge = bridge;
        }
    }
}
