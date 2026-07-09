package com.example.shizukuaccessibilitygrant.plugins;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.shizukuaccessibilitygrant.ui.UiKit;

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
    public boolean removable() {
        return true;
    }

    @Override
    public Set<String> requestedPermissions() {
        return descriptor.requestedPermissions;
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
        int gap = dp(activity, 12);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout panel = UiKit.card(activity);

        TextView title = new TextView(activity);
        title.setText(descriptor.title);
        UiKit.styleTitle(title, 22);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView meta = new TextView(activity);
        meta.setText("版本 " + descriptor.version + " · " + descriptor.author);
        UiKit.styleCaption(meta);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
        metaParams.topMargin = dp(activity, 6);
        panel.addView(meta, metaParams);

        TextView description = new TextView(activity);
        description.setText(descriptor.description);
        UiKit.styleBody(description);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = gap;
        panel.addView(description, descParams);

        TextView note = new TextView(activity);
        note.setText("这是导入的插件清单。当前版本支持导入、展示和删除外部插件；可执行插件需要作为内置 Java 插件接入 ToolRegistry。");
        note.setTextSize(14);
        note.setTextColor(UiKit.COLOR_WARN);
        note.setBackground(UiKit.rounded(0xFFFFF7ED, 8, activity));
        note.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = gap;
        panel.addView(note, noteParams);

        TextView permissionsTitle = new TextView(activity);
        permissionsTitle.setText("权限");
        permissionsTitle.setTextSize(16);
        permissionsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        permissionsTitle.setTextColor(UiKit.COLOR_TEXT);
        LinearLayout.LayoutParams permissionsTitleParams = new LinearLayout.LayoutParams(-1, -2);
        permissionsTitleParams.topMargin = gap;
        panel.addView(permissionsTitle, permissionsTitleParams);

        if (descriptor.requestedPermissions.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("该插件未声明额外权限。");
            empty.setTextSize(14);
            empty.setTextColor(UiKit.COLOR_MUTED);
            panel.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (String permission : descriptor.requestedPermissions) {
                panel.addView(createPermissionRow(activity, permission), new LinearLayout.LayoutParams(-1, -2));
            }
        }

        Button delete = new Button(activity);
        delete.setText("删除插件");
        UiKit.styleDangerButton(delete);
        delete.setOnClickListener(v -> host.deleteImportedPlugin(descriptor.id));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-1, dp(activity, 46));
        deleteParams.topMargin = dp(activity, 18);
        panel.addView(delete, deleteParams);

        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        TextView footer = new TextView(activity);
        footer.setGravity(Gravity.CENTER);
        footer.setText("插件 ID: " + descriptor.id);
        footer.setTextSize(12);
        footer.setTextColor(UiKit.COLOR_MUTED);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(-1, -2);
        footerParams.topMargin = gap;
        root.addView(footer, footerParams);
        return root;
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
        public View createView(Activity activity, PluginHost host) {
            LinearLayout card = UiKit.card(activity);

            TextView title = new TextView(activity);
            title.setText(widget.title);
            UiKit.styleCaption(title);
            card.addView(title, new LinearLayout.LayoutParams(-1, -2));

            TextView value = new TextView(activity);
            value.setText(widget.value.isEmpty() ? plugin.title : widget.value);
            UiKit.styleTitle(value, 20);
            LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(-1, -2);
            valueParams.topMargin = UiKit.dp(activity, 4);
            card.addView(value, valueParams);

            TextView subtitle = new TextView(activity);
            subtitle.setText(widget.subtitle.isEmpty() ? plugin.description : widget.subtitle);
            UiKit.styleBody(subtitle);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
            subtitleParams.topMargin = UiKit.dp(activity, 4);
            card.addView(subtitle, subtitleParams);
            return card;
        }
    }
}
