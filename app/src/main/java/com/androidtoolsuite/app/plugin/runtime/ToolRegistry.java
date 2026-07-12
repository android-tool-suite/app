package com.androidtoolsuite.app.plugin.runtime;

import com.androidtoolsuite.app.plugin.api.ToolPlugin;
import com.androidtoolsuite.app.plugins.builtin.shizuku.ShizukuPlugin;
import java.util.ArrayList;
import java.util.List;

public final class ToolRegistry {
    private ToolRegistry() {
    }

    public static List<ToolPlugin> createRequiredBuiltInPlugins() {
        return new ArrayList<>();
    }

    public static List<ToolPlugin> createOptionalBuiltInPlugins() {
        List<ToolPlugin> plugins = new ArrayList<>();
        plugins.add(new ShizukuPlugin());
        return plugins;
    }

    public static List<ToolPlugin> createBuiltInPlugins() {
        List<ToolPlugin> plugins = createRequiredBuiltInPlugins();
        plugins.addAll(createOptionalBuiltInPlugins());
        return plugins;
    }
}
