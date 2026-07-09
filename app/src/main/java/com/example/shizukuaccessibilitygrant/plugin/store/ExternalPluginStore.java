package com.example.shizukuaccessibilitygrant.plugin.store;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;

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

    private final Context context;
    private final SharedPreferences preferences;

    public ExternalPluginStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<ImportedPluginDescriptor> load() {
        Set<String> rawSet = preferences.getStringSet(PREF_PLUGIN_JSON_SET, Collections.emptySet());
        List<ImportedPluginDescriptor> plugins = new ArrayList<>();
        for (String raw : rawSet) {
            try {
                ImportedPluginDescriptor descriptor = ImportedPluginDescriptor.fromJson(raw);
                File codeFile = pluginCodeFile(descriptor.id);
                if (codeFile.exists()) {
                    descriptor = descriptor.withCodePath(codeFile.getAbsolutePath());
                }
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
                .apply();
        deleteRecursively(pluginDir(pluginId));
    }

    public void savePluginCode(String pluginId, byte[] bytes) throws IOException {
        File codeFile = pluginCodeFile(pluginId);
        File parent = codeFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建插件目录");
        }
        try (FileOutputStream output = new FileOutputStream(codeFile)) {
            output.write(bytes);
        }
        codeFile.setReadOnly();
    }

    public void setPermission(String pluginId, String permission, boolean granted) throws JSONException {
        List<ImportedPluginDescriptor> current = load();
        LinkedHashSet<String> rawSet = new LinkedHashSet<>();
        for (ImportedPluginDescriptor plugin : current) {
            ImportedPluginDescriptor next = plugin;
            if (plugin.id.equals(pluginId)) {
                LinkedHashSet<String> permissions = new LinkedHashSet<>(plugin.grantedPermissions);
                if (granted) {
                    permissions.add(permission);
                } else {
                    permissions.remove(permission);
                }
                next = plugin.withGrantedPermissions(permissions);
            }
            rawSet.add(next.toJson());
        }
        preferences.edit().putStringSet(PREF_PLUGIN_JSON_SET, rawSet).apply();
    }

    public boolean hasPermission(String pluginId, String permission) {
        for (ImportedPluginDescriptor plugin : load()) {
            if (plugin.id.equals(pluginId)) {
                return plugin.grantedPermissions.contains(permission);
            }
        }
        return false;
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
