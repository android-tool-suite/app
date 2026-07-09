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

import com.example.shizukuaccessibilitygrant.ui.UiKit;

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
        return "导入、导出、删除插件，并管理插件注册的权限。";
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
        UiKit.styleTitle(title, 30);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(activity);
        description.setText("导入插件包或清单文件，导出外部插件清单，并按插件注册的权限进行授予或撤销。");
        UiKit.styleBody(description);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = dp(6);
        root.addView(description, descParams);

        Button importButton = new Button(activity);
        importButton.setText("导入插件");
        UiKit.stylePrimaryButton(importButton);
        importButton.setOnClickListener(v -> host.importPlugin());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(-1, dp(48));
        importParams.topMargin = dp(14);
        root.addView(importButton, importParams);

        TextView format = new TextView(activity);
        format.setText("推荐 .atsplugin 包：zip 内含 manifest.json；也兼容单个 JSON 清单。");
        UiKit.styleCaption(format);
        LinearLayout.LayoutParams formatParams = new LinearLayout.LayoutParams(-1, -2);
        formatParams.topMargin = dp(8);
        root.addView(format, formatParams);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
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
        List<ToolPlugin> installedPlugins = host.installedPlugins();
        List<ImportedPluginDescriptor> imported = store.load();

        addSectionTitle("已安装插件");
        for (ToolPlugin plugin : installedPlugins) {
            if (!plugin.removable()) {
                list.addView(createBuiltInRow(plugin), new LinearLayout.LayoutParams(-1, -2));
            }
        }

        addSectionTitle("外部插件");
        if (imported.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("还没有导入外部插件。");
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(14);
            empty.setTextColor(UiKit.COLOR_MUTED);
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
        title.setTextColor(UiKit.COLOR_TEXT);
        title.setPadding(0, dp(14), 0, dp(8));
        list.addView(title, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addMutedMessage(String text) {
        TextView message = new TextView(activity);
        message.setText(text);
        message.setTextSize(14);
        message.setTextColor(UiKit.COLOR_MUTED);
        message.setPadding(0, dp(12), 0, dp(12));
        list.addView(message, new LinearLayout.LayoutParams(-1, -2));
    }

    private View createBuiltInRow(ToolPlugin plugin) {
        LinearLayout row = UiKit.card(activity);

        TextView title = new TextView(activity);
        title.setText(plugin.title());
        UiKit.styleTitle(title, 16);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(activity);
        description.setText(plugin.description());
        UiKit.styleBody(description);
        row.addView(description, new LinearLayout.LayoutParams(-1, -2));

        TextView meta = new TextView(activity);
        meta.setText("内置插件 · 权限 " + plugin.requestedPermissions().size() + " · 小部件 " + plugin.createHomeWidgets(activity, host).size());
        UiKit.styleCaption(meta);
        row.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        if (plugin.requestedPermissions().isEmpty()) {
            addPermissionNote(row, "该内置插件未注册额外权限。");
        } else {
            for (String permission : plugin.requestedPermissions()) {
                CheckBox checkBox = new CheckBox(activity);
                checkBox.setText(PluginPermissionCatalog.label(permission));
                checkBox.setTextSize(13);
                checkBox.setTextColor(UiKit.COLOR_MUTED);
                checkBox.setChecked(true);
                checkBox.setEnabled(false);
                row.addView(checkBox, new LinearLayout.LayoutParams(-1, -2));
            }
        }

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setPadding(0, 0, 0, dp(10));
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrapper;
    }

    private View createPluginRow(ImportedPluginDescriptor descriptor) {
        LinearLayout row = UiKit.card(activity);

        TextView title = new TextView(activity);
        title.setText(descriptor.title);
        UiKit.styleTitle(title, 16);
        row.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView description = new TextView(activity);
        description.setText(descriptor.description);
        UiKit.styleBody(description);
        row.addView(description, new LinearLayout.LayoutParams(-1, -2));

        TextView meta = new TextView(activity);
        meta.setText("版本 " + descriptor.version
                + " · 权限 " + descriptor.grantedPermissions.size() + "/" + descriptor.requestedPermissions.size()
                + " · 小部件 " + descriptor.widgets.size());
        UiKit.styleCaption(meta);
        row.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        if (descriptor.requestedPermissions.isEmpty()) {
            addPermissionNote(row, "该插件未注册额外权限。");
        } else {
            LinearLayout permissions = new LinearLayout(activity);
            permissions.setOrientation(LinearLayout.VERTICAL);
            permissions.setPadding(0, dp(6), 0, 0);
            for (String permission : descriptor.requestedPermissions) {
                CheckBox checkBox = new CheckBox(activity);
                checkBox.setText(PluginPermissionCatalog.label(permission));
                checkBox.setTextSize(13);
                checkBox.setTextColor(UiKit.COLOR_TEXT);
                checkBox.setChecked(descriptor.grantedPermissions.contains(permission));
                checkBox.setOnCheckedChangeListener((buttonView, isChecked) ->
                        host.setImportedPluginPermission(descriptor.id, permission, isChecked));
                permissions.addView(checkBox, new LinearLayout.LayoutParams(-1, -2));
            }
            row.addView(permissions, new LinearLayout.LayoutParams(-1, -2));
        }

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(8);

        Button export = new Button(activity);
        export.setText("导出");
        UiKit.styleSecondaryButton(export);
        export.setOnClickListener(v -> host.exportPlugin(descriptor.id));
        actions.addView(export, new LinearLayout.LayoutParams(0, dp(44), 1));

        Button delete = new Button(activity);
        delete.setText("删除");
        UiKit.styleDangerButton(delete);
        delete.setOnClickListener(v -> host.deleteImportedPlugin(descriptor.id));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        deleteParams.leftMargin = dp(8);
        actions.addView(delete, deleteParams);
        row.addView(actions, actionsParams);

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setPadding(0, 0, 0, dp(10));
        wrapper.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return wrapper;
    }

    private void addPermissionNote(LinearLayout row, String text) {
        TextView note = new TextView(activity);
        note.setText(text);
        note.setTextSize(13);
        note.setTextColor(UiKit.COLOR_MUTED);
        note.setPadding(0, dp(8), 0, 0);
        row.addView(note, new LinearLayout.LayoutParams(-1, -2));
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
