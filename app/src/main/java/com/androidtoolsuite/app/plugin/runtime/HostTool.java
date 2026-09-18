package com.androidtoolsuite.app.plugin.runtime;

import android.app.Activity;
import android.view.View;

import com.androidtoolsuite.app.migration.DatasetBridge;

import java.util.Collections;
import java.util.List;
import java.util.Set;

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
    void onSelected();
    void onHostStateChanged();
    void onDestroy();
}
