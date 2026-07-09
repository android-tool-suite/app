package com.example.shizukuaccessibilitygrant.plugin.model;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ImportedPluginDescriptor {
    public final String id;
    public final String title;
    public final String description;
    public final String version;
    public final String author;
    public final String formatVersion;
    public final String entryClass;
    public final String codePath;
    public final Set<String> requestedPermissions;
    public final Set<String> grantedPermissions;
    public final Set<String> dependencies;
    public final List<ImportedWidgetDescriptor> widgets;

    public ImportedPluginDescriptor(
            String id,
            String title,
            String description,
            String version,
            String author,
            String formatVersion,
            String entryClass,
            String codePath,
            Set<String> requestedPermissions,
            Set<String> grantedPermissions,
            Set<String> dependencies,
            List<ImportedWidgetDescriptor> widgets
    ) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.version = version;
        this.author = author;
        this.formatVersion = formatVersion;
        this.entryClass = entryClass;
        this.codePath = codePath;
        this.requestedPermissions = Collections.unmodifiableSet(new LinkedHashSet<>(requestedPermissions));
        this.grantedPermissions = Collections.unmodifiableSet(new LinkedHashSet<>(grantedPermissions));
        this.dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(dependencies));
        this.widgets = Collections.unmodifiableList(new ArrayList<>(widgets));
    }

    public static ImportedPluginDescriptor fromJson(String rawJson) throws JSONException {
        JSONObject root = new JSONObject(rawJson);
        JSONObject json = root.optJSONObject("plugin");
        if (json == null) {
            json = root;
        }

        String id = clean(json.optString("id"));
        String title = clean(json.optString("title"));
        if (id.isEmpty()) {
            throw new JSONException("缺少插件 id");
        }
        if (title.isEmpty()) {
            throw new JSONException("缺少插件 title");
        }
        return new ImportedPluginDescriptor(
                id,
                title,
                clean(json.optString("description", "外部导入插件")),
                clean(json.optString("version", "1.0")),
                clean(json.optString("author", "未知作者")),
                clean(root.optString("formatVersion", "1")),
                clean(json.optString("entryClass")),
                "",
                readStringSet(firstArray(root, json, "permissions", "requestedPermissions")),
                readStringSet(json.optJSONArray("grantedPermissions")),
                readStringSet(firstArray(root, json, "dependencies", "pluginDependencies")),
                readWidgets(firstArray(root, json, "widgets", "homeWidgets"))
        );
    }

    public String toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", "ats-plugin");
        root.put("formatVersion", formatVersion.isEmpty() ? "1" : formatVersion);

        JSONObject plugin = new JSONObject();
        plugin.put("id", id);
        plugin.put("title", title);
        plugin.put("description", description);
        plugin.put("version", version);
        plugin.put("author", author);
        if (!entryClass.isEmpty()) {
            plugin.put("entryClass", entryClass);
        }
        plugin.put("grantedPermissions", toArray(grantedPermissions));

        root.put("plugin", plugin);
        root.put("permissions", toArray(requestedPermissions));
        root.put("dependencies", toArray(dependencies));
        root.put("widgets", widgetsToArray(widgets));
        return root.toString();
    }

    public ImportedPluginDescriptor withGrantedPermissions(Set<String> permissions) {
        return new ImportedPluginDescriptor(
                id,
                title,
                description,
                version,
                author,
                formatVersion,
                entryClass,
                codePath,
                requestedPermissions,
                permissions,
                dependencies,
                widgets
        );
    }

    public ImportedPluginDescriptor withCodePath(String path) {
        return new ImportedPluginDescriptor(
                id,
                title,
                description,
                version,
                author,
                formatVersion,
                entryClass,
                path,
                requestedPermissions,
                grantedPermissions,
                dependencies,
                widgets
        );
    }

    private static List<ImportedWidgetDescriptor> readWidgets(JSONArray array) {
        List<ImportedWidgetDescriptor> widgets = new ArrayList<>();
        if (array == null) {
            return widgets;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                continue;
            }
            try {
                widgets.add(ImportedWidgetDescriptor.fromJson(json));
            } catch (JSONException ignored) {
            }
        }
        return widgets;
    }

    private static JSONArray firstArray(JSONObject root, JSONObject plugin, String first, String second) {
        JSONArray array = root.optJSONArray(first);
        if (array != null) {
            return array;
        }
        array = root.optJSONArray(second);
        if (array != null) {
            return array;
        }
        array = plugin.optJSONArray(first);
        if (array != null) {
            return array;
        }
        return plugin.optJSONArray(second);
    }

    private static Set<String> readStringSet(JSONArray array) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (array == null) {
            return values;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = clean(array.optString(i));
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }

    private static JSONArray toArray(Set<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static JSONArray widgetsToArray(List<ImportedWidgetDescriptor> widgets) throws JSONException {
        JSONArray array = new JSONArray();
        for (ImportedWidgetDescriptor widget : widgets) {
            array.put(widget.toJson());
        }
        return array;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
