package com.androidtoolsuite.app.plugin.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.androidtoolsuite.app.plugin.model.ImportedPluginDescriptor;

import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ExternalPluginStore {
    private static final String PREFS_NAME = "external_plugins";
    private static final String PREF_PLUGIN_JSON_SET = "plugin_json_set";
    private static final String PREF_ENABLED_IDS = "enabled_ids";
    private static final String PREF_PENDING_INSTALL_IDS = "pending_install_ids";
    private static final String PREF_SOURCE_PREFIX = "source_";
    private static final String PREF_SHA256_PREFIX = "sha256_";
    private static final String PREF_VERIFIED_PREFIX = "verified_";
    private static final String PREF_ROLLBACK_JSON_PREFIX = "rollback_json_";
    private static final String PREF_ROLLBACK_SOURCE_PREFIX = "rollback_source_";
    private static final String PREF_ROLLBACK_SHA256_PREFIX = "rollback_sha256_";
    private static final String PREF_ROLLBACK_VERIFIED_PREFIX = "rollback_verified_";
    private static final String MISSING_VALUE = "__ats_missing__";

    private final Context context;
    private final SharedPreferences preferences;

    public ExternalPluginStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        recoverInterruptedInstalls();
    }

    public List<ImportedPluginDescriptor> load() {
        Set<String> rawSet = preferences.getStringSet(PREF_PLUGIN_JSON_SET, Collections.emptySet());
        List<ImportedPluginDescriptor> plugins = new ArrayList<>();
        for (String raw : rawSet) {
            try {
                ImportedPluginDescriptor descriptor = ImportedPluginDescriptor.fromJson(raw);
                File codeFile = pluginCodeFile(descriptor.id);
                if (descriptor.entryClass.isEmpty() || !codeFile.isFile()) {
                    continue;
                }
                descriptor = descriptor.withCodePath(codeFile.getAbsolutePath());
                plugins.add(descriptor);
            } catch (JSONException ignored) {
            }
        }
        Collections.sort(plugins, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return plugins;
    }

    public void save(ImportedPluginDescriptor descriptor) throws JSONException {
        List<ImportedPluginDescriptor> current = load();
        LinkedHashSet<String> rawSet = new LinkedHashSet<>();
        boolean replaced = false;
        for (ImportedPluginDescriptor plugin : current) {
            ImportedPluginDescriptor next = plugin.id.equals(descriptor.id) ? descriptor : plugin;
            if (plugin.id.equals(descriptor.id)) {
                replaced = true;
            }
            rawSet.add(next.toJson());
        }
        if (!replaced) {
            rawSet.add(descriptor.toJson());
        }
        preferences.edit().putStringSet(PREF_PLUGIN_JSON_SET, rawSet).apply();
    }

    public void delete(String pluginId) throws JSONException {
        List<ImportedPluginDescriptor> current = load();
        LinkedHashSet<String> rawSet = new LinkedHashSet<>();
        for (ImportedPluginDescriptor plugin : current) {
            if (!plugin.id.equals(pluginId)) {
                rawSet.add(plugin.toJson());
            }
        }
        LinkedHashSet<String> enabledIds = new LinkedHashSet<>(enabledIds());
        enabledIds.remove(pluginId);
        preferences.edit()
                .putStringSet(PREF_PLUGIN_JSON_SET, rawSet)
                .putStringSet(PREF_ENABLED_IDS, enabledIds)
                .remove(PREF_SOURCE_PREFIX + pluginId)
                .remove(PREF_SHA256_PREFIX + pluginId)
                .remove(PREF_VERIFIED_PREFIX + pluginId)
                .commit();
        deleteRecursively(pluginDir(pluginId));
    }

    public void installPlugin(
            ImportedPluginDescriptor descriptor,
            byte[] bytes,
            String sourceReleaseUrl,
            String sha256,
            boolean verified
    ) throws IOException, JSONException {
        String pluginId = descriptor.id;
        File codeFile = pluginCodeFile(pluginId);
        File parent = codeFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建插件目录");
        }

        File pendingFile = new File(parent, "plugin.apk.pending");
        File backupFile = new File(parent, "plugin.apk.backup");
        if (pendingFile.exists() && !pendingFile.delete()) {
            throw new IOException("无法清理插件更新临时文件");
        }
        if (backupFile.exists() && !backupFile.delete()) {
            throw new IOException("无法清理插件更新备份");
        }
        try (FileOutputStream output = new FileOutputStream(pendingFile)) {
            output.write(bytes);
            output.getFD().sync();
        } catch (IOException error) {
            pendingFile.delete();
            throw error;
        }

        String oldJson = findRawDescriptor(pluginId);
        String oldSource = preferences.getString(PREF_SOURCE_PREFIX + pluginId, MISSING_VALUE);
        String oldSha256 = preferences.getString(PREF_SHA256_PREFIX + pluginId, MISSING_VALUE);
        boolean oldVerified = preferences.getBoolean(PREF_VERIFIED_PREFIX + pluginId, false);
        if (codeFile.exists() && !codeFile.renameTo(backupFile)) {
            pendingFile.delete();
            throw new IOException("无法备份现有插件代码");
        }
        if (!pendingFile.renameTo(codeFile)) {
            if (backupFile.exists()) {
                backupFile.renameTo(codeFile);
            }
            pendingFile.delete();
            throw new IOException("无法完成插件代码更新");
        }
        codeFile.setReadOnly();

        LinkedHashSet<String> pendingIds = new LinkedHashSet<>(
                preferences.getStringSet(PREF_PENDING_INSTALL_IDS, Collections.emptySet())
        );
        pendingIds.add(pluginId);
        SharedPreferences.Editor editor = preferences.edit()
                .putStringSet(PREF_PLUGIN_JSON_SET, replaceRawDescriptor(pluginId, descriptor.toJson()))
                .putStringSet(PREF_PENDING_INSTALL_IDS, pendingIds)
                .putString(PREF_ROLLBACK_JSON_PREFIX + pluginId, oldJson == null ? MISSING_VALUE : oldJson)
                .putString(PREF_ROLLBACK_SOURCE_PREFIX + pluginId, oldSource)
                .putString(PREF_ROLLBACK_SHA256_PREFIX + pluginId, oldSha256)
                .putBoolean(PREF_ROLLBACK_VERIFIED_PREFIX + pluginId, oldVerified)
                .putBoolean(PREF_VERIFIED_PREFIX + pluginId, verified);
        putOrRemove(editor, PREF_SOURCE_PREFIX + pluginId, sourceReleaseUrl);
        putOrRemove(editor, PREF_SHA256_PREFIX + pluginId, sha256);
        if (!editor.commit()) {
            codeFile.delete();
            if (backupFile.exists()) {
                backupFile.renameTo(codeFile);
            }
            throw new IOException("无法提交插件更新记录");
        }
    }

    public void confirmInstall(String pluginId) {
        File backupFile = new File(pluginDir(pluginId), "plugin.apk.backup");
        backupFile.delete();
        clearRollbackState(pluginId);
    }

    public void rollbackInstall(String pluginId) {
        File codeFile = pluginCodeFile(pluginId);
        File backupFile = new File(pluginDir(pluginId), "plugin.apk.backup");
        codeFile.setWritable(true);
        codeFile.delete();
        if (backupFile.exists()) {
            backupFile.renameTo(codeFile);
            codeFile.setReadOnly();
        }

        String oldJson = preferences.getString(PREF_ROLLBACK_JSON_PREFIX + pluginId, MISSING_VALUE);
        SharedPreferences.Editor editor = preferences.edit()
                .putStringSet(
                        PREF_PLUGIN_JSON_SET,
                        replaceRawDescriptor(
                                pluginId,
                                MISSING_VALUE.equals(oldJson) ? null : oldJson
                        )
                );
        restoreString(
                editor,
                PREF_SOURCE_PREFIX + pluginId,
                preferences.getString(PREF_ROLLBACK_SOURCE_PREFIX + pluginId, MISSING_VALUE)
        );
        restoreString(
                editor,
                PREF_SHA256_PREFIX + pluginId,
                preferences.getString(PREF_ROLLBACK_SHA256_PREFIX + pluginId, MISSING_VALUE)
        );
        editor.putBoolean(
                PREF_VERIFIED_PREFIX + pluginId,
                preferences.getBoolean(PREF_ROLLBACK_VERIFIED_PREFIX + pluginId, false)
        );
        editor.commit();
        clearRollbackState(pluginId);
    }

    public boolean isRepositoryVerified(String pluginId) {
        return preferences.getBoolean(PREF_VERIFIED_PREFIX + pluginId, false);
    }

    public String sourceReleaseUrl(String pluginId) {
        return preferences.getString(PREF_SOURCE_PREFIX + pluginId, "");
    }

    public String verifiedSha256(String pluginId) {
        return preferences.getString(PREF_SHA256_PREFIX + pluginId, "");
    }

    public void savePluginCode(String pluginId, byte[] bytes) throws IOException {
        File codeFile = pluginCodeFile(pluginId);
        File parent = codeFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建插件目录");
        }
        File pendingFile = new File(parent, "plugin.apk.pending");
        if (pendingFile.exists() && !pendingFile.delete()) {
            throw new IOException("无法清理插件更新临时文件");
        }
        try (FileOutputStream output = new FileOutputStream(pendingFile)) {
            output.write(bytes);
            output.getFD().sync();
        } catch (IOException error) {
            pendingFile.delete();
            throw error;
        }
        if (codeFile.exists()) {
            codeFile.setWritable(true);
            if (!codeFile.delete()) {
                pendingFile.delete();
                throw new IOException("无法替换现有插件代码");
            }
        }
        if (!pendingFile.renameTo(codeFile)) {
            pendingFile.delete();
            throw new IOException("无法完成插件代码更新");
        }
        codeFile.setReadOnly();
    }

    public boolean isEnabled(String pluginId) {
        return enabledIds().contains(pluginId);
    }

    public void setEnabled(String pluginId, boolean enabled) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(enabledIds());
        if (enabled) {
            ids.add(pluginId);
        } else {
            ids.remove(pluginId);
        }
        preferences.edit().putStringSet(PREF_ENABLED_IDS, ids).apply();
    }

    public Set<String> enabledIds() {
        return new LinkedHashSet<>(preferences.getStringSet(PREF_ENABLED_IDS, Collections.emptySet()));
    }

    private File pluginDir(String pluginId) {
        return new File(context.getFilesDir(), "plugins/" + pluginId);
    }

    private File pluginCodeFile(String pluginId) {
        return new File(pluginDir(pluginId), "plugin.apk");
    }

    private void recoverInterruptedInstalls() {
        Set<String> pendingIds = new LinkedHashSet<>(
                preferences.getStringSet(PREF_PENDING_INSTALL_IDS, Collections.emptySet())
        );
        for (String pluginId : pendingIds) {
            rollbackInstall(pluginId);
        }
    }

    private String findRawDescriptor(String pluginId) {
        Set<String> rawSet = preferences.getStringSet(PREF_PLUGIN_JSON_SET, Collections.emptySet());
        for (String raw : rawSet) {
            try {
                if (ImportedPluginDescriptor.fromJson(raw).id.equals(pluginId)) {
                    return raw;
                }
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    private LinkedHashSet<String> replaceRawDescriptor(String pluginId, String replacement) {
        Set<String> rawSet = preferences.getStringSet(PREF_PLUGIN_JSON_SET, Collections.emptySet());
        LinkedHashSet<String> updated = new LinkedHashSet<>();
        boolean replaced = false;
        for (String raw : rawSet) {
            try {
                if (ImportedPluginDescriptor.fromJson(raw).id.equals(pluginId)) {
                    if (!replaced && replacement != null) {
                        updated.add(replacement);
                    }
                    replaced = true;
                } else {
                    updated.add(raw);
                }
            } catch (JSONException ignored) {
                updated.add(raw);
            }
        }
        if (!replaced && replacement != null) {
            updated.add(replacement);
        }
        return updated;
    }

    private void clearRollbackState(String pluginId) {
        LinkedHashSet<String> pendingIds = new LinkedHashSet<>(
                preferences.getStringSet(PREF_PENDING_INSTALL_IDS, Collections.emptySet())
        );
        pendingIds.remove(pluginId);
        preferences.edit()
                .putStringSet(PREF_PENDING_INSTALL_IDS, pendingIds)
                .remove(PREF_ROLLBACK_JSON_PREFIX + pluginId)
                .remove(PREF_ROLLBACK_SOURCE_PREFIX + pluginId)
                .remove(PREF_ROLLBACK_SHA256_PREFIX + pluginId)
                .remove(PREF_ROLLBACK_VERIFIED_PREFIX + pluginId)
                .commit();
    }

    private static void putOrRemove(SharedPreferences.Editor editor, String key, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, clean);
        }
    }

    private static void restoreString(SharedPreferences.Editor editor, String key, String value) {
        if (value == null || MISSING_VALUE.equals(value)) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }

    private void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
