package com.example.shizukuaccessibilitygrant.plugins;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    public View createView(Activity activity, PluginHost host) {
        this.host = host;
        int gap = dp(activity, 12);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16));
        panel.setBackgroundColor(0xFFFFFFFF);

        TextView title = new TextView(activity);
        title.setText(descriptor.title);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF10201D);
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView meta = new TextView(activity);
        meta.setText("版本 " + descriptor.version + " · " + descriptor.author);
        meta.setTextSize(13);
        meta.setTextColor(0xFF64706D);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(-1, -2);
        metaParams.topMargin = dp(activity, 6);
        panel.addView(meta, metaParams);

        TextView description = new TextView(activity);
        description.setText(descriptor.description);
        description.setTextSize(15);
        description.setTextColor(0xFF344541);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = gap;
        panel.addView(description, descParams);

        TextView note = new TextView(activity);
        note.setText("这是导入的插件清单。当前版本支持导入、展示和删除外部插件；可执行插件需要作为内置 Java 插件接入 ToolRegistry。");
        note.setTextSize(14);
        note.setTextColor(0xFF7C2D12);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = gap;
        panel.addView(note, noteParams);

        TextView permissionsTitle = new TextView(activity);
        permissionsTitle.setText("权限");
        permissionsTitle.setTextSize(16);
        permissionsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        permissionsTitle.setTextColor(0xFF10201D);
        LinearLayout.LayoutParams permissionsTitleParams = new LinearLayout.LayoutParams(-1, -2);
        permissionsTitleParams.topMargin = gap;
        panel.addView(permissionsTitle, permissionsTitleParams);

        if (descriptor.requestedPermissions.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("该插件未声明额外权限。");
            empty.setTextSize(14);
            empty.setTextColor(0xFF64706D);
            panel.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (String permission : descriptor.requestedPermissions) {
                panel.addView(createPermissionRow(activity, permission), new LinearLayout.LayoutParams(-1, -2));
            }
        }

        Button delete = new Button(activity);
        delete.setText("删除插件");
        delete.setOnClickListener(v -> host.deleteImportedPlugin(descriptor.id));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-1, dp(activity, 46));
        deleteParams.topMargin = dp(activity, 18);
        panel.addView(delete, deleteParams);

        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        TextView footer = new TextView(activity);
        footer.setGravity(Gravity.CENTER);
        footer.setText("插件 ID: " + descriptor.id);
        footer.setTextSize(12);
        footer.setTextColor(0xFF7A8582);
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
        checkBox.setTextColor(0xFF344541);
        checkBox.setChecked(descriptor.grantedPermissions.contains(permission));
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                host.setImportedPluginPermission(descriptor.id, permission, isChecked));
        row.addView(checkBox, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(activity);
        description.setText(PluginPermissionCatalog.description(permission));
        description.setTextSize(12);
        description.setTextColor(0xFF64706D);
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
}
