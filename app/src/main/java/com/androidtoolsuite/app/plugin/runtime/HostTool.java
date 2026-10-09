package com.androidtoolsuite.app.plugin.runtime;

import android.app.Activity;
import android.view.View;

import com.androidtoolsuite.app.migration.DatasetBridge;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.json.JSONObject;

/** Internal host tool model used by format v3 renderers and data-management adapters. */
public interface HostTool {
    String id();
    String title();
    String description();
    default String version() { return "1.0"; }
    boolean removable();
    default Set<String> dependencies() { return Collections.emptySet(); }
    default List<HostHomeWidget> createHomeWidgets(Activity activity, HostServices host) {
        return Collections.emptyList();
    }
    default DatasetBridge datasetBridge() { return null; }
    View createView(Activity activity, HostServices host);
    default void onVisibilityChanged(boolean visible) {}
    /** Configure internal back handling only for this tool's current renderer session. */
    default void setBackNavigation(String sessionId, boolean enabled) {}
    default boolean ownsSession(String sessionId) { return false; }
    /** Session-only navigation from an instance owned by this plugin. */
    default void onWidgetSelected(JSONObject entry) {}
    /** True when the visible tool accepted native Back for one of its own child pages. */
    default boolean onBackRequested() { return false; }
    void onSelected();
    void onHostStateChanged();
    void onDestroy();
}
