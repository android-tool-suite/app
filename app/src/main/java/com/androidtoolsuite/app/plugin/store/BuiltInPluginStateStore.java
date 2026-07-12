package com.androidtoolsuite.app.plugin.store;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class BuiltInPluginStateStore {
    private static final String PREFS_NAME = "built_in_plugins";
    private static final String PREF_ENABLED_IDS = "enabled_ids";

    private final SharedPreferences preferences;

    public BuiltInPluginStateStore(Context context) {
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
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
}
