package com.example.shizukuaccessibilitygrant.plugin.runtime;

import android.content.Context;

import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import com.example.shizukuaccessibilitygrant.plugins.external.ImportedToolPlugin;

import java.io.File;

import dalvik.system.DexClassLoader;

public final class ExternalToolFactory {
    private ExternalToolFactory() {
    }

    public static ToolPlugin create(Context context, ImportedPluginDescriptor descriptor) {
        ToolPlugin dynamicPlugin = loadDynamicPlugin(context, descriptor);
        if (dynamicPlugin != null) {
            return dynamicPlugin;
        }
        return new ImportedToolPlugin(descriptor);
    }

    private static ToolPlugin loadDynamicPlugin(Context context, ImportedPluginDescriptor descriptor) {
        if (descriptor.entryClass.isEmpty() || descriptor.codePath.isEmpty()) {
            return null;
        }
        File codeFile = new File(descriptor.codePath);
        if (!codeFile.exists()) {
            return null;
        }
        try {
            File optimizedDir = context.getDir("plugin_dex", Context.MODE_PRIVATE);
            DexClassLoader classLoader = new DexClassLoader(
                    codeFile.getAbsolutePath(),
                    optimizedDir.getAbsolutePath(),
                    null,
                    context.getClassLoader()
            );
            Class<?> pluginClass = classLoader.loadClass(descriptor.entryClass);
            Object instance = pluginClass.getDeclaredConstructor().newInstance();
            if (instance instanceof ToolPlugin) {
                return (ToolPlugin) instance;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
        return null;
    }
}
