package com.example.shizukuaccessibilitygrant.plugin.store;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class BuiltInPluginStateStore {
    private static final String PREFS_NAME = "built_in_plugins";
    private static final String PREF_DISABLED_IDS = "disabled_ids";

    private final SharedPreferences preferences;

    public BuiltInPluginStateStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled(String pluginId) {
        return !disabledIds().contains(pluginId);
    }

    public void setEnabled(String pluginId, boolean enabled) {
        LinkedHashSet<String> ids = new LinkedHashSet<>(disabledIds());
        if (enabled) {
            ids.remove(pluginId);
        } else {
            ids.add(pluginId);
        }
        preferences.edit().putStringSet(PREF_DISABLED_IDS, ids).apply();
    }

    public Set<String> disabledIds() {
        return new LinkedHashSet<>(preferences.getStringSet(PREF_DISABLED_IDS, Collections.emptySet()));
    }
}
