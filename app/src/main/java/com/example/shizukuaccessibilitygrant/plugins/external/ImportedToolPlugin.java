package com.example.shizukuaccessibilitygrant.plugins.external;

import com.example.shizukuaccessibilitygrant.plugin.api.HomeWidget;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginHost;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginPermissionCatalog;
import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedWidgetDescriptor;
import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.shizukuaccessibilitygrant.ui.UiKit;
import com.example.shizukuaccessibilitygrant.plugins.ComposePluginUiKt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ImportedToolPlugin implements ToolPlugin {
    private final ImportedPluginDescriptor descriptor;
    private PluginHost host;

    public ImportedToolPlugin(ImportedPluginDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public String id() {
        return descriptor.id;
    }

    @Override
    public String title() {
        return descriptor.title;
    }

    @Override
    public String description() {
        return descriptor.description;
    }

    @Override
    public String version() {
        return descriptor.version;
    }

    @Override
    public boolean removable() {
        return true;
    }

    @Override
    public Set<String> requestedPermissions() {
        return descriptor.requestedPermissions;
    }

    @Override
    public Set<String> dependencies() {
        return descriptor.dependencies;
    }

    @Override
    public List<HomeWidget> createHomeWidgets(Activity activity, PluginHost host) {
        List<HomeWidget> widgets = new ArrayList<>();
        for (ImportedWidgetDescriptor widget : descriptor.widgets) {
            widgets.add(new ImportedHomeWidget(descriptor, widget));
        }
        return widgets;
    }

    @Override
    public View createView(Activity activity, PluginHost host) {
        this.host = host;
        return ComposePluginUiKt.createImportedPluginView(activity, descriptor, host);
    }

    private View createPermissionRow(Activity activity, String permission) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(activity, 6), 0, dp(activity, 6));

        CheckBox checkBox = new CheckBox(activity);
        checkBox.setText(PluginPermissionCatalog.label(permission));
        checkBox.setTextSize(14);
        checkBox.setTextColor(UiKit.COLOR_TEXT);
        checkBox.setChecked(descriptor.grantedPermissions.contains(permission));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                host.setImportedPluginPermission(descriptor.id, permission, isChecked));
        row.addView(checkBox, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(activity);
        description.setText(PluginPermissionCatalog.description(permission));
        description.setTextSize(12);
        description.setTextColor(UiKit.COLOR_MUTED);
        description.setPadding(dp(activity, 42), 0, 0, 0);
        row.addView(description, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    @Override
    public void onSelected() {
    }

    @Override
    public void onHostStateChanged() {
    }

    @Override
    public void onDestroy() {
    }

    private int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class ImportedHomeWidget implements HomeWidget {
        private final ImportedPluginDescriptor plugin;
        private final ImportedWidgetDescriptor widget;

        ImportedHomeWidget(ImportedPluginDescriptor plugin, ImportedWidgetDescriptor widget) {
            this.plugin = plugin;
            this.widget = widget;
        }

        @Override
        public String id() {
            return widget.id;
        }

        @Override
        public String title() {
            return widget.title;
        }

        @Override
        public String pluginId() {
            return plugin.id;
        }

        @Override
        public List<com.example.shizukuaccessibilitygrant.plugin.api.HomeWidgetSize> supportedSizes() {
            return widget.sizes;
        }

        @Override
        public View createView(Activity activity, PluginHost host) {
            return ComposePluginUiKt.createImportedWidgetView(activity, plugin, widget);
        }
    }
}
