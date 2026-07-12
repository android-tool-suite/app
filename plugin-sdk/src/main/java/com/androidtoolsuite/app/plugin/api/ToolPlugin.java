package com.androidtoolsuite.app.plugin.api;

import android.app.Activity;
import android.view.View;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public interface ToolPlugin {
    String id();

    String title();

    String description();

    default String version() {
        return "1.0";
    }

    boolean removable();

    default Set<String> dependencies() {
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
