package com.example.shizukuaccessibilitygrant.plugin.store;

import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import com.example.shizukuaccessibilitygrant.plugins.accessibility.AccessibilityGrantPluginDescriptor;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ExternalPluginStore {
    private static final String PREFS_NAME = "external_plugins";
    private static final String PREF_PLUGIN_JSON_SET = "plugin_json_set";
    private static final String PREF_SEEDED_ACCESSIBILITY = "seeded_accessibility_grant";

    private final SharedPreferences preferences;

    public ExternalPluginStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<ImportedPluginDescriptor> load() {
        Set<String> rawSet = preferences.getStringSet(PREF_PLUGIN_JSON_SET, Collections.emptySet());
        List<ImportedPluginDescriptor> plugins = new ArrayList<>();
        for (String raw : rawSet) {
            try {
                plugins.add(ImportedPluginDescriptor.fromJson(raw));
            } catch (JSONException ignored) {
            }
        }
        Collections.sort(plugins, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return plugins;
    }

    public void seedAccessibilityGrantPluginIfNeeded() throws JSONException {
        if (preferences.getBoolean(PREF_SEEDED_ACCESSIBILITY, false)) {
            return;
        }
        for (ImportedPluginDescriptor plugin : load()) {
            if (AccessibilityGrantPluginDescriptor.ID.equals(plugin.id)) {
                preferences.edit().putBoolean(PREF_SEEDED_ACCESSIBILITY, true).apply();
                return;
            }
        }
        save(AccessibilityGrantPluginDescriptor.create());
        preferences.edit().putBoolean(PREF_SEEDED_ACCESSIBILITY, true).apply();
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
        preferences.edit().putStringSet(PREF_PLUGIN_JSON_SET, rawSet).apply();
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
}
