package com.androidtoolsuite.app.plugin.runtime;

import android.app.Activity;

import java.io.IOException;
import java.util.List;

/** Internal services exposed to host-owned tool renderers. */
public interface HostServices {
    Activity activity();
    boolean isShizukuReady();
    boolean hasShizukuPermission();
    boolean isShellServiceConnected();
    int hostStateRevision();
    int shizukuUid();
    void requestShizukuPermission();
    void ensureShellService();
    String runShellCommand(String... command) throws IOException;
    void importPlugin();
    void exportPlugin(String pluginId);
    void deleteImportedPlugin(String pluginId);
    boolean isImportedPluginEnabled(String pluginId);
    void setImportedPluginEnabled(String pluginId, boolean enabled);
    List<HostTool> optionalBuiltInPlugins();
    List<HostTool> installedPlugins();
    boolean isBuiltInPluginEnabled(String pluginId);
    void setBuiltInPluginEnabled(String pluginId, boolean enabled);
    void showToast(String message);
}
