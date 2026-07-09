package com.example.shizukuaccessibilitygrant.host.management;

import com.example.shizukuaccessibilitygrant.plugin.api.PluginHost;
import com.example.shizukuaccessibilitygrant.plugin.api.PluginPermissionCatalog;
import com.example.shizukuaccessibilitygrant.plugin.api.ToolPlugin;
import com.example.shizukuaccessibilitygrant.plugin.model.ImportedPluginDescriptor;
import com.example.shizukuaccessibilitygrant.plugin.store.ExternalPluginStore;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Set<String> activePluginIds = activePluginIds(installedPlugins);
        List<ImportedPluginDescriptor> imported = store.load();
        List<ToolPlugin> builtIns = host.optionalBuiltInPlugins();
        PluginGraph graph = buildPluginGraph(builtIns, imported, activePluginIds);

        addSectionTitle("内置插件");
        if (builtIns.isEmpty()) {
            addMutedMessage("没有可管理的内置插件。");
        } else {
            for (ToolPlugin plugin : builtIns) {
                list.addView(createBuiltInRow(plugin, activePluginIds, graph), new LinearLayout.LayoutParams(-1, -2));
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
        } else {
            for (ImportedPluginDescriptor descriptor : imported) {
                list.addView(createPluginRow(descriptor, activePluginIds, graph), new LinearLayout.LayoutParams(-1, -2));
            }
        }

        addDependencyTree(graph);
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

    private View createBuiltInRow(ToolPlugin plugin, Set<String> activePluginIds, PluginGraph graph) {
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
        boolean enabled = host.isBuiltInPluginEnabled(plugin.id());
        meta.setText((enabled ? "已启用" : "已停用")
                + " · 内置插件 · 权限 " + plugin.requestedPermissions().size()
                + " · 小部件 " + plugin.createHomeWidgets(activity, host).size()
                + dependencySummary(plugin.dependencies(), activePluginIds));
        UiKit.styleCaption(meta);
        row.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        CheckBox enabledCheckBox = new CheckBox(activity);
        enabledCheckBox.setText("启用此内置插件");
        enabledCheckBox.setTextSize(14);
        enabledCheckBox.setTextColor(UiKit.COLOR_TEXT);
        enabledCheckBox.setChecked(enabled);
        enabledCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            host.setBuiltInPluginEnabled(plugin.id(), isChecked);
            renderList();
        });
        LinearLayout.LayoutParams enabledParams = new LinearLayout.LayoutParams(-1, -2);
        enabledParams.topMargin = dp(8);
        row.addView(enabledCheckBox, enabledParams);

        addDependencyDetails(row, plugin.id(), plugin.dependencies(), graph);

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

    private View createPluginRow(ImportedPluginDescriptor descriptor, Set<String> activePluginIds, PluginGraph graph) {
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
        boolean enabled = host.isImportedPluginEnabled(descriptor.id);
        meta.setText((enabled ? "已启用" : "已停用")
                + " · 版本 " + descriptor.version
                + " · 权限 " + descriptor.grantedPermissions.size() + "/" + descriptor.requestedPermissions.size()
                + " · 小部件 " + descriptor.widgets.size()
                + dependencySummary(descriptor.dependencies, activePluginIds));
        UiKit.styleCaption(meta);
        row.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        if (!descriptor.dependencies.isEmpty() && !activePluginIds.containsAll(descriptor.dependencies)) {
            addPermissionNote(row, "依赖未满足，插件暂不会出现在插件列表和主页中。");
        }
        if (!enabled) {
            addPermissionNote(row, "插件已停用，暂不会出现在插件列表和主页中。");
        }

        CheckBox enabledCheckBox = new CheckBox(activity);
        enabledCheckBox.setText("启用此外部插件");
        enabledCheckBox.setTextSize(14);
        enabledCheckBox.setTextColor(UiKit.COLOR_TEXT);
        enabledCheckBox.setChecked(enabled);
        enabledCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            host.setImportedPluginEnabled(descriptor.id, isChecked);
            renderList();
        });
        LinearLayout.LayoutParams enabledParams = new LinearLayout.LayoutParams(-1, -2);
        enabledParams.topMargin = dp(8);
        row.addView(enabledCheckBox, enabledParams);

        addDependencyDetails(row, descriptor.id, descriptor.dependencies, graph);

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

    private void addDependencyDetails(LinearLayout row, String pluginId, Set<String> dependencies, PluginGraph graph) {
        addPermissionNote(row, "依赖：" + dependencyNames(dependencies, graph));
        List<PluginInfo> dependents = graph.dependentsOf(pluginId);
        if (dependents.isEmpty()) {
            addPermissionNote(row, "被依赖：无");
        } else {
            addPermissionNote(row, "被依赖：" + pluginNames(dependents));
        }
    }

    private void addDependencyTree(PluginGraph graph) {
        addSectionTitle("依赖关系");
        if (graph.plugins.isEmpty()) {
            addMutedMessage("还没有可查看的插件依赖。");
            return;
        }
        LinearLayout card = UiKit.card(activity);
        TextView title = new TextView(activity);
        title.setText("插件依赖树");
        UiKit.styleTitle(title, 16);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView body = new TextView(activity);
        body.setText(buildDependencyTreeText(graph));
        body.setTextSize(13);
        body.setTextColor(UiKit.COLOR_MUTED);
        body.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2);
        bodyParams.topMargin = dp(8);
        card.addView(body, bodyParams);

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setPadding(0, 0, 0, dp(10));
        wrapper.addView(card, new LinearLayout.LayoutParams(-1, -2));
        list.addView(wrapper, new LinearLayout.LayoutParams(-1, -2));
    }

    private Set<String> activePluginIds(List<ToolPlugin> plugins) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ToolPlugin plugin : plugins) {
            ids.add(plugin.id());
        }
        return ids;
    }

    private String dependencySummary(Set<String> dependencies, Set<String> activePluginIds) {
        if (dependencies.isEmpty()) {
            return " · 依赖 0";
        }
        int satisfied = 0;
        for (String dependency : dependencies) {
            if (activePluginIds.contains(dependency)) {
                satisfied++;
            }
        }
        return " · 依赖 " + satisfied + "/" + dependencies.size();
    }

    private PluginGraph buildPluginGraph(List<ToolPlugin> builtIns, List<ImportedPluginDescriptor> imported, Set<String> activePluginIds) {
        PluginGraph graph = new PluginGraph();
        for (ToolPlugin plugin : builtIns) {
            graph.add(new PluginInfo(
                    plugin.id(),
                    plugin.title(),
                    plugin.dependencies(),
                    host.isBuiltInPluginEnabled(plugin.id()),
                    activePluginIds.contains(plugin.id()),
                    true
            ));
        }
        for (ImportedPluginDescriptor descriptor : imported) {
            graph.add(new PluginInfo(
                    descriptor.id,
                    descriptor.title,
                    descriptor.dependencies,
                    host.isImportedPluginEnabled(descriptor.id),
                    activePluginIds.contains(descriptor.id),
                    false
            ));
        }
        return graph;
    }

    private String dependencyNames(Set<String> dependencies, PluginGraph graph) {
        if (dependencies.isEmpty()) {
            return "无";
        }
        List<String> names = new ArrayList<>();
        for (String dependency : dependencies) {
            PluginInfo info = graph.find(dependency);
            if (info == null) {
                names.add(dependency + "（缺失）");
            } else {
                names.add(info.title + "（" + statusText(info) + "）");
            }
        }
        return joinNames(names);
    }

    private String pluginNames(List<PluginInfo> plugins) {
        List<String> names = new ArrayList<>();
        for (PluginInfo plugin : plugins) {
            names.add(plugin.title + "（" + statusText(plugin) + "）");
        }
        return joinNames(names);
    }

    private String buildDependencyTreeText(PluginGraph graph) {
        StringBuilder builder = new StringBuilder();
        for (PluginInfo plugin : graph.plugins.values()) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            appendDependencyTree(builder, graph, plugin.id, new LinkedHashSet<>(), 0);
        }
        return builder.toString();
    }

    private void appendDependencyTree(StringBuilder builder, PluginGraph graph, String pluginId, Set<String> visiting, int depth) {
        PluginInfo plugin = graph.find(pluginId);
        appendIndent(builder, depth);
        if (plugin == null) {
            builder.append(pluginId).append("（缺失）");
            return;
        }
        builder.append(plugin.title).append("（").append(statusText(plugin)).append("）");
        if (!visiting.add(pluginId)) {
            builder.append(" · 循环依赖");
            return;
        }
        if (plugin.dependencies.isEmpty()) {
            builder.append("\n");
            appendIndent(builder, depth + 1);
            builder.append("无依赖");
        } else {
            for (String dependency : plugin.dependencies) {
                builder.append("\n");
                appendDependencyTree(builder, graph, dependency, new LinkedHashSet<>(visiting), depth + 1);
            }
        }
    }

    private void appendIndent(StringBuilder builder, int depth) {
        for (int i = 0; i < depth; i++) {
            builder.append("  ");
        }
        builder.append(depth == 0 ? "" : "- ");
    }

    private String statusText(PluginInfo plugin) {
        if (!plugin.enabled) {
            return "已停用";
        }
        if (!plugin.active) {
            return "依赖未满足";
        }
        return "已启用";
    }

    private String joinNames(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append("、");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class PluginGraph {
        final Map<String, PluginInfo> plugins = new LinkedHashMap<>();

        void add(PluginInfo plugin) {
            plugins.put(plugin.id, plugin);
        }

        PluginInfo find(String pluginId) {
            return plugins.get(pluginId);
        }

        List<PluginInfo> dependentsOf(String pluginId) {
            List<PluginInfo> dependents = new ArrayList<>();
            for (PluginInfo plugin : plugins.values()) {
                if (plugin.dependencies.contains(pluginId)) {
                    dependents.add(plugin);
                }
            }
            return dependents;
        }
    }

    private static final class PluginInfo {
        final String id;
        final String title;
        final Set<String> dependencies;
        final boolean enabled;
        final boolean active;
        final boolean builtIn;

        PluginInfo(String id, String title, Set<String> dependencies, boolean enabled, boolean active, boolean builtIn) {
            this.id = id;
            this.title = title;
            this.dependencies = dependencies;
            this.enabled = enabled;
            this.active = active;
            this.builtIn = builtIn;
        }
    }
}
