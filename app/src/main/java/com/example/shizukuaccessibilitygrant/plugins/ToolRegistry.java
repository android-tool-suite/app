package com.example.shizukuaccessibilitygrant.plugins;

import java.util.ArrayList;
import java.util.List;

public final class ToolRegistry {
    private ToolRegistry() {
    }

    public static List<ToolPlugin> createRequiredBuiltInPlugins() {
        List<ToolPlugin> plugins = new ArrayList<>();
        plugins.add(new ShizukuPlugin());
        return plugins;
    }

    public static List<ToolPlugin> createOptionalBuiltInPlugins() {
        return new ArrayList<>();
    }

    public static List<ToolPlugin> createBuiltInPlugins() {
        List<ToolPlugin> plugins = createRequiredBuiltInPlugins();
        plugins.addAll(createOptionalBuiltInPlugins());
        return plugins;
    }
}
