package com.example.shizukuaccessibilitygrant.plugins;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public final class PluginManagerPlugin implements ToolPlugin {
    private Activity activity;
    private PluginHost host;
    private LinearLayout list;
    private View rootView;

    @Override
    public String id() {
        return "plugin_manager";
    }

    @Override
    public String title() {
        return "插件管理";
    }

    @Override
    public String description() {
        return "导入外部插件清单，删除已导入插件。";
    }

    @Override
    public boolean removable() {
        return false;
    }

    @Override
    public View createView(Activity activity, PluginHost host) {
        this.activity = activity;
        this.host = host;
        if (rootView == null) {
            rootView = createContentView();
        }
        renderList();
        return rootView;
    }

    @Override
    public void onSelected() {
        renderList();
    }

    @Override
    public void onHostStateChanged() {
    }

    @Override
    public void onDestroy() {
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(activity);
        title.setText("插件管理");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF10201D);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(activity);
        description.setText("导入插件包或清单文件，管理外部插件及其权限。内置插件由应用提供，不能删除。");
        description.setTextSize(14);
        description.setTextColor(0xFF5B6663);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = dp(6);
        root.addView(description, descParams);

        Button importButton = new Button(activity);
        importButton.setText("导入插件");
        importButton.setOnClickListener(v -> host.importPlugin());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(-1, dp(48));
        importParams.topMargin = dp(14);
        root.addView(importButton, importParams);

        TextView format = new TextView(activity);
        format.setText("推荐 .atsplugin 包：zip 内含 manifest.json；也兼容单个 JSON 清单。");
        format.setTextSize(13);
        format.setTextColor(0xFF64706D);
        LinearLayout.LayoutParams formatParams = new LinearLayout.LayoutParams(-1, -2);
        formatParams.topMargin = dp(8);
        root.addView(format, formatParams);

        ScrollView scrollView = new ScrollView(activity);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(14), 0, 0);
        scrollView.addView(list, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1);
        root.addView(scrollView, listParams);
        return root;
    }

    private void renderList() {
        if (list == null || activity == null) {
            return;
        }
        list.removeAllViews();
        ExternalPluginStore store = new ExternalPluginStore(activity);
        List<ToolPlugin> optionalBuiltIns = host.optionalBuiltInPlugins();
        List<ImportedPluginDescriptor> imported = store.load();
        addSectionTitle("内置可选插件");
        if (optionalBuiltIns.isEmpty()) {
            addMutedMessage("没有可选内置插件。");
        } else {
            for (ToolPlugin plugin : optionalBuiltIns) {
                list.addView(createOptionalBuiltInRow(plugin), new LinearLayout.LayoutParams(-1, -2));
            }
        }

        addSectionTitle("外部插件");
        if (imported.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("还没有导入外部插件。");
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(14);
            empty.setTextColor(0xFF64706D);
            empty.setPadding(0, dp(36), 0, 0);
            list.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        for (ImportedPluginDescriptor descriptor : imported) {
            list.addView(createPluginRow(descriptor), new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void addSectionTitle(String text) {
        TextView title = new TextView(activity);
        title.setText(text);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF10201D);
        title.setPadding(0, dp(8), 0, dp(8));
        list.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addMutedMessage(String text) {
        TextView message = new TextView(activity);
        message.setText(text);
        message.setTextSize(14);
        message.setTextColor(0xFF64706D);
        message.setPadding(0, dp(12), 0, dp(12));
        list.addView(message, new LinearLayout.LayoutParams(-1, -2));
    }

    private View createOptionalBuiltInRow(ToolPlugin plugin) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundColor(0xFFFFFFFF);

        TextView title = new TextView(activity);
        title.setText(plugin.title());
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF10201D);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(activity);
        description.setText(plugin.description());
        description.setTextSize(14);
        description.setTextColor(0xFF53615D);
        row.addView(description, new LinearLayout.LayoutParams(-1, -2));

        CheckBox enabled = new CheckBox(activity);
        enabled.setText("启用此插件");
        enabled.setTextSize(14);
        enabled.setTextColor(0xFF344541);
        enabled.setChecked(host.isBuiltInPluginEnabled(plugin.id()));
        enabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                host.setBuiltInPluginEnabled(plugin.id(), isChecked));
        LinearLayout.LayoutParams enabledParams = new LinearLayout.LayoutParams(-1, -2);
        enabledParams.topMargin = dp(8);
        row.addView(enabled, enabledParams);

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setPadding(0, 0, 0, dp(10));
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrapper;
    }

    private View createPluginRow(ImportedPluginDescriptor descriptor) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundColor(0xFFFFFFFF);

        TextView title = new TextView(activity);
        title.setText(descriptor.title);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF10201D);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(activity);
        description.setText(descriptor.description);
        description.setTextSize(14);
        description.setTextColor(0xFF53615D);
        row.addView(description, new LinearLayout.LayoutParams(-1, -2));

        TextView meta = new TextView(activity);
        meta.setText("版本 " + descriptor.version + " · 权限 " + descriptor.grantedPermissions.size() + "/" + descriptor.requestedPermissions.size());
        meta.setTextSize(12);
        meta.setTextColor(0xFF64706D);
        row.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        if (!descriptor.requestedPermissions.isEmpty()) {
            LinearLayout permissions = new LinearLayout(activity);
            permissions.setOrientation(LinearLayout.VERTICAL);
            permissions.setPadding(0, dp(6), 0, 0);
            for (String permission : descriptor.requestedPermissions) {
                CheckBox checkBox = new CheckBox(activity);
                checkBox.setText(PluginPermissionCatalog.label(permission));
                checkBox.setTextSize(13);
                checkBox.setTextColor(0xFF344541);
                checkBox.setChecked(descriptor.grantedPermissions.contains(permission));
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                        host.setImportedPluginPermission(descriptor.id, permission, isChecked));
                permissions.addView(checkBox, new LinearLayout.LayoutParams(-1, -2));
            }
            row.addView(permissions, new LinearLayout.LayoutParams(-1, -2));
        }

        Button delete = new Button(activity);
        delete.setText("删除");
        delete.setOnClickListener(v -> host.deleteImportedPlugin(descriptor.id));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-1, dp(44));
        deleteParams.topMargin = dp(8);
        row.addView(delete, deleteParams);

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setPadding(0, 0, 0, dp(10));
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrapper;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
