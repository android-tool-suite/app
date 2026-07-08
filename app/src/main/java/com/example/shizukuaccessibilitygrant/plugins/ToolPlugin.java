package com.example.shizukuaccessibilitygrant.plugins;

import android.app.Activity;
import android.view.View;

public interface ToolPlugin {
    String id();

    String title();

    String description();

    boolean removable();

    View createView(Activity activity, PluginHost host);

    void onSelected();

    void onHostStateChanged();

    void onDestroy();
}
