package com.androidtoolsuite.app.debug;

import android.content.Context;
import com.androidtoolsuite.app.migration.BackupPackageProbe;
import com.androidtoolsuite.app.migration.BackupArchiveV2;
import com.androidtoolsuite.app.migration.DataPackageArchive;
import com.androidtoolsuite.app.migration.DatasetRestoreMode;
import com.androidtoolsuite.app.migration.MigrationBridgeManager;
import com.androidtoolsuite.app.plugin.runtime.HostTool;
import com.androidtoolsuite.app.plugin.runtime.MigrationToolPlugin;
import com.androidtoolsuite.app.plugin.runtime.PluginRuntime;
import com.androidtoolsuite.app.plugin.runtime.PluginPackageStore;
import com.androidtoolsuite.runtime.contract.RuntimePluginManifest;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Metadata-only diagnostics and staged Dataset recovery; never returns Dataset contents. */
final class DebugDataCommands {
    static JSONObject verify(Context context, String pluginId) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(context);
        PluginPackageStore.InstalledPlugin plugin = runtime.packages().find(pluginId);
        if (plugin == null) throw new IOException("插件不存在：" + pluginId);
        JSONArray results = new JSONArray();
        String session = "debug-verify-" + UUID.randomUUID();
        for (RuntimePluginManifest.Dataset dataset : plugin.manifest.datasets) {
            // Sensitive data is deliberately excluded from content diagnostics.
            if (dataset.sensitive || !runtime.datasets().hasDataset(pluginId, dataset.id)) continue;
            java.security.MessageDigest expected = java.security.MessageDigest.getInstance("SHA-256");
            runtime.datasets().exportDataset(pluginId, dataset.id, new java.security.DigestOutputStream(
                    new java.io.OutputStream() { @Override public void write(int value) { }
                        @Override public void write(byte[] bytes, int offset, int count) { } }, expected));
            java.security.MessageDigest actual = java.security.MessageDigest.getInstance("SHA-256");
            JSONObject opened = runtime.datasets().openRead(pluginId, session, dataset.id);
            String handle = opened.getString("handle");
            long offset = 0;
            int chunks = 0;
            try {
                while (true) {
                    JSONObject part = runtime.datasets().read(pluginId, session, handle, offset, 128 * 1024);
                    byte[] bytes = android.util.Base64.decode(part.getString("bytes"), android.util.Base64.NO_WRAP);
                    actual.update(bytes);
                    offset += bytes.length;
                    chunks++;
                    if (part.getBoolean("eof")) break;
                    if (bytes.length == 0) throw new IOException("Dataset 读取未前进");
                }
            } finally {
                runtime.datasets().abort(pluginId, session, handle);
            }
            if (!java.security.MessageDigest.isEqual(expected.digest(), actual.digest())) {
                throw new IOException("Dataset 分块内容与导出内容不一致：" + dataset.id);
            }
            results.put(new JSONObject().put("key", pluginId + "/" + dataset.id)
                    .put("bytes", offset).put("chunks", chunks).put("matchesExport", true));
        }
        return new JSONObject().put("verified", results).put("sensitiveDatasetsSkipped", true);
    }

    static JSONObject datasets(Context context, String pluginId) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(context);
        JSONArray result = new JSONArray();
        boolean found = pluginId.isEmpty();
        for (PluginPackageStore.InstalledPlugin plugin : runtime.packages().load()) {
            String id = plugin.manifest.plugin.id;
            if (!pluginId.isEmpty() && !pluginId.equals(id)) continue;
            found = true;
            for (RuntimePluginManifest.Dataset dataset : plugin.manifest.datasets) {
                boolean exists = runtime.datasets().hasDataset(id, dataset.id);
                result.put(new JSONObject().put("key", id + "/" + dataset.id)
                        .put("present", exists).put("sensitive", dataset.sensitive)
                        .put("bytes", exists ? runtime.datasets().datasetSize(id, dataset.id) : 0));
            }
        }
        if (!found) throw new IOException("插件不存在：" + pluginId);
        return new JSONObject().put("datasets", result);
    }

    static JSONObject backup(Context context, File source, boolean restore,
                             Set<String> keys, char[] password) throws Exception {
        PluginRuntime runtime = PluginRuntime.get(context);
        List<HostTool> plugins = new ArrayList<>();
        Set<String> installed = new LinkedHashSet<>();
        for (PluginPackageStore.InstalledPlugin plugin : runtime.packages().load()) {
            installed.add(plugin.manifest.plugin.id);
            plugins.add(new MigrationToolPlugin(plugin, runtime.datasets()));
        }
        BackupPackageProbe.Format format;
        try (FileInputStream input = new FileInputStream(source)) {
            format = BackupPackageProbe.detect(input);
        }
        List<MigrationBridgeManager.DatasetOption> options;
        JSONArray items = new JSONArray();
        if (format == BackupPackageProbe.Format.DATA_PACKAGE_V3) {
            DataPackageArchive.ReadResult archive;
            try (FileInputStream input = new FileInputStream(source)) {
                archive = DataPackageArchive.inspect(input);
            }
            options = MigrationBridgeManager.matchDataPackageForImport(null, plugins, archive.items, false, installed);
            for (DataPackageArchive.ItemRecord item : archive.items) {
                items.put(new JSONObject().put("key", item.key()).put("kind", item.kind.name())
                        .put("protection", item.protection.name()));
            }
        } else if (format == BackupPackageProbe.Format.BRIDGE_V2) {
            BackupArchiveV2.ReadResult archive;
            try (FileInputStream input = new FileInputStream(source)) {
                archive = BackupArchiveV2.inspect(input);
            }
            options = MigrationBridgeManager.matchForImport(null, plugins, archive.datasets);
            for (BackupArchiveV2.DatasetRecord item : archive.datasets) {
                items.put(new JSONObject().put("key", item.key()).put("kind", "PLUGIN_DATA")
                        .put("protection", archive.encrypted ? "PASSWORD" : "NONE"));
            }
        } else {
            throw new IOException("此接口仅支持 v2/v3 Dataset 归档，旧整机迁移请使用应用界面");
        }
        Set<String> supported = new LinkedHashSet<>();
        List<MigrationBridgeManager.ImportSelection> selected = new ArrayList<>();
        for (MigrationBridgeManager.DatasetOption option : options) {
            if (option.isHostItem() || !option.descriptor.supportsRestoreMode(DatasetRestoreMode.REPLACE)) continue;
            supported.add(option.key());
            if (keys.contains(option.key())) selected.add(new MigrationBridgeManager.ImportSelection(option, DatasetRestoreMode.REPLACE));
        }
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = items.getJSONObject(index);
            item.put("restorable", supported.contains(item.getString("key")));
        }
        JSONObject result = new JSONObject().put("format", format.name()).put("items", items);
        if (!restore) return result;
        if (keys.isEmpty()) throw new IOException("必须显式选择要恢复的 Dataset keys");
        if (!supported.containsAll(keys)) throw new IOException("所选项目不存在、不兼容或不是可恢复的插件 Dataset");
        File staging = new File(context.getCacheDir(), "debug-restore-" + UUID.randomUUID());
        try (FileInputStream input = new FileInputStream(source)) {
            if (format == BackupPackageProbe.Format.DATA_PACKAGE_V3) {
                MigrationBridgeManager.restoreDataPackage(null, input, password, selected, staging,
                        (host, files) -> { throw new IOException("此接口不恢复宿主项目"); }, null);
            } else {
                MigrationBridgeManager.restore(null, input, password, selected, staging);
            }
        }
        return result.put("restored", new JSONArray(keys));
    }
}
