package com.example.shizukuaccessibilitygrant.plugin.api;

import android.app.Activity;
import android.view.View;

public interface HomeWidget {
    String id();

    String title();

    String pluginId();

    View createView(Activity activity, PluginHost host);
}
