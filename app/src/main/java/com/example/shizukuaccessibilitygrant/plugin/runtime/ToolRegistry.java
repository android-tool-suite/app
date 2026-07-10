package com.example.shizukuaccessibilitygrant.plugin.runtime;

import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.plugins.builtin.host.HostAppPlugin;
import com.example.shizukuaccessibilitygrant.plugins.builtin.shizuku.ShizukuPlugin;
import java.util.ArrayList;
import java.util.List;

public final class ToolRegistry {
    private ToolRegistry() {
    }

    public static List<ToolPlugin> createRequiredBuiltInPlugins() {
        List<ToolPlugin> plugins = new ArrayList<>();
        plugins.add(new HostAppPlugin());
        return plugins;
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
