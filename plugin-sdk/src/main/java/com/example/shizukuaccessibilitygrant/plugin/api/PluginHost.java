package com.example.shizukuaccessibilitygrant.plugin.api;

import android.app.Activity;

import java.io.IOException;
import java.util.List;

public interface PluginHost {
    Activity activity();

    boolean isShizukuReady();

    boolean hasShizukuPermission();

    boolean isShellServiceConnected();

    int shizukuUid();

    void requestShizukuPermission();

    void ensureShellService();

    String runShellCommand(String... command) throws IOException;

    void importPlugin();

    void exportPlugin(String pluginId);

    void deleteImportedPlugin(String pluginId);

    boolean isImportedPluginEnabled(String pluginId);

    void setImportedPluginEnabled(String pluginId, boolean enabled);

    List<ToolPlugin> optionalBuiltInPlugins();

    List<ToolPlugin> installedPlugins();

    boolean isBuiltInPluginEnabled(String pluginId);

    void setBuiltInPluginEnabled(String pluginId, boolean enabled);

    void showToast(String message);
}
