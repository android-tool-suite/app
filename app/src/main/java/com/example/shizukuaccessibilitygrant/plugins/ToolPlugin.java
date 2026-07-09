package com.example.shizukuaccessibilitygrant.plugins;

import android.app.Activity;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public interface ToolPlugin {
    String id();

    String title();

    String description();

    boolean removable();

    default Set<String> requestedPermissions() {
        return Collections.emptySet();
    }

    default List<HomeWidget> createHomeWidgets(Activity activity, PluginHost host) {
        return Collections.emptyList();
    }

    View createView(Activity activity, PluginHost host);

    void onSelected();

    void onHostStateChanged();

    void onDestroy();
}
