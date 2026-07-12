package com.androidtoolsuite.app.plugin.api;

import android.app.Activity;
import android.view.View;

import java.util.List;
import java.util.Arrays;

public interface HomeWidget {
    String id();

    String title();

    String pluginId();

    default List<HomeWidgetSize> supportedSizes() {
        return Arrays.asList(new HomeWidgetSize(2, 2), new HomeWidgetSize(4, 2));
    }

    View createView(Activity activity, PluginHost host);
}
