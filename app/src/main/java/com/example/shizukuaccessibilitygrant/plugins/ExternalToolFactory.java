package com.example.shizukuaccessibilitygrant.plugins;

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
