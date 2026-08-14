package com.androidtoolsuite.app.plugin.api;

import android.app.Activity;
import android.view.View;

import com.androidtoolsuite.app.plugin.migration.LegacyDataBridge;

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

    /**
     * Temporary read-only adapter used by the Debug Migration Bridge.
     *
     * <p>Returning {@code null} keeps existing plugins binary compatible and opts out of
     * dataset export.</p>
     */
    default LegacyDataBridge legacyDataBridge() {
        return null;
    }

    View createView(Activity activity, PluginHost host);

    void onSelected();

    void onHostStateChanged();

    void onDestroy();
}
