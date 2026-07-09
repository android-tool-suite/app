package com.example.shizukuaccessibilitygrant.plugin.runtime;

import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import com.example.shizukuaccessibilitygrant.plugins.accessibility.AccessibilityGrantPlugin;
import com.example.shizukuaccessibilitygrant.plugins.accessibility.AccessibilityGrantPluginDescriptor;
import com.example.shizukuaccessibilitygrant.plugins.external.ImportedToolPlugin;
public final class ExternalToolFactory {
    private ExternalToolFactory() {
    }

    public static ToolPlugin create(ImportedPluginDescriptor descriptor) {
        if (AccessibilityGrantPluginDescriptor.ID.equals(descriptor.id)) {
            return new AccessibilityGrantPlugin(descriptor);
        }
        return new ImportedToolPlugin(descriptor);
    }
}
