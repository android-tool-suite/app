package com.androidtoolsuite.app.plugin.runtime;

import com.androidtoolsuite.app.plugin.runtime.HostTool;
import java.util.ArrayList;
import java.util.List;

public final class ToolRegistry {
    private ToolRegistry() {
    }

    public static List<HostTool> createRequiredBuiltInPlugins() {
        return new ArrayList<>();
    }

    public static List<HostTool> createOptionalBuiltInPlugins() {
        return new ArrayList<>();
    }

    public static List<HostTool> createBuiltInPlugins() {
        List<HostTool> plugins = createRequiredBuiltInPlugins();
        plugins.addAll(createOptionalBuiltInPlugins());
        return plugins;
    }
}
