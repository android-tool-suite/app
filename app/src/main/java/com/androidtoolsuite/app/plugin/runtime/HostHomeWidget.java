package com.androidtoolsuite.app.plugin.runtime;

import android.app.Activity;
import android.view.View;

import com.androidtoolsuite.app.plugin.api.HomeWidgetSize;

import java.util.Arrays;
import java.util.List;

/** Internal host rendering abstraction; it is not part of the external plugin SDK. */
public interface HostHomeWidget {
    String id();
    String title();
    String pluginId();

    default List<HomeWidgetSize> supportedSizes() {
        return Arrays.asList(new HomeWidgetSize(2, 2), new HomeWidgetSize(4, 2));
    }

    View createView(Activity activity, HostServices host);
}
