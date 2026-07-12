package com.example.shizukuaccessibilitygrant.plugin.runtime;

import android.content.Context;

import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;

import java.io.File;

import dalvik.system.DexClassLoader;

public final class ExternalToolFactory {
    private ExternalToolFactory() {
    }

    public static ToolPlugin create(Context context, ImportedPluginDescriptor descriptor) {
        return loadDynamicPlugin(context, descriptor);
    }

    private static ToolPlugin loadDynamicPlugin(Context context, ImportedPluginDescriptor descriptor) {
        if (descriptor.entryClass.isEmpty() || descriptor.codePath.isEmpty()) {
            return null;
        }
        File codeFile = new File(descriptor.codePath);
        if (!codeFile.exists()) {
            return null;
        }
        if (codeFile.canWrite()) {
            codeFile.setReadOnly();
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
